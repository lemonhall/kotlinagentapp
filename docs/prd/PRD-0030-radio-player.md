# PRD-0030：Files 页签内置 Radio 电台播放器（radios/ VFS + 直播流 + Favorites + 可扩展 CLI 控制面）

日期：2026-02-19  
定位：在现有 **Files（Dashboard）** 页签内，新增一个与 `musics/` 同级的特殊目录 `radios/`，把“全球互联网电台”映射为目录树与 `.radio` 描述文件；点击即可播放直播流，并复用现有播放器的大部分运行时与 UI（含后台/息屏播放），同时预留 `terminal_exec` 的 CLI 控制面与（可选）用户同意后的收听行为日志能力。

## Vision

用户能在 App 内完成以下闭环：

1) Files 根目录下存在特殊目录 `radios/`（**严格小写**，实现路径为 `.agents/workspace/radios/`）；不存在时由 App 自动创建  
2) 仅在 `radios/` 子树内启用“电台视图”：把国家/地区目录按需懒加载出来；目录最深处是电台条目（以 `.radio` 文件呈现）  
3) 点击任意 `.radio` 文件即可开始播放对应直播流，并展示 mini play bar + 播放器面板（复用音乐播放器 UI，按直播流特性做必要降级）  
4) 切换到 Chat/Web/Terminal/Settings 等其他页签时，**电台仍继续播放**（不依赖当前 Fragment 生命周期）  
4.1) 退到后台或锁屏后，**电台仍继续播放**（与音乐播放器同级别保障）  
5) 在 `radios/` 根目录下提供 `favorites/`（**严格小写**）用于收藏电台：收藏本质上是把电台 `.radio` 文件复制/写入到 `favorites/`（Everything is FileSystem）  
6) 未来：Agent/人类可通过 `terminal_exec` 以白名单 CLI 查询/控制电台播放状态（`radio status/play/stop/...`），并且（在用户明确同意后）记录最小化收听行为日志以支持后续推荐/分析

## Background

- 现有 Files 页签已落地了“特殊目录子树启用额外能力”的边界策略（`musics/` 只在其子树内启用 mp3 播放与 metadata）。  
- 电台与音乐一样属于“跨 UI 生命周期的长任务”：必须由 Service/Controller 承载，避免页签切换/旋转导致中断。  
- 电台目录不适合全量同步（数据量大、流可用性变化快），应采用“进入目录时懒加载 + 缓存 + 可手动刷新”的策略。  
- “把电台当文件系统”能最大化复用现有 Files 的能力：目录导航、面包屑、搜索、长按菜单、收藏（即文件操作）等。

## Non-Goals（本期不做）

- 不新增新的 Bottom Navigation 页签（入口只在 Files 页签）。  
- 不做“全量同步 9 万+ 电台”的离线库。  
- 不做录音/回放/定时录制/断点续播。  
- 不做“账号登录/跨设备同步收藏”（收藏仅本机工作区）。  
- 不做复杂推荐算法（仅提供可选行为日志的基础设施与口径）。  
- 不保证所有电台都可播放（broken stream 必然存在；必须可解释、可跳过、可刷新）。  

## Data Source（目录来源）

目录数据源建议使用 **Radio Browser** 的公开目录（无需 API Key），通过 REST 拉取国家/地区列表与 station 列表。

约束：

- 网络不稳定/代理环境下必须能失败可解释（超时/解析失败/无可用流）。  
- station 可能返回不可用流：播放失败不应导致崩溃；应提示并允许重试/刷新。  

## Design Options（VFS 映射方式）

### 方案 A：纯虚拟（不落地 station 文件）

- 进入 `radios/` 目录时实时拉取并在 UI 中“虚拟渲染”目录与条目；仅 `favorites/` 真实落地为文件。  
- 优点：本地文件更干净、无需清理缓存文件。  
- 缺点：与“Everything is FileSystem”的理念不完全一致；离线/弱网时列表不可用；复用“文件搜索/批量操作”的能力会受限。  

### 方案 B：写入式懒加载缓存（推荐）

- 目录结构与 station `.radio` 文件**按需创建/更新**到 `.agents/workspace/radios/**`；目录可持久存在，station 条目可缓存并带 TTL。  
- 优点：Files 体验最一致；离线可回看已加载内容；收藏可直接复用“文件复制/移动”。  
- 缺点：需要缓存清理策略（TTL/最大数量）。  

### 方案 C：全量同步（不推荐）

- 启动或定时把全量目录同步到本地。  
- 缺点：成本高、无必要、极易变慢；不符合本项目“边界与最小闭环”的策略。  

本 PRD 推荐采用 **方案 B**。

## `.radio` 文件约定（最小 schema）

`.radio` 文件内容为 UTF-8 JSON（单文件即可被播放器与 CLI 消费），示例（字段允许增量扩展）：

