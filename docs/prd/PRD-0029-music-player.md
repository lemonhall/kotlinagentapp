#!/usr/bin/env markdown
# PRD-0029：Files 页签内置 MP3 播放器（metadata + mini play bar + 可扩展 CLI 控制面）

日期：2026-02-18  
定位：在现有 **Files（Dashboard）** 页签内交付一个“只支持 `.mp3` 的本地播放器最小闭环”，满足：可读取 MP3 metadata、播放不受页签切换影响、提供 mini play bar；并预留一套“堆外控制面”（未来由 `terminal_exec` 白名单 CLI 调用）以便查询状态 / 控制播放 / 编辑 metadata。

## Vision

用户能在 App 内完成以下闭环：

1) 在 Files 页签根目录下有一个特殊目录 `musics/`（音乐库根目录，**严格小写**）；用户把 mp3 放进该目录（可包含任意层级子目录，等价“歌单/分类”）  
2) 仅在 `musics/` 子树内：列表对 `.mp3` 展示并读取基础 metadata（title/artist/album/duration…），点一下即可开始播放，并显示 mini play bar  
3) `musics/` 目录以外：Files 维持原有逻辑，不尝试读取 mp3 metadata，也不强行把 mp3 点击行为改成播放（避免复杂度扩散）  
4) 切换到 Chat/Web/Terminal/Settings 等其他页签时，**音乐仍继续播放**（不依赖当前 Fragment 生命周期）  
3.1) 退到后台或锁屏后，**音乐仍继续播放**  
5) 未来：Agent 可通过 `terminal_exec` 以可审计、可测试的方式查询“正在播放什么/播放进度/播放状态”，并执行 stop/切歌/seek 等操作；并在受控范围内编辑曲目的 metadata（ID3）。

## Background

- 当前 Files（Dashboard）页签已具备 `.agents` 工作区的浏览/编辑/导入能力（例如 PRD-0027）。  
- 音频播放属于“跨 UI 生命周期的长任务”：如果把播放器状态绑在 Fragment，会导致切换页签后播放中断或状态丢失。  
- 后续要接入 `terminal_exec` 的 CLI 控制面，要求播放器的运行时与 UI 解耦，并提供稳定的可测 API。
- “只在 `musics/` 子树启用音乐视图与 metadata”是为了把复杂度约束在一个明确边界内，让 Files 的通用文件管理能力不被音乐逻辑污染。

## Non-Goals（本期不做）

- 不新增新的 Bottom Navigation 页签（只在 Files 页签提供入口与 UI）。  
- 不支持除 MP3 外的格式（aac/flac/wav/ogg…不做）。  
- 不做在线播放/订阅/下载/歌词/均衡器/播放速度/音量增强。  
- 不做“全盘扫描媒体库”的复杂音乐库（默认只对 Files 里可见的 `.mp3` 提供播放能力）。  
- v29 不做“写入 metadata”（只读）；写入放到 v30+。

## Library Choice（建议）

推荐组合（面向 v29/v30）：

- 播放：AndroidX **Media3 ExoPlayer** + **MediaSession**（更适合长期维护/扩展控制面）  
- metadata 读取（v29）：优先 `MediaMetadataRetriever`（best-effort，不因 metadata 异常崩溃）  
- metadata 写入（v30）：需要额外 ID3 写入实现/库（见 Risks）。

> 降级方案：Android `MediaPlayer`（API 简单，但控制面与扩展性较弱；不推荐作为主方案）。

## Architecture Overview（必须解耦 UI）

核心原则：**播放器运行时独立于 Files Fragment**，UI 只是一个“观察 + 发指令”的外壳。

- `MusicPlayerController`（进程级 singleton 或 Application 级依赖）
  - 持有 ExoPlayer/队列/播放状态
  - 对外暴露：`play(filePath) / pause() / resume() / stop() / seekTo(ms) / next() / prev() / status()`
  - 对内保证：线程安全（串行化指令）、状态可观察（Flow/LiveData）
- `MusicPlaybackService`（建议 v29 起就上；至少要保证 tab 切换/旋转不影响播放）
  - 负责生命周期与资源（音频焦点、播放器释放）
  - v29 起必须采用**前台服务（Foreground Service）+ 媒体播放通知 + MediaSession**，以满足“后台/锁屏继续播放”
