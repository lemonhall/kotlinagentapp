# v38：Radio 模块总览（产品设计 / 架构 / Radio CLI）

日期：2026-02-19  
工程：kotlin-agent-app（Android）  
范围：Radio 模块（`radios/` VFS + 直播播放 + Favorites + `terminal_exec radio` 控制面 + 可选收听日志）

> 关联文档：
> - 产品需求：`docs/prd/PRD-0030-radio-player.md`
> - 录制/转录/翻译/实时翻译：`docs/prd/PRD-0034-radio-recording-transcript-translation-live.md`
> - 计划：`docs/plan/v32-radio-player.md`、`docs/plan/v33-radio-cli-and-listening-logs.md`、`docs/plan/v37-radio-playback-reliability.md`
> - 计划（录制→实时翻译 roadmap）：`docs/plan/v39-radio-recording.md`、`docs/plan/v40-radio-offline-transcript.md`、`docs/plan/v41-radio-offline-translation.md`、`docs/plan/v42-radio-language-learning.md`、`docs/plan/v43-radio-dual-language.md`、`docs/plan/v44-asr-tts-modularization.md`、`docs/plan/v45-radio-live-translation-full.md`、`docs/plan/v46-radio-live-AudioFocusManager.md`
> - 内置技能说明书：`app/src/main/assets/builtin_skills/radio-cli/SKILL.md`

---

## 1. 产品涉及（Product Scope）

Radio 模块的定位：在 **Files（Dashboard）** 页签内，把“互联网电台”映射为一个可浏览的目录树，并把每个电台落盘为 `.radio` 文件，使其成为“文件系统的一等公民（Everything is FileSystem）”。播放链路复用音乐播放器的 Service/Controller/MediaSession 路径，保障 **切页签/后台/锁屏不断播**。

核心闭环：

1) Files 根目录出现 `radios/`（严格小写；真实落盘为 `.agents/workspace/radios/`）。  
2) 进入 `radios/` 展示国家/地区目录（懒加载、可刷新、可解释失败）。  
3) 进入国家目录展示电台条目：每个条目是一个 `.radio` 文件，点击即可播放直播流。  
4) `favorites/` 作为收藏夹：收藏本质是将电台 `.radio` 文件写入/复制到 `radios/favorites/`。  
5) Agent/人类可通过 `terminal_exec` 执行白名单 CLI：`radio status/play/pause/resume/stop/...`，实现可审计、可测试的“堆外控制面”。  
6) 可选收听行为日志（默认关闭）：用户在 Settings 明确同意后，才会把播放/暂停/停止/失败等事件记录到 JSONL（可一键清空，且二次确认）。

非目标（当前口径）：

- 不做全量离线同步电台库（只做懒加载 + 缓存 TTL + 手动刷新）。  
- 不保证所有电台可播（broken stream 是现实约束；策略是“可解释 + 快速换台 + 可刷新”）。  
- 不把复杂“电台搜索”做成 `radio search` 子命令（发现流程由索引 + explore 子 agent 完成，见 `radio-cli` 技能）。  

---

## 2. 落盘与数据结构（Workspace & File Formats）

### 2.1 工作区目录结构（真实落盘）

Radio 模块的主要落盘位置在 App 内部存储的 `.agents/` 工作区（由 `AgentsWorkspace.ensureInitialized()` 创建）：

```
.agents/workspace/radios/
  .countries.index.json      # 国家索引（pretty JSON，多行）
  .countries.meta.json       # 国家索引缓存元数据（TTL）
  _STATUS.md                 # radios 根目录状态（可解释失败）
  favorites/                 # 收藏夹（Everything is FileSystem）
    *.radio
  {CC}__{CountryName}/       # 国家目录（如 CN__China/）
    .stations.index.json     # 电台索引（pretty JSON，多行）
    .stations.meta.json      # 电台缓存元数据（TTL）
    _STATUS.md               # 国家目录状态（可解释失败）
    *.radio                  # 单电台文件（UTF-8 JSON + 尾随换行）
```