```json
{
  "schema": "kotlin-agent-app/radio-station@v1",
  "id": "radio-browser:<uuid>",
  "name": "BBC World Service",
  "streamUrl": "https://...",
  "homepage": "https://...",
  "faviconUrl": "https://...",
  "country": "United Kingdom",
  "state": "England",
  "language": "English",
  "tags": ["news", "talk"],
  "codec": "MP3",
  "bitrateKbps": 128,
  "source": {
    "provider": "radio-browser",
    "lastSyncedAt": "2026-02-18T00:00:00Z",
    "query": { "by": "country", "value": "United Kingdom" }
  }
}
```

### 文件命名与路径安全

- station 文件名建议：`<display-name>__<short-id>.radio`（避免同名覆盖），并对非法路径字符做替换（Windows 风格禁用字符集同样适用）。  
- `favorites/` 下允许同台多份（不同 query 来源）或去重（由实现选择，但必须可解释）。  

## Architecture Overview（复用音乐播放器）

原则：**电台播放器运行时独立于 Files UI**，并尽可能复用既有音乐播放器能力。

推荐最小增量路径（从易到难）：

1) 在现有 `MusicPlayerController` / `Media3MusicTransport` 的能力上扩展“播放网络直播流（Uri）”入口（例如 `playRadioNow(station)`），并在状态模型里区分 `LocalTrack` vs `RadioStation`  
2) UI 复用：mini play bar + 播放器面板对直播流做降级（例如 duration/seek 不可用时隐藏或置灰；显示 “LIVE”）  
3) 后续如发现音乐/电台分支越来越多，再抽象为 `AudioPlayerController`（音乐与电台都是 `PlaybackItem`）  

约束：

- 仍保持“子树边界”：只在 `radios/` 子树内把 `.radio` 点击行为接管为播放；其他目录的 `.radio` 视为普通文件（避免逻辑扩散）。  
- 后台/息屏播放必须复用现有前台服务 + MediaSession 的路径，禁止把直播流绑回 Fragment 生命周期。  

## Requirements（Req IDs）

### 0) 电台库根目录（v32）

- REQ-0030-000：Files 根目录下约定特殊目录 `radios/`（严格小写，对应 `.agents/workspace/radios/`）；目录不存在时 App 自动创建。  
- REQ-0030-001：仅在 `radios/` 子树内启用“电台视图”：展示国家/地区目录与 `.radio` 条目；`radios/` 子树以外不得自动把 `.radio` 当成可播放电台（避免复杂度扩散）。  

### A) 目录树与懒加载同步（v32）

- REQ-0030-010：进入 `radios/` 根目录时展示国家/地区目录列表（按需拉取/缓存）；国家目录进入后展示 station 列表（懒加载）。  
- REQ-0030-011：station 列表必须可缓存（写入式懒加载或等价机制），并具备可解释的刷新入口（例如“下拉刷新/菜单刷新/点击刷新文件”之一）。  
- REQ-0030-012：失败必须可解释：网络失败/解析失败/空列表/被限流等场景必须返回稳定错误信息，不得崩溃。  
- REQ-0030-013：国家目录 station 列表默认按“热度/投票（votes）”降序排序（如数据源可用）；无 votes 时回退为稳定排序（例如按名称）。  
- REQ-0030-014：缓存 TTL 默认 72 小时；超过 TTL 时应触发刷新（或提示可刷新），并保持失败可解释。  

### B) Favorites（v32）

- REQ-0030-020：`radios/` 根目录下存在 `favorites/`（严格小写）；不存在时 App 自动创建。  
- REQ-0030-021：用户可把任意 station 收藏到 `favorites/`；收藏结果必须在文件系统中可见（写入 `.radio` 文件）。  
- REQ-0030-022：从 `favorites/` 播放的电台也必须可后台/息屏持续播放。  

### C) 播放（v32）

- REQ-0030-030：在 `radios/` 子树内点击 `.radio` 文件默认行为为“开始播放对应 `streamUrl`”。  
- REQ-0030-031：播放器 UI 复用音乐播放器：mini play bar 可见、可暂停/继续/停止；直播流不支持 seek/duration 时必须降级（隐藏或置灰 seek）。  
- REQ-0030-032：播放失败（无法连接/解码失败/被重定向/超时）必须可解释，且不影响返回列表浏览。  
- REQ-0030-033：在电台播放 UI 中提供 next/prev（同目录下一台/上一台）控制；当当前目录 station 列表 size=1 时必须合理降级（禁用或等价行为），不得崩溃且可解释。  

### D) 后台/息屏（v32）

- REQ-0030-040：切换页签不影响播放（控制面与运行时解耦）。  
- REQ-0030-041：退到后台或锁屏后，电台仍可持续播放（与音乐同级别保障）。  