- Files UI（DashboardFragment）
  - `.mp3` 条目展示 metadata 摘要
  - 点击/长按动作触发 `MusicPlayerController` 指令
  - 展示 mini play bar（已确认：只在 Files 页签可见）

## Requirements（Req IDs）

### 0. 音乐库根目录约定（v29）

- REQ-0029-000：在 Files 根目录下约定一个特殊目录 `musics/`（**严格小写**）作为音乐库根目录（实现上为 `.agents/workspace/musics/`）；该目录不存在时由 App 自动创建。
- REQ-0029-001：仅在 `musics/` 子树内启用“音乐视图”：对 `.mp3` 展示可播放标识与 metadata；`musics/` 子树以外不得尝试读取 mp3 metadata（避免性能与复杂度扩散）。

### A. Files 入口与列表（v29）

- REQ-0029-002：在 `musics/` 子树内：点击 `.mp3` 条目默认行为为“开始播放”（而不是用系统外部播放器打开）。
- REQ-0029-002A：在 `musics/` 子树外：点击 `.mp3` 的行为保持 Files 既有逻辑（例如打开/分享/预览策略），不得强制接管为播放。
- REQ-0029-003：长按 `.mp3` 条目至少提供：播放/停止/下一首（若队列存在）/复制文件名（或路径）等最小动作集合（具体 UI 由 v29 计划定）。

### B. MP3 metadata 读取（v29）

- REQ-0029-010：对 `.mp3` 文件进行 metadata best-effort 读取：`title/artist/album/trackNumber/year/durationMs`（有则展示，无则 fallback）。
- REQ-0029-011：metadata 缺失/损坏时不得崩溃；必须 fallback 为文件名 +（可得的）duration。
- REQ-0029-012：读取逻辑必须可单元测试（输入为测试用 mp3/或可注入的 metadata provider）。

### C. 播放运行时（v29）

- REQ-0029-020：播放不受页签切换影响：从 Files 切到其他页签后音频仍持续播放。
- REQ-0029-020A：后台/锁屏继续播放：App 退到后台或锁屏后，音频仍持续播放（基于前台服务 + 媒体通知 + MediaSession，受系统约束影响时需可解释）。
- REQ-0029-021：播放器必须处理音频焦点（Audio Focus）：短暂失焦自动暂停/降低音量（由实现策略定），重新获得焦点可恢复。
- REQ-0029-022：播放状态必须可被 UI 订阅（例如 StateFlow/LiveData），保证 mini play bar 可刷新进度与按钮状态。
- REQ-0029-023：同一时刻只能有一个 active 播放器实例（防止多实例抢占焦点/资源）。
- REQ-0029-024：播放中必须展示媒体播放通知（至少包含播放/暂停），并在锁屏/蓝牙等场景可展示基础信息（受系统限制）。
- REQ-0029-025：Android 13+ 若通知权限未授予：不得崩溃；说明：媒体会话相关通知在部分场景可豁免 `POST_NOTIFICATIONS`，但不同 ROM 行为可能有差异；必须保持 App 内控播（mini play bar）可用，并在 `musics/` 给出可解释提示与引导。
- REQ-0029-026：前台播放实现必须满足 targetSdk=35 的平台约束：前台服务声明 `mediaPlayback` 类型，并确保在请求 AudioFocus / 播放时处于“前台 App 或运行中前台服务”的允许状态（避免被系统拒绝焦点导致锁屏/后台无法持续播放）。
- REQ-0029-027：在 `musics/` 视图提供一个“后台播放排障”入口（静态说明即可）：至少覆盖华为（含 Nova 9）常见的电量/后台限制开关，并解释“如果被系统杀，会发生什么、如何自查”。

### D. Mini play bar（v29）

- REQ-0029-030：mini play bar 至少展示：title（或文件名）、artist（如有）、播放/暂停、进度（文本或进度条），并提供进入“详情/播放列表”的入口（可选）。
- REQ-0029-031：mini play bar 不得要求新增页签；并且（已确认）**仅在 Files 页签可见**（其他页签不展示 mini bar）。
- REQ-0029-032：当用户离开 Files 页签时，仍必须可通过媒体通知控制播放/暂停（至少播放/暂停），作为“无 mini bar 的控播入口”。

### E. CLI 控制面（v30）

> 本组需求为 v30：用于后续 `terminal_exec` 接入（遵守 `paw-cli-add-workflow`：无 shell / 白名单 / 可审计 / 可测试）。

