# v32 Plan：Files 电台播放器（radios/ VFS + 懒加载目录 + 直播流 + Favorites）

## Goal

在不新增新页签、不破坏 Files 通用文件管理能力的前提下，把电台作为“文件系统的一等公民”接入：

- `radios/` 作为特殊目录子树（与 `musics/` 同级）；
- 国家/地区目录与 station 列表支持懒加载 + 可解释刷新；
- station 落地为 `.radio` 描述文件，点击即可播放直播流；
- 复用现有音乐播放器的运行时（Service/Controller/MediaSession）与大部分 UI（mini play bar + 播放器面板），并按直播特性做降级；
- `favorites/` 用纯文件系统实现收藏。

## PRD Trace

- PRD-0030：REQ-0030-000 / REQ-0030-001
- PRD-0030：REQ-0030-010 / REQ-0030-011 / REQ-0030-012 / REQ-0030-013 / REQ-0030-014
- PRD-0030：REQ-0030-020 / REQ-0030-021 / REQ-0030-022
- PRD-0030：REQ-0030-030 / REQ-0030-031 / REQ-0030-032
- PRD-0030：REQ-0030-033
- PRD-0030：REQ-0030-040 / REQ-0030-041

## Scope

做（v32）：

- Files 根目录新增特殊目录约定：
  - `.agents/workspace/radios/`（严格小写）
  - `.agents/workspace/radios/favorites/`（严格小写）
- 目录数据源（Radio Browser）最小闭环：
  - 拉取国家/地区列表（用于 `radios/` 根目录渲染）
  - 拉取某国家的 station 列表（用于国家目录渲染）
  - station → `.radio`（UTF-8 JSON）落地与读取
- 懒加载与缓存：
  - 进入目录时触发拉取；允许缓存复用（默认 TTL=72h，或显式刷新）
  - 失败可解释（网络/解析/空结果），不得崩溃
- 播放：
  - 点击 `.radio`：播放 `streamUrl`
  - UI 复用音乐播放器：mini play bar + 播放器面板
  - 对直播流降级：不支持 seek/duration 时隐藏或置灰 seek，并展示“LIVE/直播”
  - 提供 next/prev：同目录下一台/上一台（队列 size=1 时合理降级，不崩溃且可解释）
- Favorites：
  - 收藏把 station `.radio` 写入 `radios/favorites/`
  - 从 favorites 播放同样遵守后台/锁屏不断播

不做（v32）：

- `terminal_exec radio` CLI（放到 v33）
- 收听行为日志（放到 v33，且必须用户同意）
- 全量同步/离线全库
- 录音/回放/定时录制

## Design Notes（关键取舍，避免返工）

### 目录落地 vs 纯虚拟

建议采用“写入式懒加载缓存”（PRD-0030 推荐方案 B）：

- “看到什么就落地什么”：进入国家目录时才在本地创建/更新对应 station `.radio` 文件；
- 用户能离线回看“已加载过的目录”，也能直接用文件操作做收藏/拷贝；
- 需要最小缓存策略（TTL/手动刷新）与文件命名去重（推荐 `name__shortId.radio`）。

补充口径（v32 锁定）：

- `radios/` 根目录直接列出所有国家/地区 + `favorites/`（不引入 `countries/` 中间层）。
- 国家目录 station 默认按“热度/投票（votes）”排序（如数据源可用），并提供手动刷新。

### 复用音乐播放器的边界

建议先走“最小侵入式扩展”：

- 复用现有前台服务 + MediaSession 路径（保证后台/息屏不断播的关键）；
- 复用现有 mini play bar / 播放器面板；
- 仅对直播流做 UI 降级（seek/duration）。

若未来分支膨胀，再抽象为 `AudioPlayerController`（v33+ 之后再评估）。

## Files（规划）

以下为建议改动点（实现时可微调，但必须保持“子树边界”不扩散）：

- Files/Dashboard（radios 子树识别、列表渲染、点击行为、收藏入口）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/DashboardFragment.kt`
- 播放器运行时（扩展支持直播流，并能输出可观察状态）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/media/MusicPlayerController.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/media/*`（transport/service/session 相关文件，以现有结构为准）
- radios 域模型与数据源（新增）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radios/*`（建议新增包）
- 单测：
  - `app/src/test/java/**`（新增：`.radio` schema 解析、缓存 TTL、目录加载失败可解释）

## Steps（Strict / TDD）

1) Analysis：锁定 `radios/` 根目录布局与缓存策略（TTL/刷新入口），并最终敲定 `.radio` schema v1（字段名与必填项）。  
2) TDD Red：为 `.radio` 解析与“路径安全命名”写单测（正常/缺字段/非法 JSON/非法路径字符）。  
3) TDD Red：为“国家列表加载/失败可解释/缓存复用/刷新”写单测（建议抽象 Repository，便于用 fake client）。  
4) TDD Green：实现 RadioBrowser client + repository + 缓存落地（只做到目录可展示与可刷新）。  
5) TDD Red：为“点击 `.radio` 触发播放 + UI 降级 + next/prev”写单测（至少覆盖：seek 不可用时 UI 状态；同目录下一台/上一台）。  
6) TDD Green：扩展现有播放器运行时支持直播流（Uri）并接入 UI；确保切页签/后台/锁屏不断播路径不回退，并接入同目录队列语义（next/prev）。  
7) Favorites：实现收藏写入/去重策略，并补单测（收藏后在 `favorites/` 可见且可播放）。  
8) Verify：`.\gradlew.bat :app:testDebugUnitTest` exit code=0。  
9) Device smoke（手动证据）：真机播放电台 → 锁屏/后台 5 分钟不断播 → 通知栏可控制，并把结果记录到 `docs/plan/v32-index.md` 的 Review。  

## Risks

- station 数量大：需要 limit/排序与分页策略，否则列表加载慢。  
- broken stream 多：必须“失败可解释 + 快速切台”，避免用户以为 App 坏了。  
- 直播流与音乐 UI 的差异：seek/duration/上一首下一首都需要明确降级策略。  