其中一些对“Agent 电台发现流程”很关键：

- `.countries.index.json`：用于从关键词/国家定位国家目录（`dir`）。  
- `.stations.index.json`：用于从关键词定位 `.radio` 路径（`path`），避免全目录扫描与逐个读取 `.radio`。  

### 2.2 `.radio` 文件（电台描述文件）

实现位置：`app/src/main/java/com/lsl/kotlin_agent_app/radios/RadioStationFileV1.kt`

最小 schema（必填字段）：  

- `schema`：固定 `kotlin-agent-app/radio-station@v1`  
- `id`：例如 `radio-browser:<uuid>`  
- `name`：电台名称  
- `streamUrl`：直播流 URL（仅允许 `http://` 或 `https://`）  

可选扩展字段（用于 UI/索引/排障/未来扩展）：

- `homepage`、`faviconUrl`、`country`、`state`、`language`、`tags[]`、`codec`、`bitrateKbps`、`votes`  
- `source{ provider,url,fetchedAtSec }`：标注数据源与抓取时间

解析策略（关键点）：

- 使用 `kotlinx.serialization.json`，`ignoreUnknownKeys=true`，允许未来增量扩字段。  
- `validateOrThrow()` 会严格校验必填字段与 `streamUrl` scheme。  

### 2.3 国家索引 `.countries.index.json`

由 `RadioRepository.syncCountries()` 生成，schema：

- `schema`: `kotlin-agent-app/radios-countries-index@v1`
- `generatedAtSec`: number
- `countries[]`: `{ dir, name, code?, stationCount? }`

其中 `dir` 是国家目录名（例如 `CN__China`），命名规则见 `RadioPathNaming.countryDirName(...)`。

### 2.4 电台索引 `.stations.index.json`

由 `RadioRepository.syncStationsForCountryDir(...)` 生成，schema：

- `schema`: `kotlin-agent-app/radios-stations-index@v1`
- `dir`: string（国家目录名）
- `generatedAtSec`: number
- `stations[]`: 最小包含：
  - `name`
  - `path`：形如 `workspace/radios/{dir}/{StationName}__{shortId}.radio`（注意这里是 `workspace/` 前缀，用于对外展示与索引搜索）
  - `tags[]`
  - `language?`、`votes?`

> 约束：所有“供 Agent 读取的索引 JSON”必须使用 pretty 多行输出，避免工具截断与上下文爆炸；实现中已使用 `prettyPrint=true`。

### 2.5 缓存元数据 `.countries.meta.json` / `.stations.meta.json`

schema：`kotlin-agent-app/radios-cache-meta@v1`，字段：

- `fetchedAtSec`: number

TTL（当前实现）：默认 72 小时（国家索引与电台索引一致）。

### 2.6 可选收听行为日志（JSONL）

实现位置：`app/src/main/java/com/lsl/kotlin_agent_app/listening_history/ListeningHistoryStore.kt`

- 路径：`.agents/artifacts/listening_history/events.jsonl`
- 默认关闭：只有用户在 Settings 同意开启后才写入
- 每行 1 条 JSON（JSONL），schema：`kotlin-agent-app/listening-event@v1`

事件最小字段集合（示意）：

```json
{
  "schema": "kotlin-agent-app/listening-event@v1",
  "ts": "2026-02-19T12:34:56.000Z",
  "source": "radio",
  "action": "play",
  "item": { "radioFilePath": "workspace/radios/CN__China/中国之声__a1b2c3d4e5.radio", "name": "中国之声", "country": "China" },
  "userInitiated": true,
  "error": null
}
```

> 隐私口径：未开启时不得写入；清空需要二次确认；错误信息尽量不包含完整 URL（尤其是 query/token）。

---

## 3. 整体架构设计（Architecture）

### 3.1 一句话架构