- REQ-0029-040：新增 `terminal_exec` 顶层命令 `music`，提供最小子命令：`status/play/pause/resume/stop/seek/next/prev`。
- REQ-0029-041：`music status` 返回结构化 JSON：`state(track, positionMs, durationMs, isPlaying, queueSize, queueIndex)`，并保证 stdout/result 截断策略可控。
- REQ-0029-042：所有 CLI 命令必须可单元测试（Robolectric），且每次执行被审计落盘到 `.agents/artifacts/terminal_exec/runs/<run_id>.json`。

### F. metadata 编辑（v30）

- REQ-0029-050：提供 `music meta get --in <path>`（或等价设计）读取并返回结构化 metadata（与 UI 一致口径）。
- REQ-0029-051：提供受控写入：`music meta set --in <path> --title ... --artist ...`，仅允许写入白名单字段；写入前必须显式 `--confirm`（防误操作）。
- REQ-0029-051A：写入字段范围（已确认）：尽可能覆盖常见字段：`title/artist/album/albumArtist/trackNumber/discNumber/year/date/genre/comment/composer/lyricist/lyrics/coverArt`（若部分字段受限于实现库/格式能力，必须在 v30 计划中明确“不支持清单”与原因）。
- REQ-0029-052：写入失败必须给出可解释错误（如 `NotSupported`/`InvalidMp3`/`PermissionDenied`），不得产出半写入损坏文件（必要时用临时文件 + 原子替换）。
- REQ-0029-053：tag 版本策略：默认**尽量保留**原文件的 ID3 版本；如因实现需要必须升级/转换版本，必须在 `result` 中显式返回（例如 `tagVersionBefore/tagVersionAfter`），并确保读写后 metadata 不丢字段。

### G. 播放器体验增强（v31）[已由 ECN-0004 变更]

- REQ-0029-060：封面渲染：在 Files 页签播放器 UI 中渲染专辑封面（优先读取 mp3 embedded picture；无则使用占位图），不得因封面缺失/损坏崩溃。
- REQ-0029-061：播放模式：支持 4 种模式并可在 UI 中切换：  
  1) 随机循环（永不停止）  
  2) 顺序循环（从上到下扫描，循环回到开头，永不停止）  
  3) 单曲循环（永不停止）  
  4) 播放一次（播放完当前曲目即停止/不自动续播）
- REQ-0029-062：自动续播：当曲目自然播放结束时，必须按 REQ-0029-061 的模式选择下一动作（自动下一首/循环/停止），不得“播完就停在 paused 但不续播”（除播放一次外）。
- REQ-0029-063：歌词渲染：若曲目包含歌词（至少支持 ID3v2 `USLT`），在播放器 UI 中可查看歌词文本；若歌词包含 LRC 时间戳（如 `[mm:ss.xx]`），则应随播放位置高亮当前行（best-effort）。
- REQ-0029-064：外置歌词（可选）：若同目录存在同名 `.lrc` 文件（如 `song.mp3` 对应 `song.lrc`），优先或可选读取并渲染（策略由 v31 计划锁定并写入验收）。
- REQ-0029-065：音量控制：提供 mute 切换与音量滑块（0.0~1.0），且用户设置需持久化（下次打开仍生效）。
- REQ-0029-066：下一首/上一首：在 UI 中提供 next/prev 控制；当队列 size=1 时应降级为禁用或等价行为（不崩溃、可解释）。

## Acceptance（硬口径）

### v29（核心播放器）