### E) CLI 控制面（v33+，按 paw-cli-add-workflow）

- REQ-0030-050：为 `terminal_exec` 增加顶层命令 `radio`：支持 `radio status/play/pause/resume/stop` 的最小闭环，并输出结构化 `result` 供 agent 消费。  
- REQ-0030-051：`radio play` 必须是**受控输入**：只允许播放 `.agents/workspace/radios/**` 下的 `.radio` 文件（或明确的 `stationId`），其他路径必须拒绝并给出稳定 `errorCode`。  
- REQ-0030-052：新增 builtin skill（例如 `radio-cli`）并在 `AgentsWorkspace` 初始化时安装到 `.agents/skills/`。  

### F) 收听行为日志（v33+，需用户同意）

- REQ-0030-060：提供“是否记录收听行为日志”的开关（默认关闭）；未开启时不得记录可用于还原个人偏好的行为日志。  
- REQ-0030-061：开启后，最小化记录：播放开始/停止/切台/失败原因（不记录 secrets，不记录完整 URL 的敏感 query 参数），落盘到 `.agents/artifacts/` 下的公开路径并在 UI 中明确展示该路径。  
- REQ-0030-062：日志应可被用户一键清空（清空前必须二次确认）。  

## Acceptance（硬口径）

### v32（radios/ VFS + 懒加载目录 + 直播播放 + Favorites）

1) Files 根目录下存在 `radios/`（不存在则 App 自动创建）。  
2) 仅在 `radios/` 子树内：展示国家/地区目录；进入某国家目录后按需加载并展示 station 列表（无需全量同步）。  
3) 点击 station（`.radio`）即可开始播放其直播流；mini play bar 可见且可暂停/继续/停止，并提供 next/prev（同目录下一台/上一台）。  
4) 直播流无 duration/seek 时 UI 必须降级（不得显示错误进度、不得崩溃）。  
5) 切换到其他页签：直播仍持续播放。  
6) 锁屏与后台：直播仍持续播放（基于前台服务 + 媒体通知 + MediaSession；复用音乐播放器路径）。  
7) `favorites/` 存在且可收藏/取消收藏，收藏结果在文件系统中可见（`.radio` 文件）。  
8) 反作弊：不得把电台播放状态绑在 Files Fragment；必须由 Service/Controller 承载，并有单测证明“Fragment 销毁后仍保持播放状态”。  
9) Verify：`.\gradlew.bat :app:testDebugUnitTest` exit code=0。  
10) 设备验收（手动，必须记录）：在至少 1 台真机上完成一次最小验收并记录：
    - 从 `radios/` 播放任意电台；
    - 锁屏 5 分钟：应持续播放；
    - 切到后台 5 分钟：应持续播放；
    - 通知栏存在媒体播放通知并可控制播放/暂停；
    - 若因系统限制被终止：必须记录触发条件与排障入口策略（如有）。  

### v33（radio CLI + 可选收听日志）

1) `terminal_exec` 新增顶层命令 `radio`，支持 `radio status/play/pause/resume/stop`，help 可用，且输出结构化 result（遵守 paw-cli-add-workflow）。  
2) `radio play` 的输入必须受控：只允许 `.agents/workspace/radios/**` 的 `.radio` 或明确 stationId；越界必须拒绝并可解释。  
3) 收听行为日志默认关闭；开启需用户明确同意；可清空（清空前二次确认）。  
4) Verify：`.\gradlew.bat :app:testDebugUnitTest` exit code=0（覆盖：CLI 成功/失败路径 + 日志开关行为）。  

## Risks

- 直播流质量不稳定：broken stream、重定向、codec 不兼容都可能发生；必须把“失败可解释 + 可快速切台”作为核心体验。  
- 目录数据量大：国家目录可能返回大量 station；需要分页/limit/排序策略，以及缓存 TTL 与清理策略。  
- UI 复用的边界：音乐 UI 的 seek/duration/上一首下一首对直播流不完全成立；必须明确降级策略，避免误导。  
- 行为日志敏感性：必须有显式同意、默认关闭、可清空、路径透明，并避免记录可识别个人的敏感信息。  

## Locked Decisions（已确认，作为实现口径）

1) `radios/` 根目录布局：**根目录直接列出所有国家/地区 + `favorites/`**（不引入额外 `countries/` 中间层）。  
2) station 文件扩展名：固定为 **`.radio`**（UTF-8 JSON），v32/v33 不支持 `.m3u/.pls`。  
3) 国家目录 station 排序：默认按 **热度/投票（votes）**（如数据源可用）；并提供“刷新”入口。  
4) 直播流的 next/prev：v32 **需要** next/prev（同目录下一台/上一台）的队列语义。  
5) 缓存 TTL：默认 **72 小时**（支持手动刷新）。  