Radio = `Files(radios/ UI)` + `RadioRepository(懒加载落盘缓存)` + `MusicPlayerController(直播播放/队列/状态)` + `terminal_exec radio(可审计控制面)` + `ListeningHistory(可选 JSONL 日志)`。

### 3.2 模块与职责边界（关键类/文件）

**A) Workspace 初始化与边界**

- `app/src/main/java/com/lsl/kotlin_agent_app/agent/AgentsWorkspace.kt`
  - 创建 `.agents/workspace/radios` 与 `favorites/`，并安装内置技能（`radio-cli`）。
  - 统一做路径归一化与路径穿越防护（拒绝 `..`，拒绝逃逸 `.agents` 根）。

**B) 目录数据源与落盘缓存**

- `app/src/main/java/com/lsl/kotlin_agent_app/radios/RadioBrowserClient.kt`
  - Radio Browser REST client（默认 base：`https://de1.api.radio-browser.info`）。
  - 抽象 `RadioBrowserTransport` + TestHooks，便于单测替换网络层。
- `app/src/main/java/com/lsl/kotlin_agent_app/radios/RadioRepository.kt`
  - `syncCountries(force)`：生成 `.countries.index.json`、`.countries.meta.json`、根目录 `_STATUS.md`，并确保国家目录存在。
  - `syncStationsForCountryDir(countryDirName, force)`：拉取 station 列表（当前 limit=200），生成：
    - `{dir}/*.radio`
    - `{dir}/.stations.index.json`
    - `{dir}/.stations.meta.json`
    - `{dir}/_STATUS.md`
  - 失败必须“可解释”：将错误写入 `_STATUS.md` 并返回 message。

**C) 播放器运行时（复用 music）**

- `app/src/main/java/com/lsl/kotlin_agent_app/media/MusicPlaybackService.kt`（前台 Service + MediaSession 路径）
- `app/src/main/java/com/lsl/kotlin_agent_app/media/MusicPlayerController.kt`
  - `playAgentsRadioNow(agentsPath, streamUrlOverride?)`：播放 `.radio`（严格门禁：只允许 `radios/` 子树 + `.radio` 后缀）。
  - 直播特性：`isLive=true`、`durationMs=null`，UI 与状态推导按直播降级。
  - 状态流：持续输出 `transport snapshot`（`isPlaying/playWhenReady/playbackState/mediaId/...`）用于减少“刚 play 就误判 idle”的问题。

**D) 直播 URL 解析与可靠性**

- `app/src/main/java/com/lsl/kotlin_agent_app/radios/StreamUrlResolver.kt`
  - 解析/归类：Direct/Pls/M3u/Hls/Asx/Xspf 等；限制重定向次数与响应体大小，避免“下载整段音频”。
  - 支持从 `.pls/.m3u/.asx/.xspf` 中提取候选 URL（最多 10 个）。
  - 对 HLS（`.m3u8`）保持保守：不强行解析候选，让 Media3 HLS 模块处理。

**E) Files（Dashboard）与 radios/ 子树 UI**

- `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/FilesViewModel.kt`
  - 在 `radios/` 下做“目录刷新 = sync”语义：
    - `radios/` 刷新触发 `radioRepo.syncCountries(force)`
    - `radios/{country}/` 刷新触发 `radioRepo.syncStationsForCountryDir(..., force)`
- `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/DashboardFragment.kt`
  - `.radio` 点击触发播放；
  - `.radio` 长按支持收藏到 `favorites/`；
  - radios 子树有特定提示文案与排序策略（目录优先、`.radio` 条目优先等）。

**F) CLI 控制面（Pseudo Terminal）**

- `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalExecTool.kt`
  - `terminal_exec` 工具：只执行白名单命令、无 shell、无外部进程；并把每次运行审计落盘到：
    - `.agents/artifacts/terminal_exec/runs/{run_id}.json`
- `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/radio/RadioCommand.kt`
  - 实现 `radio` 顶层命令：status/play/pause/resume/stop/fav/sync/help。