1. Files 根目录下存在 `musics/`（不存在则 App 自动创建）。  
2. 仅在 `musics/` 子树内：点击 `.mp3` 文件即可开始播放；并展示 metadata（best-effort）与 mini play bar。  
3. `musics/` 子树外：Files 不读取 mp3 metadata，且不改变既有 mp3 的打开/分享逻辑。  
4. 切换页签到 Chat/Web/Terminal/Settings：音频仍持续播放（不因 Fragment 销毁而停止）。  
5. 退到后台或锁屏：音频仍持续播放（基于前台服务 + 媒体通知 + MediaSession）。  
6. mini play bar 在 Files 页签可见，按钮能控制播放/暂停，进度能随播放更新。  
7. metadata 展示可用：至少能显示 title（或文件名）+ duration；若 mp3 含 ID3，能展示 artist/album 等。  
8. 反作弊：播放器运行时不允许绑在 Files Fragment；必须由 Service/Controller 承载，并有单测证明“Fragment 销毁后仍保持播放状态”。  
9. Verify（最少）：`.\gradlew.bat :app:testDebugUnitTest` exit code=0。  
10. 设备验收（手动，必须记录）：在 **华为 Nova 9** 上完成一次最小验收并记录结果（可写入 `docs/plan/v29-index.md` 的回顾区或独立 notes，但必须可追溯）：
    - 从 `musics/` 播放任意 mp3；
    - 锁屏 5 分钟：应持续播放；
    - 切到后台 5 分钟：应持续播放；
    - 通知栏存在媒体播放通知并可控制播放/暂停；
    - 若因系统限制导致被终止：必须记录触发条件与“后台播放排障”入口是否能引导用户完成设置。

### v30（CLI 控制面 + metadata 编辑）

1. `terminal_exec` 新增顶层命令 `music` 并按 REQ-0029-040/041 输出结构化 result。  
2. CLI 能在不依赖 Files UI 的情况下查询/控制播放状态（status/play/stop/seek/next…）。  
3. `music meta set` 具备 `--confirm` 门禁，且写入具备原子性（失败不损坏原文件）。  
4. Verify：`.\gradlew.bat :app:testDebugUnitTest` exit code=0（覆盖：status/play/stop/seek、非法参数、unknown file、confirm 门禁、写入失败回滚）。  

### v31（UX：封面/播放模式/歌词/音量）

1. Files 页签播放器 UI 可渲染封面：有 embedded picture 的 mp3 显示封面；无封面的 mp3 显示占位图（不得崩溃）。  
2. 支持 4 种播放模式（随机循环/顺序循环/单曲循环/播放一次），UI 可切换且状态可见。  
3. 自然播放结束行为正确：除“播放一次”外，均不会停止（必须自动续播）。  
4. 支持 next/prev：能切歌并更新 UI；队列 size=1 时行为合理且不崩溃。  
5. 歌词可查看：至少支持 ID3 `USLT` 文本展示；若包含 LRC 时间戳则随播放高亮（best-effort）。  
6. 音量控制可用：mute + 滑块可调节音量并持久化。  
7. Verify：`.\gradlew.bat :app:testDebugUnitTest` exit code=0（覆盖：播放模式状态机 + 歌词解析）。  


## Risks

- Android 后台播放策略（含华为/鸿蒙/EMUI）：不同 ROM 对后台/锁屏/电量优化限制不同；v29 已要求后台/锁屏播放，因此必须采用 Media3 + MediaSession( Service ) + 前台服务（`mediaPlayback` 类型）+ 媒体通知；并在 `musics/` 提供“后台播放排障”入口（覆盖华为 Nova 9 常见后台限制设置）；同时把“华为 Nova 9 手动验收”写入 DoD，失败必须留痕并进入下一轮（必要时走 ECN）。  
- `musics/` 目录的约定会引入“特殊目录”概念：需要在 UI 上明确提示其用途，避免用户困惑“为什么只有这里 mp3 才有 metadata/播放入口”。  
- metadata 写入：ID3 写入在 Android 上可用库有限，且需保证原子替换与兼容性（ID3v2.3/v2.4）。  
- 测试可控性：真实音频解码不适合 Robolectric 单测直跑；需要抽象 player/clock 或用 fake 证明状态机，必要时补 instrumentation test 覆盖最短播放链路。  
- 资源与焦点：AudioFocus / noisy intent（拔耳机）/ 来电等系统事件的行为需要明确策略，否则体验不稳定。  

## Open Questions（需要你确认，避免 v29/v30 返工）

1) （已确认）v29 要求：退到后台/锁屏也继续播放。  
2) （已确认）mini play bar：只在 Files 页签可见。  
3) （已确认）音乐文件来源：只播放 `musics/` 目录下的 mp3；不支持外部 `content://` URI，不支持 `musics/` 以外目录。  
4) （已确认）v30 metadata 写入：希望支持尽可能完整的字段范围，且包含 lyrics；tag 版本默认尽量保留，必要时可升级，但必须可追溯（见 REQ-0029-053）。
5) （已确认）音乐库根目录名称固定为 `musics/`（严格小写），后续如要改名需走迁移与 ECN。