**G) 可选收听行为日志（默认关闭）**

- `app/src/main/java/com/lsl/kotlin_agent_app/listening_history/ListeningHistoryStore.kt`
  - 路径：`.agents/artifacts/listening_history/events.jsonl`
  - 默认关闭；只有 Settings 同意开启后，才会写入事件。
- `app/src/main/java/com/lsl/kotlin_agent_app/ui/settings/SettingsFragment.kt`
  - 开关 + 路径透明展示 + 清空按钮（二次确认）。

---

## 4. Radio CLI 整体设计（`terminal_exec radio ...`）

### 4.1 为什么要做 Radio CLI（控制面定位）

UI（Files）适合“人类点击与浏览”，但 Agent 需要：

- 可审计：每次操作都有稳定命令文本 + 可落盘审计记录；  
- 可测试：不依赖 UI 生命周期；  
- 可结构化消费：命令输出有稳定 `result` 字段；  
- 可控输入：禁止任意文件/任意 URL 越权播放。  

因此引入 `terminal_exec` + 白名单命令 `radio`，形成“堆外控制面”。

### 4.2 terminal_exec 输出包裹（Tool Output）

`terminal_exec` 会把命令输出包装为 JSON，核心字段：

- `run_id`
- `exit_code`
- `stdout` / `stderr`
- `error_code?` / `error_message?`
- `result`（结构化 JSON，供 agent 使用）
- `artifacts[]`
- `argv[]`（回显，便于排障）

同时会把审计 JSON 落盘到 `.agents/artifacts/terminal_exec/runs/`（注意：审计不记录 stdin）。

### 4.3 radio 命令总览（Subcommands）

命令列表（见 `radio --help`）：

- `radio status`
- `radio play (--in <path> | --in_b64 <base64-utf8-path>) [--await_ms <ms>]`
- `radio pause` / `radio resume` / `radio stop`
- `radio fav add [--in <path> | --in_b64 <...>]`
- `radio fav rm  [--in <path> | --in_b64 <...>]`
- `radio fav list`
- `radio sync countries [--force]`
- `radio sync stations (--dir <country-dir> | --cc <CC>) [--force]`

### 4.4 路径口径（`workspace/` vs `.agents/`）

Radio CLI 内部会对输入路径做归一化：

- 允许传 `.agents/workspace/radios/...`
- 也允许传更“用户友好”的 `workspace/radios/...`（会自动映射到 `.agents/workspace/...`）

路径强制门禁：

- `radio play` 仅允许 `.agents/workspace/radios/**.radio`（否则 `NotInRadiosDir` / `NotRadioFile`）。  
- `fav add/rm` 同样只允许 radios 子树；未给 `--in` 时默认操作“当前正在播放的电台”，否则报 `NotPlayingRadio`。  

为避免路径含空格/Unicode 造成解析问题，提供：

- `--in_b64`：对 UTF-8 路径做 base64，再传入 CLI（最稳）。

### 4.5 `radio status`（状态读口）

目标：给 agent 一个“足够稳定”的状态读口，避免刚播放时误判 idle。

result 关键字段：

- `state`: `idle|playing|paused|stopped|error`
- `station`: 仅当“当前确实在播 radios/*.radio”时提供：
  - `path`（workspace/radios/...）
  - `id?` / `name?` / `country?` / `favicon_url?`
- `transport`: 结构化快照（用于判定缓冲中/连接状态）：
  - `is_connected` / `playback_state` / `play_when_ready` / `is_playing`
  - `media_id`（通常为 agentsPath）
  - `position_ms` / `duration_ms?`
- `warning_message?` / `error_message?`

### 4.6 `radio play`（播放 + 可选验证窗口）

输入：

- `--in` / `--in_b64`：目标 `.radio` 文件路径（必须在 radios 子树）
- `--await_ms`：可选等待窗口（0~30000ms）

行为（关键点）：

1) 读取 `.radio` 并解析 `RadioStationFileV1`；非法则 `InvalidRadio`。  
2) 通过 `StreamUrlResolver.resolve(streamUrl)` 做 best-effort 解析：
   - 若拿到 candidates，会构造 `attemptUrls`（最多 10 个、去重），逐个尝试播放；
   - HLS（`.m3u8`）走保守路径：尝试 `finalUrl` 交给 Media3 HLS 模块处理。
3) 若 `--await_ms > 0`：
   - CLI 会轮询 transport 状态，直到 `isPlaying=true` 或 controller 进入 `Error` 或超时；
   - 如果 outcome=error 且还有候选 URL，会自动尝试下一个 URL（减少 broken stream 挫败感）。

输出：在 `radio status` 的 result 基础上，额外附加 `result.play`：

- `play.in`: 原始输入路径
- `play.attempted_url`: 本次实际尝试的 URL
- `play.resolution`（若解析成功）：
  - `final_url`
  - `classification`（Direct/Pls/M3u/Hls/...）
  - `candidates[]`
- `play.verify`（仅当 await_ms>0）：
  - `await_ms` / `elapsed_ms` / `polls`
  - `outcome`: `playing|error|timeout`
  - `attempt_index` / `attempts_total`

### 4.7 `radio pause|resume|stop`

门禁：仅当当前在播 radio（`agentsPath` 以 `.radio` 结尾且位于 radios 子树）时允许，否则 `NotPlayingRadio`。

输出：复用 `radio status` 的结构化 result（并把 stdout 简化为 `paused/resumed/stopped`）。

### 4.8 `radio fav ...`（收藏）

- `radio fav add`：
  - 默认收藏“当前正在播放的电台”；也可用 `--in/--in_b64` 指定。
  - 收藏落盘：写入 `.agents/workspace/radios/favorites/{StationName}__{shortId}.radio`（若已存在不重复写）。
- `radio fav rm`：同上，移除对应 favorites 文件。
- `radio fav list`：列出 favorites 目录下全部 `.radio`，并 best-effort 解析补全 `id/name/country`。

### 4.9 `radio sync ...`（懒加载同步）

**`radio sync countries [--force]`**

- 生成/刷新国家索引（TTL=72h；force 可跳过缓存）。
- result：`countries_count`、`force` 等。

**`radio sync stations (--dir <country-dir> | --cc <CC>) [--force]`**

- 先确保 countries 索引存在（用于 `--cc` 映射与目录合法性）。
- 拉取 station 列表并生成 `.radio` + `.stations.index.json`（当前实现会统计 `radios_count`）。

### 4.10 ExitCode 与错误码（Error Codes）

约定（当前实现）：

- `exit_code=0`：成功  
- `exit_code=2`：参数/门禁/输入错误或同步失败（可解释）  
- `exit_code=127`：未知命令（由 `terminal_exec` 提供）  

radio 常见 `errorCode`（节选）：

- `InvalidArgs`
- `NotInRadiosDir`
- `NotRadioFile`
- `NotFound`
- `InvalidRadio`
- `NotPlayingRadio`
- `SyncFailed` / `SyncCountriesFailed` / `CountryNotFound`

### 4.11 与 `radio-cli` 内置技能配合（模糊选台标准流程）

当用户只说“来点国内新闻 radio / 来点越南电台”而没给出精确 `.radio` 路径时，推荐把“发现（Discovery）”与“控制（Control）”分离：

- **Control（主 agent）**：只负责 `terminal_exec radio ...`（同步/收藏/播放/状态），以及可选读写记忆文件 `workspace/radios/.last_played.json`（这是技能约定的“记忆”，不是 App 强制生成的文件）。
- **Discovery（explore 子 agent）**：负责用 `Grep` 在索引里找候选路径（`workspace/radios/.countries.index.json`、`workspace/radios/{dir}/.stations.index.json`），避免全目录扫描与逐个读取 `.radio`。

典型步骤（高度概括，细节以 `app/src/main/assets/builtin_skills/radio-cli/SKILL.md` 为准）：

1) `radio sync countries`（保证国家索引存在）  
2) `radio fav list`（优先复用收藏夹）  
3) 结合记忆文件/用户意图，委派 explore 在索引中定位候选 `.radio` 路径  
4) `radio play --in_b64 ... --await_ms 4000`（带验证窗口）  
5) `radio status` 校验；若坏台（`state=error` 或 verify=error），最多跳过 3 个候选后再反馈用户换关键词/换国家  

---

## 5. 可靠性设计（Playback Reliability）

Radio 生态的系统性问题（大量 broken stream）决定了“可靠性”要分层兜底：

1) **系统性缺口先堵**  
   - Cleartext HTTP：通过 `app/src/main/res/xml/network_security_config.xml` 显式允许（可审计，未来可收紧为白名单）。  
   - HLS：引入 `androidx.media3:media3-exoplayer-hls`，提高 `.m3u8` 可播率。  
2) **解析层兜底（StreamUrlResolver）**  
   - 尝试解析 `.pls/.m3u/.asx/.xspf` 以提取候选 URL，避免“playlist 当音频本体加载”导致的批量失败。  
3) **控制面兜底（radio play --await_ms）**  
   - 通过等待窗口降低“刚发起播放立即 status = idle”的误判；  
   - outcome=error 时自动尝试下一个候选 URL（最多 10 个），把“跳过坏台”变成默认路径。  
4) **状态读口可诊断（radio status + transport snapshot）**  
   - transport 字段让 agent 可以区分：确实 idle / 正在缓冲(play_when_ready=true) / 已出声(is_playing=true)。  

---

## 6. 安全与隐私（Security & Privacy）

安全：

- `terminal_exec` 无 shell、无外部进程；命令必须单行；并落盘审计。  
- `radio play` 受控输入：只允许 radios 子树 `.radio`，避免任意文件被当作媒体播放。  
- `AgentsWorkspace` 层面做路径穿越与逃逸防护（禁止 `..`，禁止逃逸 `.agents`）。  

隐私：

- 收听行为日志默认关闭；Settings 明确同意后才记录。  
- 日志路径透明：`.agents/artifacts/listening_history/events.jsonl`。  
- 清空属于破坏性操作：UI 二次确认后执行删除。  
- 事件/错误信息的落盘与展示尽量避免泄漏完整 URL（尤其是 query/token），播放器侧有 `sanitizeErrorMessage(...)` 的保守过滤。  

---

## 7. 测试与验证建议（Testing Checklist）

建议把验证分为“本地可自动化”与“真机证据”两类：

单测 / Robolectric（优先）：

- `.radio` schema 解析：正常/缺字段/非法 JSON/非法 streamUrl。  
- `radio sync ...`：缓存复用、force 刷新、未知国家目录、`--cc` 映射失败。  
- `radio play --await_ms`：timeout/playing/error 路径的稳定性（可用 resolver/transport hooks 做 fake）。  
- favorites：add/rm/list 的路径门禁与结果字段稳定。

真机冒烟（必做，行为证据）：

- radios/ 任意电台可播放；切页签不断播；锁屏/后台不断播；通知栏可控。  
- 针对 2~3 个“易挂站点”，确认：
  - `radio play --await_ms 4000` 能更快给出 outcome；
  - broken stream 能快速换台而不是空转。

---

## 8. 未来演进方向（Roadmap）

不改变现有边界策略（只在 radios 子树启用能力）的前提下，常见扩展点：

- 站点列表分页/更多排序维度（当前每国 limit=200，按 votes 排序）。  
- 更强的“可播率策略”：缓存“坏台”短 TTL、或记录最近 N 次失败避免重复尝试。  
- 可选的“更隐私友好”的推荐：完全本地、仅在用户同意前提下基于 listening history 计算。  
- CLI 扩展：在保持受控输入前提下，引入“按 stationId 播放”的安全通道（避免暴露路径）。  
