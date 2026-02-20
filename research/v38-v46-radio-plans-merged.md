# Radio Plans v38-v46（Merged）

生成时间（UTC）：`2026-02-20T01:42:06Z`

## 包含文件
- `docs/plan/v38-radio-module-overview.md`
- `docs/plan/v39-radio-recording.md`
- `docs/plan/v40-radio-offline-transcript.md`
- `docs/plan/v41-radio-offline-translation.md`
- `docs/plan/v42-radio-language-learning.md`
- `docs/plan/v43-radio-dual-language.md`
- `docs/plan/v44-asr-tts-modularization.md`
- `docs/plan/v45-radio-live-translation-full.md`
- `docs/plan/v46-radio-live-AudioFocusManager.md`

---

## Source：`docs/plan/v38-radio-module-overview.md`

<!-- merged by tools/merge_md.py -->

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

---

## Source：`docs/plan/v39-radio-recording.md`

<!-- merged by tools/merge_md.py -->

# v39 Plan：Radio 后台录制（RecordingService + ChunkWriter + `radio record`）

## Goal

交付"电台可录制"的最小闭环，并保持 Everything is FileSystem：

- UI：正在播放电台可一键开始/停止录制；后台/锁屏不中断  
- CLI：`terminal_exec radio record ...` 可控、可审计、可测试  
- VFS：产物落盘到 `workspace/radio_recordings/`，按 10min 切片

## PRD Trace

- PRD-0034：REQ-0034-000 / REQ-0034-001 / REQ-0034-002
- PRD-0034：REQ-0034-010 / REQ-0034-011 / REQ-0034-012 / REQ-0034-013 / REQ-0034-014

## Scope

做（v39）：

- `workspace/radio_recordings/` 根目录初始化（含 `_STATUS.md`、`.recordings.index.json`）
- 前台服务录制引擎（并发 ≤2）：
  - Media3 独立 Player 解码 → PCM
  - MediaCodec Opus 编码 + MediaMuxer OGG 封装（`.ogg`，64kbps）
  - 10min chunk 切片写盘 + 元信息落盘
- CLI：`radio record start|stop|status|list`（最小闭环）
- Files：在 `workspace/` 下把 `radio_recordings/` 目录"命名/简介"做轻度装饰（类似 radios）

不做（v39）：

- 不做转录/翻译/TTS（v40+）
- 不做录制任意 URL（只允许 `.radio`）
- 不做 >2 路并发录制（必须硬限制）

## 编码格式决策

录制统一输出 OGG Opus 64kbps（`.ogg`），使用 Android 原生 `MediaCodec`（Opus 编码）+ `MediaMuxer`（OGG 封装），零第三方依赖。

要求 minSdk ≥ 29（Android 10）。目标设备华为 Nova 9（HarmonyOS 2 / Android 11, API 30）已满足。

理由：

- Android `MediaCodec` 原生支持 Opus 编码，`MediaMuxer` 原生支持 OGG 封装（API 29+）
- 不引入任何 JNI/NDK 库
- Opus 是语音优化编码，64kbps 即可达到优秀语音质量，体积约为 AAC 128kbps 的一半
- 完全开源，无专利费
- 下游 ASR 全平台兼容：

| ASR 服务 | OGG/Opus 支持 | 备注 |
|----------|---------------|------|
| 阿里云百炼（Paraformer / SenseVoice） | ✅ | 直接支持 ogg/opus |
| 火山引擎 - 录音文件识别标准版 | ✅ | format=ogg, codec=opus |
| 火山引擎 - 流式语音识别 | ✅ | format=ogg, codec=opus |
| OpenAI Whisper API | ✅ | 直接支持 ogg |

## Acceptance（硬 DoD）

- 并发上限：第三路 `radio record start ...` 必须失败，`error_code=MaxConcurrentRecordings`（或等价稳定码）。  2 个不同电台各 1 路。
- 产物结构：录制开始后必须创建 `{session_id}/_meta.json` 与 `{session_id}/chunk_001.ogg`（允许短延迟）；录制停止后 `state=completed|cancelled|failed` 可解释。  
- 切片策略：单会话 chunk 文件名连续（`chunk_001...`），chunk 时长目标 10min（允许最后一片不足）。  
- 编码验证：产出的 `.ogg` 文件可被 Android MediaPlayer 正常播放，且可直接提交阿里云/火山引擎/OpenAI Whisper ASR 无需转码。
- CLI help：`radio record --help` / `radio help record` 必须 `exit_code=0`。  

验证命令（开发机）：

- `.\gradlew.bat :app:testDebugUnitTest`
- 真机冒烟：`.\gradlew.bat :app:installDebug` 后录制 1–2 分钟，确认后台不中断 + chunk 可播放。

## Files（规划）

- Workspace 初始化：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/AgentsWorkspace.kt`（新增 `workspace/radio_recordings` 初始化）
- 录制模块（建议新包，避免塞进 `radios/`）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_recordings/*`
    - `RadioRecordingService.kt`（Foreground Service）
    - `RecordingSession.kt`（单会话状态机）
    - `ChunkWriter.kt`（PCM → MediaCodec Opus → MediaMuxer OGG，10min 切片）
    - `RecordingMetaV1.kt` / `RecordingsIndexV1.kt`（kotlinx.serialization）
- CLI：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/radio/RadioCommand.kt`
    - 新增 `record` 子命令（必要时先做一次子命令拆分重构）
- Files UI 装饰（最小）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/FilesViewModel.kt`

## Steps（Strict / TDD）

1) Analysis：确定 `radio_recordings/` 的落盘结构、错误码集合（含并发上限/路径门禁/状态机失败），以及 CLI 输出字段（`session_id/state/dir/...`）。  
2) TDD Red：为 `radio record --help` / argv 校验 / 路径门禁 / 并发上限写 `TerminalExecToolTest`（或等价测试入口）。  
3) TDD Green：实现 CLI 框架（只做参数解析 + 结构化输出 + 创建 session 目录与 `_meta.json/_STATUS.md`），先不接真实编码。  
4) TDD Red：为 `ChunkWriter` 写纯 Kotlin 单测（切片命名、写入原子性、更新 `_meta.json` 与 `.recordings.index.json` 的一致性）。  
5) TDD Green：接入实际录制链路（Media3 + MediaCodec Opus + MediaMuxer OGG），并把"录制状态 → 文件落盘"打通。  
6) Refactor：把 `RadioCommand.kt` 里与 v39 无关的逻辑保持不动，但避免 `record` 子命令继续膨胀（必要时引入 `RadioSubcommand` 分发）。  
7) Verify：跑 UT；真机录制冒烟（后台/锁屏/切页签）。  

## Risks

- 音频管线复杂且难以纯单测覆盖：必须把"状态机/落盘/CLI"做成可单测，真机仅做最小冒烟。  
- 并发与资源占用：2 路并发要严格限流并有可解释失败，避免 OOM/编码器占用冲突。  
- Opus 编码器可用性：虽然 API 29+ 规范要求支持 Opus 编码，但部分厂商 ROM 可能存在实现差异。v39 第一个 step 应在目标设备（Nova 9）上做编码可行性验证，若不可用则回退到 AAC/.m4a 方案（AAC 除火山引擎流式 ASR 外均兼容，而流式 ASR 本身喂 PCM raw 不涉及容器格式）。

---

## Source：`docs/plan/v40-radio-offline-transcript.md`

<!-- merged by tools/merge_md.py -->

# v40 Plan：Radio 离线转录（TranscriptTaskManager + CloudAsrClient + `radio transcript`）

## Goal

对 v39 录制产物提供"离线转录"的后台慢任务闭环：

- 为录制会话创建 `transcripts/`，任务/进度以文件形式可见
- 云端 ASR 把每个 `chunk_NNN.ogg` 转为 `chunk_NNN.transcript.json`
- CLI：`radio transcript start|status|list|cancel`

## 录制会话与转录任务的目录关系

v39 录制产物与 v40 转录任务、v41 翻译产物的完整嵌套结构：

```
workspace/radio_recordings/
  rec_20260219_140000_a1b2c3/           # v39 录制会话
    _meta.json                           # 录制元信息（state/stationId/...）
    _STATUS.md
    chunk_001.ogg                        # 10min 音频切片
    chunk_002.ogg
    ...
    transcripts/                         # v40 转录任务根目录
      _tasks.index.json                  # 该 session 下所有转录任务索引
      tx_abc_ja/                         # v40 转录任务（source_lang=ja）
        _task.json                       # 任务状态/进度
        _STATUS.md
        chunk_001.transcript.json        # v40 转录产物
        chunk_001.translation.json       # v41 翻译产物（若指定了 target_lang）
        chunk_002.transcript.json
        chunk_002.translation.json
      tx_abc_ja2zh/                      # v41 多语言对任务（ja→zh）
        _task.json
        _STATUS.md
        chunk_001.transcript.json
        chunk_001.translation.json
      tx_abc_ja2en/                      # v41 多语言对任务（ja→en）
        _task.json
        _STATUS.md
        chunk_001.transcript.json
        chunk_001.translation.json
```

关键规则：

- `transcripts/` 位于录制会话目录内部（`rec_*/transcripts/`）
- 每个转录任务有独立的 `tx_*/` 子目录
- `_tasks.index.json` 位于 `transcripts/` 根，索引该 session 下所有任务
- transcript/translation 文件与源 chunk 同名（只是后缀不同），便于定位对应音频

## PRD Trace

- PRD-0034：REQ-0034-050 / REQ-0034-051 / REQ-0034-052 / REQ-0034-053

## Scope

做（v40）：

- `TranscriptTaskManager`（串行处理 chunk，避免 API 并发爆炸）
- WorkManager 集成：可恢复、可取消
- `_task.json` + `_tasks.index.json` + `_STATUS.md` 落盘与持续更新
- 云端 ASR 抽象层：
  - `CloudAsrClient` 接口（输入 audio file + 语言，输出 segments）
  - `OpenAiWhisperClient` 作为第一个实现
  - 上传 `.ogg` chunk（MIME type: `audio/ogg`）
  - 解析 `verbose_json`（带 timestamps）为 segments
  - 错误码归一：AsrNetworkError / AsrRemoteError / InvalidArgs / …
- CLI：`radio transcript ...`（受控输入：sessionId 或录制目录）

不做（v40）：

- 不做翻译（v41）
- 不做"实时"转录（v44）
- 不做对 `state=recording`（仍在录制中）的 session 发起转录（明确拒绝，错误码 `SessionStillRecording`）

## ASR 提供商抽象

```kotlin
interface CloudAsrClient {
    /** 转录单个音频文件，返回带时间戳的 segments */
    suspend fun transcribe(
        audioFile: File,
        mimeType: String,       // "audio/ogg"
        language: String?,      // null = 自动检测
    ): AsrResult
}

data class AsrResult(
    val segments: List<AsrSegment>,
    val detectedLanguage: String?,
)

data class AsrSegment(
    val id: Int,
    val startSec: Double,
    val endSec: Double,
    val text: String,
)
```

v40 交付 `OpenAiWhisperClient` 实现；后续版本可插入 `AliyunAsrClient`、`VolcEngineAsrClient`，无需改动 TaskManager。

## Acceptance（硬 DoD）

- 前置校验：`radio transcript start --session <sessionId>` 必须校验 `_meta.json` 存在且 `state` 为 `completed`（或 `cancelled`/`failed` 但已有 chunks）；对 `state=recording` 的 session 必须返回 `error_code=SessionStillRecording`。
- 任务可见性：启动任务后必须产生 `{task_id}/_task.json` 和 `{task_id}/_STATUS.md`，`_task.json` 中体现 `totalChunks/transcribedChunks/failedChunks` 进度。
- 单 chunk 原子性：每完成一个 chunk 的转录，必须先写 `chunk_NNN.transcript.json` 再更新 `_task.json`（避免"进度已走但文件缺失"）。
- 可恢复：WorkManager 中断后再次启动时，扫描 `{task_id}/` 目录下已存在的 `chunk_NNN.transcript.json`，跳过已完成的 chunk；`_task.json` 的 `transcribedChunks` 计数基于实际文件存在性重算，不依赖内存状态。
- 取消语义：`radio transcript cancel --task <taskId>` 后，`_task.json` 的 `state=cancelled`，已完成的 chunk transcript 文件保留不删。
- CLI help：`radio transcript --help` / `radio help transcript` 为 0。

验证命令：

- `.\gradlew.bat :app:testDebugUnitTest`

## Files（规划）

- 转录模块（新包）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_transcript/*`
    - `TranscriptTaskManager.kt`（串行队列 + WorkManager 调度）
    - `TranscriptTaskStore.kt`（读写 `_task.json` / `_tasks.index.json` / `_STATUS.md`）
    - `TranscriptChunkV1.kt` / `TranscriptTaskV1.kt`（kotlinx.serialization data class）
- ASR 抽象层（建议独立子包，未来 Chat 语音输入可复用）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/asr/*`
    - `CloudAsrClient.kt`（接口）
    - `AsrResult.kt` / `AsrSegment.kt`
    - `OpenAiWhisperClient.kt`（第一个实现）
- CLI：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/radio/RadioCommand.kt`（新增 `transcript` 子命令）
- Tests：
  - `app/src/test/java/...`（MockWebServer + store 读写测试 + argv 门禁 + 恢复逻辑测试）

## `radio transcript` CLI 命令

```
radio transcript start --session <sessionId> --source_lang ja
radio transcript status --task <taskId>
radio transcript list --session <sessionId>
radio transcript cancel --task <taskId>
```

`radio transcript list` 粒度为 session 级（列出该 session 下所有转录任务），不提供全局列表。

`radio transcript start` 的 result：

```json
{
  "task_id": "tx_20260219_1600_x1y2z3",
  "session_id": "rec_20260219_140000_a1b2c3",
  "state": "pending",
  "source_language": "ja",
  "total_chunks": 12,
  "message": "Transcript task created, 12 chunks queued"
}
```

前置校验失败时：

```json
{
  "error_code": "SessionStillRecording",
  "error_message": "Session rec_... is still recording. Stop recording first."
}
```

## 错误码集合

| error_code | 含义 |
|------------|------|
| `InvalidArgs` | 参数缺失或非法 |
| `SessionNotFound` | sessionId 对应目录或 `_meta.json` 不存在 |
| `SessionStillRecording` | session `state=recording`，拒绝转录 |
| `SessionNoChunks` | session 存在但无 chunk 文件 |
| `TaskNotFound` | taskId 对应目录或 `_task.json` 不存在 |
| `TaskAlreadyExists` | 该 session 已有相同 source_lang 的进行中任务 |
| `AsrNetworkError` | ASR API 网络不可达 |
| `AsrRemoteError` | ASR API 返回非 2xx |
| `AsrParseError` | ASR 返回内容无法解析为 segments |

## Steps（Strict / TDD）

1) Analysis：固化 `_task.json` 最小字段与错误码集合；确认 OpenAI Whisper API 对 `.ogg`（`audio/ogg`）上传的 MIME type 与 `response_format=verbose_json` 的返回结构；明确"串行 + 每 chunk 立即落盘"的一致性规则。
2) TDD Red：CLI help + argv 校验 + sessionId 解析门禁 + `SessionStillRecording` / `SessionNoChunks` 前置校验测试。
3) TDD Red：`TranscriptTaskStore` 单测 — 创建任务（`_task.json` + `_STATUS.md`）、更新进度、生成 `_tasks.index.json`、取消后状态变更。
4) TDD Red：`OpenAiWhisperClient` 在 MockWebServer 下解析 `verbose_json` → segments 的单测（含 NetworkError / RemoteError / AsrParseError 映射）。
5) TDD Red：恢复逻辑单测 — 模拟 `{task_id}/` 下已有部分 `chunk_NNN.transcript.json`，验证 TaskManager 跳过已完成 chunk 并正确重算进度。
6) TDD Green：实现 `TranscriptTaskManager` 串行队列 + WorkManager glue + `CloudAsrClient` 接口 + `OpenAiWhisperClient`，跑到绿。
7) Verify：UT 全绿；手动用一个短录音 chunk 做真机转录验证（可选但推荐）。

## Risks

- Whisper API 费用/网络：测试必须用 MockWebServer；真机仅最小验证。
- JSON 体积：每 chunk transcript 可能较大（10min 语音 ≈ 数百 segments），UI/CLI 默认输出必须摘要化（`transcribedChunks/totalChunks`），完整内容走文件浏览。
- OGG MIME type：OpenAI Whisper API 上传时需确认 `audio/ogg` 被正确识别为 Opus 编码；Analysis 阶段做一次手动 curl 验证。

---

## Source：`docs/plan/v41-radio-offline-translation.md`

<!-- merged by tools/merge_md.py -->

# v41 Plan：Radio 离线翻译（TranslationWorker + `translation.json` + 边录边转）

## Goal

在 v40 转录基础上，交付离线翻译闭环：

- 将 `chunk_NNN.transcript.json` 翻译为 `chunk_NNN.translation.json`（时间戳对齐）
- 支持"录制完成后自动转录+翻译"与"边录边转"
- 一个 task 对应一个语言对（source→target），多语言翻译创建多个 task

## PRD Trace

- PRD-0034：REQ-0034-080 / REQ-0034-081

## Scope

做（v41）：

- `TranslationClient` 接口抽象 + `OpenAiTranslationClient` 第一个实现
- `TranslationWorker`（LLM 翻译，按 segment 批处理，每批 10-20 segments，含上下文窗口）
- translation 落盘 schema 固化（与 transcript segment 对齐：同 id/startSec/endSec）
- 录制 meta 支持 `transcriptRequest`（自动转录/翻译参数）
- "边录边转"模式：
  - 修改 v40 的 `SessionStillRecording` 硬拒绝为"允许但需确认"：CLI 需加 `--streaming` flag
  - `transcriptRequest.autoStart=true` 触发时自动进入 streaming 模式
  - TaskManager 知道 chunk 列表动态增长，持续轮询新 chunk
- CLI：`radio transcript start` 扩展 `--target_lang` 参数（必做，非可选）

不做（v41）：

- 不做语言学习 UI（v42）
- 不做实时翻译管线（v44+）
- 不做术语表/摘要增强（后续版本追加）

## 翻译抽象层

```kotlin
interface TranslationClient {
    /**
     * 批量翻译 segments。
     * @param context 前一批最后 2-3 句，用于提高连贯性（可为空）
     */
    suspend fun translateBatch(
        segments: List<TranscriptSegment>,
        context: List<TranscriptSegment>,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationBatchResult
}

data class TranslationBatchResult(
    val translatedSegments: List<TranslatedSegment>,
)

data class TranslatedSegment(
    val id: Int,
    val startSec: Double,
    val endSec: Double,
    val sourceText: String,
    val translatedText: String,
)
```

v41 交付 `OpenAiTranslationClient` 实现（复用 app 已有的 LLM 调用通道，但走独立队列，不与 Chat 对话抢并发）；后续可插入阿里云通义、火山豆包等实现。

## 批处理策略

- 每批 10-20 个 segments（约 30-60 秒内容）
- 每批 prompt 包含前一批最后 2-3 句作为 context，提高跨批连贯性
- 超过 LLM token 限制时自动拆分为更小的批次
- 单批失败最多重试 3 次，超过则标记该批 segments 为 failed，继续下一批

## 语言对口径

一个 task 对应一个语言对（source→target）。用户想同时生成 ja→zh 和 ja→en 两份翻译，需创建两个 task，各自独立目录、独立进度。VFS 结构清晰：

```
transcripts/
  tx_abc_ja2zh/
    _task.json          # sourceLanguage=ja, targetLanguage=zh
    chunk_001.transcript.json
    chunk_001.translation.json
  tx_abc_ja2en/
    _task.json          # sourceLanguage=ja, targetLanguage=en
    chunk_001.transcript.json
    chunk_001.translation.json
```

注意：同一 session 的多个 task 共享转录结果（`transcript.json` 内容相同），但各自独立落盘一份，避免跨目录引用的复杂性。

## 边录边转模式

v40 原有的 `SessionStillRecording` 硬拒绝逻辑调整为：

- CLI 不带 `--streaming`：对 `state=recording` 的 session 仍然报 `SessionStillRecording`（保持 v40 默认行为安全）
- CLI 带 `--streaming`：允许对 `state=recording` 的 session 创建转录任务，`_task.json` 标记 `mode=streaming`
- `transcriptRequest.autoStart=true`：录制开始时自动创建 `mode=streaming` 任务，每产出一个新 `chunk_NNN.ogg`，TaskManager 自动将其加入队列

streaming 模式下的 `_task.json` 额外字段：

```json
{
  "mode": "streaming",
  "totalChunks": null,
  "knownChunks": 5,
  "transcribedChunks": 3,
  "translatedChunks": 2,
  "waitingForMoreChunks": true
}
```

`totalChunks=null` 表示总量未知（录制仍在进行）；录制完成后 TaskManager 收到通知，将 `totalChunks` 设为最终值，`waitingForMoreChunks=false`。

## Acceptance（硬 DoD）

- 对齐：`translation.json` 中每个 segment 必须带 `sourceText/translatedText`，且 `id/startSec/endSec` 与来源 transcript 严格一致。
- 进度可解释：`_task.json` 中 `translatedChunks` 必须单调递增，失败 chunk 必须计入 `failedChunks` 并可继续后续 chunk。
- 自动触发：录制会话 `_meta.json.transcriptRequest.autoStart=true` 时，录制完成（或录制开始，若含 streaming 配置）会自动创建任务并进入队列。
- 边录边转：`--streaming` 模式下，新 chunk 产出后 ≤30 秒内被 TaskManager 感知并加入队列。
- CLI 完整性：`radio transcript start --session <sid> --source_lang ja --target_lang zh` 必须可用；不带 `--target_lang` 则只转录不翻译。
- 多语言对：同一 session 可创建多个不同 target_lang 的 task，互不干扰。
- CLI help：`radio transcript --help` 为 0。

验证命令：

- `.\gradlew.bat :app:testDebugUnitTest`

## 错误码集合

| error_code | 含义 |
|------------|------|
| `InvalidArgs` | 参数缺失或非法（如 source_lang 与 target_lang 相同） |
| `SessionNotFound` | sessionId 对应目录或 `_meta.json` 不存在 |
| `SessionStillRecording` | session `state=recording` 且未指定 `--streaming` |
| `SessionNoChunks` | session 存在但无 chunk 文件 |
| `TaskNotFound` | taskId 对应目录或 `_task.json` 不存在 |
| `TaskAlreadyExists` | 该 session 已有相同 source_lang + target_lang 的进行中任务 |
| `TranscriptNotReady` | 该 chunk 的 transcript.json 尚未生成（翻译依赖转录） |
| `TranslationAlreadyExists` | 该 chunk 的 translation.json 已存在（跳过） |
| `LlmNetworkError` | LLM API 网络不可达 |
| `LlmRemoteError` | LLM API 返回非 2xx |
| `LlmParseError` | LLM 返回内容无法解析为翻译结果 |
| `LlmQuotaExceeded` | LLM API 配额耗尽 |

## Files（规划）

- 翻译抽象层（建议独立子包，未来其他模块可复用）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/translation/*`
    - `TranslationClient.kt`（接口）
    - `TranslationBatchResult.kt` / `TranslatedSegment.kt`
    - `OpenAiTranslationClient.kt`（第一个实现）
- 翻译 Worker（放在 v40 同包）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_transcript/TranslationWorker.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_transcript/TranslationChunkV1.kt`
- 录制 meta 扩展：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_recordings/RecordingMetaV1.kt`（新增 `transcriptRequest` 字段）
- v40 前置校验修改：
  - `TranscriptTaskManager.kt`：`SessionStillRecording` 从硬拒绝改为"无 `--streaming` 时拒绝，有则允许"
- CLI：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/radio/RadioCommand.kt`
    - `radio transcript start` 扩展 `--target_lang` 和 `--streaming` 参数
- Tests：
  - MockWebServer + translation schema 对齐测试 + 批处理拆分测试 + streaming 模式测试

## Steps（Strict / TDD）

1) Analysis：确定翻译 prompt 的最小稳定口径（输入：segments[] + context[] + language pair；输出：逐条翻译 JSON）；确定批处理大小（10-20 segments）与 context 窗口（前一批末尾 2-3 句）；确定多语言对的目录策略（独立落盘 vs 引用共享）。
2) TDD Red：`TranslationClient` 接口 + `OpenAiTranslationClient` mock 测试 — 正常翻译、超时、限流、返回缺字段的鲁棒性。
3) TDD Red：translation schema 对齐单测 — 输入 transcript segments，产出 translation segments，断言 id/startSec/endSec 严格一致。
4) TDD Red：批处理拆分单测 — 验证 >20 segments 自动拆批、context 窗口正确传递。
5) TDD Green：实现 `TranslationWorker` + 与 `TranscriptTaskManager` 的串行调度；每 chunk 翻译完成即落盘 `translation.json` 再更新 `_task.json`。
6) TDD Red：自动触发测试 — `transcriptRequest.autoStart=true` + `targetLanguage` 存在时，录制完成回调创建含翻译的任务。
7) TDD Red：边录边转测试 — `--streaming` 模式下，模拟新 chunk 产出，验证 TaskManager 感知并处理。
8) TDD Green：修改 v40 的 `SessionStillRecording` 逻辑，实现 streaming 模式。
9) Verify：UT 全绿；用 1 个短 chunk 做端到端（转录+翻译）手动验证（可选）。

## Risks

- 翻译一致性（术语、人名）：v41 先保证"可用"，术语表/摘要属于增强（后续版本追加）。
- LLM 成本：10min chunk ≈ 数百 segments，每批 10-20 个，单个 chunk 可能需要 10-30 次 LLM 调用。测试必须用 mock。
- 边录边转的 chunk 感知延迟：依赖文件系统轮询或 `RecordingService` 的回调通知，需在 Analysis 阶段确定机制。回答：用回调通知而非轮询。RecordingService 每完成一个 chunk 的 rename（chunk_NNN.ogg.tmp → chunk_NNN.ogg）时发一个事件（SharedFlow 或 BroadcastChannel），TranscriptTaskManager 订阅即可。轮询有延迟且浪费资源。
- 多 task 共享 transcript 的目录策略：独立落盘

---

## Source：`docs/plan/v42-radio-language-learning.md`

<!-- merged by tools/merge_md.py -->

# v42 Plan：文件类型感知渲染 + 双语字幕视图 + 播放定位高亮

## Goal

把"转录/翻译文件"从原始 JSON 变成可阅读、可交互的字幕体验：

- Files 模块引入可插拔的文件类型感知渲染器机制
- `*.translation.json` 渲染为双语字幕视图
- 点击时间戳定位播放对应 `chunk_NNN.ogg` + 高亮当前句

## PRD Trace

- PRD-0034：REQ-0034-100 / REQ-0034-101 / REQ-0034-102

## Scope

做（v42）：

- `VfsFileRenderer` 可插拔渲染器架构（Files 模块级基础设施）
- 首批渲染器：
  - `*.translation.json` → 双语字幕视图（原文/译文/双语三种显示模式切换）
  - `*.transcript.json` → 原文字幕视图（双语字幕视图的简化版，复用组件）
  - `_task.json`（transcript/translation task） → 任务进度卡片
- 播放定位：从字幕视图点击时间戳 → 播放对应 `chunk_NNN.ogg` 的对应位置
- 高亮联动：播放进行时，当前句自动高亮，字幕自动滚动跟随

不做（v42）：

- 不做 language-tutor Agent 面板（v43）
- 不做 TTS 双语听力生成（v43）
- 不做实时字幕（v45+）

## 文件类型感知渲染架构

```kotlin
interface VfsFileRenderer {
    /** 是否能渲染此文件（基于文件名、父路径、或文件内容 schema） */
    fun canRender(fileName: String, parentPath: String): Boolean
    /** 渲染优先级（多个 renderer 匹配时取最高） */
    val priority: Int get() = 0
    /** 返回渲染用的 Composable */
    @Composable
    fun Render(file: VfsFile, modifier: Modifier)
}
```

渲染器注册表（`VfsRendererRegistry`）：

```kotlin
class VfsRendererRegistry {
    private val renderers = mutableListOf<VfsFileRenderer>()
    fun register(renderer: VfsFileRenderer)
    /** 找到最匹配的渲染器，无则返回 null（回退到默认 JSON/文本查看） */
    fun findRenderer(fileName: String, parentPath: String): VfsFileRenderer?
}
```

v42 注册的渲染器：

| 文件模式 | 渲染器 | 匹配规则 |
|----------|--------|----------|
| `*.translation.json` | `BilingualSubtitleRenderer` | 文件名以 `.translation.json` 结尾 |
| `*.transcript.json` | `TranscriptSubtitleRenderer` | 文件名以 `.transcript.json` 结尾 |
| `_task.json`（在 transcripts/ 下） | `TranscriptTaskCardRenderer` | 文件名为 `_task.json` 且父路径含 `transcripts/` |

未来其他模块可按同样模式注册自己的渲染器，不侵入 Files 核心代码。

## 双语字幕视图设计

```
┌─────────────────────────────────────────────┐
│  NHK World · chunk_001 · 00:00 - 10:00      │
│  [原文] [译文] [双语]               advancement│
│─────────────────────────────────────────────│
│  ▶ 00:00  こんにちは、NHKワールドニュースです     │
│           你好，这里是NHK世界新闻               │
│                                             │
│  ● 00:03  今日のトップニュースをお伝えします       │  ← 当前播放高亮
│           为您播报今天的头条新闻                 │
│                                             │
│    00:07  まず、経済ニュースです                 │
│           首先是经济新闻                        │
│  ...                                        │
└─────────────────────────────────────────────┘
```

交互行为：

- 点击时间戳 → 定位播放（详见下方"播放定位逻辑"）
- 顶部 tab 切换显示模式：原文 / 译文 / 双语对照
- 播放中自动滚动 + 当前句高亮
- `TranscriptSubtitleRenderer` 复用同一组件，只是不显示译文行

## 播放定位逻辑

点击字幕视图中某个 segment 的时间戳时，根据当前播放状态分三种情况：

| 当前状态 | 行为 |
|----------|------|
| 没在播放任何内容 | 启动播放该 `chunk_NNN.ogg`，seek 到 `startSec` |
| 正在播放同一个 chunk | 直接 seek 到 `startSec` |
| 正在播放其他内容 | 切换到该 `chunk_NNN.ogg`，seek 到 `startSec` |

chunk 文件定位规则：字幕视图知道自己渲染的是哪个 `chunk_NNN.translation.json`，对应的音频文件在同级目录的上一层（`../{sourceChunk}`，即 `chunk_NNN.ogg`）。`sourceChunk` 字段已在 v40/v41 的 schema 中定义。

高亮联动：

- ViewModel 持有 `currentPlaybackPositionSec: StateFlow<Double>`
- 字幕列表根据 `currentPlaybackPositionSec` 计算当前 segment index
- LazyColumn 自动 `animateScrollToItem` 到当前 segment

## Acceptance（硬 DoD）

- 渲染器架构：`VfsRendererRegistry` 可注册/查找渲染器；未匹配的文件回退到默认查看器。
- 双语字幕：点击 `chunk_001.translation.json` 必须进入双语字幕视图，支持原文/译文/双语三种模式切换。
- 原文字幕：点击 `chunk_001.transcript.json` 必须进入原文字幕视图。
- 任务卡片：点击 transcripts 目录下的 `_task.json` 必须渲染为进度卡片（而非原始 JSON）。
- 定位播放：点击任意时间戳必须触发播放定位到对应 chunk 的对应时间点，三种播放状态场景均正确处理。
- 高亮联动：播放进行时，当前句高亮随播放位置移动（允许 ±0.5 秒近似）。
- CLI help：无新增 CLI（v42 纯 UI 版本）。

验证命令：

- `.\gradlew.bat :app:testDebugUnitTest`
- 真机：安装后打开 Files → 录制会话 → transcripts → translation 文件 → 字幕视图可用、定位播放可用

## Files（规划）

- 渲染器架构（Files 模块基础设施）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/renderer/VfsFileRenderer.kt`（接口）
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/renderer/VfsRendererRegistry.kt`
- 字幕渲染器：
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/renderer/BilingualSubtitleRenderer.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/renderer/TranscriptSubtitleRenderer.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/renderer/TranscriptTaskCardRenderer.kt`
- 字幕 UI 组件（Compose）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/subtitle/SubtitleScreen.kt`（字幕视图主屏）
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/subtitle/SubtitleViewModel.kt`（播放定位 + 高亮状态）
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/subtitle/SegmentRow.kt`（单行 segment 组件）
- Files 集成：
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/FilesViewModel.kt`（接入 RendererRegistry）

## Steps（Strict / TDD）

1) Analysis：确定 `VfsFileRenderer` 接口最小契约；确定字幕视图需要的数据结构（segments + 时间戳 + 当前播放位置）；确定从文件路径定位音频 chunk 的规则（`sourceChunk` 字段 → 上级目录）。
2) TDD Red：`VfsRendererRegistry` 单测 — 注册/查找/优先级/未匹配回退。
3) TDD Green：实现 `VfsRendererRegistry` + 三个渲染器的 `canRender` 逻辑。
4) TDD Red：`SubtitleViewModel` 单测 — 加载 translation.json → segments 列表；显示模式切换（原文/译文/双语）。
5) TDD Red：播放定位单测 — 三种播放状态场景（未播放/同 chunk/不同内容），用 fake player 验证 seek 调用。
6) TDD Red：高亮联动单测 — 给定 `currentPlaybackPositionSec`，验证计算出的 `currentSegmentIndex` 正确。
7) TDD Green：实现 `SubtitleScreen` + `SubtitleViewModel` + `SegmentRow`，接入真实播放器。
8) TDD Green：实现 `TranscriptTaskCardRenderer`（进度卡片）。
9) Verify：UT 全绿；真机字幕交互冒烟（打开 translation.json → 字幕视图 → 点击定位 → 高亮跟随）。

## Risks

- UI 工作量：双语字幕视图 + 播放联动是 v42 的主要工作量，建议先做最小可用版本（纯文本列表 + seek），动画/滚动优化后续迭代。
- 渲染器架构的侵入性：`VfsRendererRegistry` 需要在 Files 的文件点击流程中插入一个分发点，需确认不破坏现有文件查看行为。
- 播放器状态管理：字幕视图需要和播放器双向通信（seek + position 监听），需确认现有 Radio 播放器架构支持这种交互。

---

## Source：`docs/plan/v43-radio-dual-language.md`

<!-- merged by tools/merge_md.py -->

# v43 Plan：language-tutor Agent + TTS 双语听力生成

## Goal

在 v42 字幕视图基础上，交付语言学习的两个核心能力：

- 长按字幕句子唤起 language-tutor Agent，获得语法解析、词汇扩展、例句等
- 生成双语听力 TTS 音频（交替模式 / 仅译文模式）

## PRD Trace

- PRD-0034：REQ-0034-103 / REQ-0034-104

## Scope

做（v43）：

- 内置 language-tutor SKILL：
  - `app/src/main/assets/builtin_skills/language-tutor/SKILL.md`
  - 能力：语法解析、词汇扩展、例句生成、发音要点、文化背景
- 字幕视图长按交互 → 跳转 Chat 页签 + 自动切换 skill + 注入上下文（方案 B，复用现有 Chat 基础设施）
- TTS 抽象层：
  - `TtsClient` 接口（输入文本 + 语言 + voice，输出音频文件）
  - 第一个实现（OpenAI TTS 或 Android 系统 TTS，Analysis 阶段确定）
- TTS 双语听力任务系统：
  - 两种模式：仅译文（`target_only`）/ 交替（`interleaved`：原文朗读 → 1s 停顿 → 译文朗读）
  - 落盘到 `audio_bilingual/` 目录
- CLI：`radio tts start|status|cancel`

不做（v43）：

- 不做 mini chat 内嵌面板（方案 A，后续优化）
- 不做复杂学习系统（生词本/复习计划等）
- 不做实时翻译管线（v45+）

## language-tutor Agent

### SKILL.md 能力范围

- 语法解析：拆解句子结构，标注词性、时态、语法点
- 词汇扩展：生词释义、同义词、常用搭配
- 例句生成：围绕该语法点或词汇，生成 2-3 个难度递进的例句
- 发音要点：针对日语的音调、英语的连读弱读等
- 文化背景：必要时补充语境（如新闻用语的正式程度）

### 上下文注入

长按字幕中某个 segment 后，跳转 Chat 页签，自动：

1. 切换到 `language-tutor` skill
2. 注入结构化上下文作为首条消息：

```json
{
  "selectedSegment": {
    "sourceText": "今日のトップニュースをお伝えします",
    "translatedText": "为您播报今天的头条新闻",
    "sourceLanguage": "ja",
    "targetLanguage": "zh",
    "startSec": 3.2,
    "endSec": 7.8
  },
  "surroundingSegments": [
    {
      "sourceText": "こんにちは、NHKワールドニュースです",
      "translatedText": "你好，这里是NHK世界新闻"
    },
    {
      "sourceText": "まず、経済ニュースです",
      "translatedText": "首先是经济新闻"
    }
  ],
  "stationName": "NHK World",
  "userLevel": "intermediate"
}
```

3. Agent 自动生成第一条分析回复
4. 用户可继续追问（如"这个语法还有什么用法？"）

### 交互流程

```
字幕视图 → 长按 segment → 跳转 Chat 页签
  → 自动切换 language-tutor skill
  → 注入上下文 → Agent 自动回复分析
  → 用户可继续对话
  → 返回字幕视图（Chat 历史保留）
```

## TTS 抽象层

```kotlin
interface TtsClient {
    /**
     * 将文本合成为音频文件。
     * @return 生成的音频文件路径
     */
    suspend fun synthesize(
        text: String,
        language: String,
        voice: String,
        outputFile: File,
        outputFormat: String,   // "ogg" / "mp3"
    ): TtsResult
}

data class TtsResult(
    val outputFile: File,
    val durationSec: Double,
)
```

v43 交付第一个实现（Analysis 阶段确定选型：OpenAI TTS API 或 Android 系统 TTS）。后续可插入阿里云、火山引擎等。

## TTS 双语听力落盘结构

```
{task_id}/
  audio_bilingual/
    _task.json
    _STATUS.md
    chunk_001_bilingual.ogg
    chunk_002_bilingual.ogg
    ...
```

### _task.json Schema

```json
{
  "schema": "kotlin-agent-app/tts-bilingual-task@v1",
  "taskId": "tts_20260219_1700_m1n2o3",
  "parentTaskId": "tx_20260219_1600_x1y2z3",
  "mode": "interleaved",
  "sourceLanguage": "ja",
  "targetLanguage": "zh",
  "sourceTtsVoice": "ja-JP-Neural",
  "targetTtsVoice": "zh-CN-Neural",
  "outputFormat": "ogg",
  "state": "pending",
  "progress": {
    "totalChunks": 12,
    "completedChunks": 4,
    "failedChunks": 0
  },
  "createdAt": "2026-02-19T17:00:00+08:00",
  "updatedAt": "2026-02-19T17:25:00+08:00",
  "error": null
}
```

`state` 取值：`pending | in_progress | completed | failed | cancelled`

### 双语听力音频生成逻辑

交替模式（`interleaved`）：

```
对每个 segment:
  1. TTS 合成原文（sourceLanguage + sourceTtsVoice）→ 临时文件
  2. 插入 1 秒静音
  3. TTS 合成译文（targetLanguage + targetTtsVoice）→ 临时文件
  4. 插入 0.5 秒静音（segment 间隔）
拼接所有片段 → chunk_NNN_bilingual.ogg
```

仅译文模式（`target_only`）：

```
对每个 segment:
  1. TTS 合成译文（targetLanguage + targetTtsVoice）→ 临时文件
  2. 插入 0.5 秒静音（segment 间隔）
拼接所有片段 → chunk_NNN_bilingual.ogg
```

## CLI

```
radio tts start --task  --mode interleaved|target_only 

[--source_voice ja-JP-Neural] [--target_voice zh-CN-Neural]
radio tts status --task 
radio tts cancel --task 
```

`radio tts start` 的 result：

```json
{
  "task_id": "tts_20260219_1700_m1n2o3",
  "parent_task_id": "tx_20260219_1600_x1y2z3",
  "mode": "interleaved",
  "state": "pending",
  "total_chunks": 12,
  "message": "TTS task created, 12 chunks queued"
}
```

## 错误码集合

| error_code | 含义 |
|------------|------|
| `InvalidArgs` | 参数缺失或非法（如 mode 不合法） |
| `TranscriptTaskNotFound` | parentTaskId 对应的转录任务不存在 |
| `TranslationNotReady` | 转录任务尚未完成翻译（无 translation.json） |
| `TtsTaskAlreadyExists` | 该转录任务已有相同 mode + 语言对的 TTS 任务进行中 |
| `TtsNetworkError` | TTS API 网络不可达 |
| `TtsRemoteError` | TTS API 返回非 2xx |
| `TtsQuotaExceeded` | TTS API 配额耗尽 |
| `AudioConcatError` | 音频片段拼接失败 |

## Acceptance（硬 DoD）

- Agent 唤起：长按字幕视图中任意 segment，必须跳转 Chat 页签并自动切换到 language-tutor skill，上下文（选中句 + 周边句 + 语言对）正确注入。
- Agent 回复：注入上下文后 Agent 必须自动生成第一条分析回复（语法/词汇/例句至少覆盖其一）。
- 返回保留：从 Chat 返回字幕视图后，Chat 历史保留，可再次进入继续对话。
- TTS 落盘：`radio tts start` 必须创建 `audio_bilingual/_task.json` + `_STATUS.md`。
- TTS 产物：逐 chunk 产出 `chunk_NNN_bilingual.ogg`，可被 Android MediaPlayer 正常播放。
- TTS 模式：`interleaved` 模式产出的音频必须包含原文朗读 + 停顿 + 译文朗读；`target_only` 模式只包含译文朗读。
- CLI help：`radio tts --help` 为 0。

验证命令：

- `.\gradlew.bat :app:testDebugUnitTest`
- 真机：字幕视图长按 → Agent 面板可用；`radio tts start` → 产出可播放的双语音频

## Files（规划）

- 内置 skill：
  - `app/src/main/assets/builtin_skills/language-tutor/SKILL.md`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/AgentsWorkspace.kt`（注册 skill）
- 字幕视图长按交互：
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/subtitle/SubtitleScreen.kt`（新增长按手势 + 跳转逻辑）
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/subtitle/SubtitleViewModel.kt`（组装上下文）
- Chat 集成：
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/chat/ChatViewModel.kt`（支持外部注入 skill + 上下文）
- TTS 抽象层（建议独立子包）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/tts/*`
    - `TtsClient.kt`（接口）
    - `TtsResult.kt`
    - `OpenAiTtsClient.kt` 或 `SystemTtsClient.kt`（第一个实现）
- TTS 双语听力任务：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_tts/*`
    - `BilingualTtsTaskManager.kt`
    - `BilingualTtsWorker.kt`（调用 TtsClient + 音频拼接）
    - `BilingualTtsTaskV1.kt`（kotlinx.serialization）
- CLI：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/radio/RadioCommand.kt`（新增 `tts` 子命令）
- Tests：
  - language-tutor 上下文组装测试
  - TTS mock 测试（synthesize + 拼接逻辑）
  - CLI argv 门禁测试

## Steps（Strict / TDD）

1) Analysis：确定 language-tutor SKILL.md 的 prompt 结构；确定 TTS 选型（OpenAI TTS vs Android 系统 TTS）；确定音频拼接方案（MediaCodec + MediaMuxer 拼接 OGG，或 PCM 拼接后统一编码）。
2) TDD Red：language-tutor 上下文组装测试 — 选中句 + 周边句（前后各 2 句）+ 语言对 + stationName 正确组装。
3) TDD Green：实现 SKILL.md + AgentsWorkspace 注册 + SubtitleViewModel 上下文组装。
4) TDD Red：Chat 外部注入测试 — 验证从外部传入 skillId + 上下文后，ChatViewModel 正确切换 skill 并发送首条消息。
5) TDD Green：实现字幕视图长按 → Chat 跳转 → skill 切换 → 上下文注入 → 自动回复。
6) TDD Red：`TtsClient` mock 测试 — synthesize 正常/超时/失败。
7) TDD Red：`BilingualTtsWorker` 单测 — interleaved 模式拼接逻辑（原文 + 静音 + 译文 + 间隔）；target_only 模式拼接逻辑。
8) TDD Green：实现 `BilingualTtsTaskManager` + `BilingualTtsWorker` + CLI `radio tts`。
9) Verify：UT 全绿；真机冒烟（长按 → Agent 回复；TTS 产出可播放音频）。

## Risks

- TTS 选型：OpenAI TTS 质量好但有网络/费用依赖；Android 系统 TTS 免费离线但语音质量参差不齐（尤其日语）。建议 Analysis 阶段在 Nova 9 上实测系统 TTS 的日语/中文质量，再做决策。
- 音频拼接复杂度：多段 TTS 音频 + 静音段拼接为单个 OGG 文件，需要处理采样率/声道数一致性。如果 TTS 输出格式不统一，可能需要先统一解码为 PCM 再重新编码。回答：所有 TTS 输出统一为 48kHz mono OGG Opus（与 v39 录制格式一致），拼接时不需要重采样。如果 TTS provider 输出格式不同，在 TtsClient 实现层做转码，不要把这个复杂度泄漏到 BilingualTtsWorker。
- Chat 外部注入的侵入性：ChatViewModel 需要支持"从外部传入 skill + 上下文并自动发送"，需确认不破坏现有 Chat 交互流程。
- language-tutor 回复质量：依赖 LLM 的语言学知识，日语语法分析的准确性需要人工抽检。v43 先保证"可用"，质量调优后续迭代。

---

## Source：`docs/plan/v44-asr-tts-modularization.md`

<!-- merged by tools/merge_md.py -->

# v44 Plan：ASR/TTS Service 编排层 + Chat 语音输入 + 通道隔离

## Goal

在 v40（CloudAsrClient）和 v43（TtsClient）已有的 provider 接口之上，新增 service 编排层，统一管理通道隔离、Settings 配置：

- `AsrService`：在 `CloudAsrClient` 之上加通道隔离 + 文件转录 / 流式转录
- `TtsService`：在 `TtsClient` 之上加通道隔离
- Chat 语音输入最小闭环（麦克风 → ASR → 文字填入输入框）
- 通道隔离：Chat 独占通道，永远不被 Radio background 阻塞

## 架构分层

```
AsrService（v44 新增：通道隔离 + Settings）
  └── CloudAsrClient（v40 已有：provider 接口）
        ├── OpenAiWhisperClient（v40 已有）
        ├── AliyunAsrClient（未来）
        └── VolcEngineAsrClient（未来）

TtsService（v44 新增：通道隔离 + Settings）
  └── TtsClient（v43 已有：provider 接口）
        ├── OpenAiTtsClient（v43 已有）
        └── AndroidSystemTtsClient（未来）
```

v44 不修改 v40/v43 已有的 provider 接口，只在其上层做编排。v40 的 `TranscriptTaskManager` 和 v43 的 `TtsWorker` 改为通过 `AsrService` / `TtsService` 调用，而非直接持有 provider 实例。

## PRD Trace

- PRD-0034：REQ-0034-130 / REQ-0034-131 / REQ-0034-132

## Scope

做（v44）：

- `AsrService`：
  - `transcribeFile(file, channel)` — 文件级转录（复用 v40 的 `CloudAsrClient.transcribe()`）
  - `transcribeStream(pcmFlow, sampleRate, channel)` — 流式转录（麦克风 PCM → 实时文字）
- `TtsService`：
  - `synthesize(text, language, voice, channel)` — 复用 v43 的 `TtsClient.synthesize()`
- 通道隔离（3 条独立通道，互不阻塞）
- Settings 配置（provider 选择 + voice 配置 + 启用开关）
- Chat 语音输入最小闭环（仅语音输入，不做语音输出）
- v40 `TranscriptTaskManager` + v43 `TtsWorker` 重构为通过 Service 层调用

不做（v44）：

- 不做 Chat 语音输出 / Agent 回复朗读（v45）
- 不做完整实时翻译 UI（v45+）
- 不做"全语音聊天产品化"

## 通道隔离策略

核心原则：**Chat 通道永远不被 Radio background 阻塞。三条通道完全独立，各自串行，互不干扰。**

```
┌─────────────────────────────────────────────────────┐
│                    App 全局                          │
│                                                     │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────┐ │
│  │ Chat 通道    │  │ Radio 通道 A  │  │ Radio 通道 B│ │
│  │ (独占)       │  │ (background) │  │ (background)│ │
│  │             │  │              │  │             │ │
│  │ 语音输入 ASR │  │ 电台1 转录    │  │ 电台2 转录   │ │
│  │ Agent TTS   │  │ 电台1 TTS    │  │ 电台2 TTS   │ │
│  │             │  │              │  │             │ │
│  │ 并发：1      │  │ 并发：1       │  │ 并发：1      │ │
│  │ 永远可用     │  │ 可排队        │  │ 可排队       │ │
│  └─────────────┘  └──────────────┘  └────────────┘ │
│                                                     │
│  三条通道完全独立，各自内部串行，互不阻塞              │
└─────────────────────────────────────────────────────┘
```

设计要点：

- **Chat 通道**：独占 1 条通道，并发 1。用户发起语音输入时立即可用，无需等待任何 background 任务。Chat 通道内部串行（同一时刻只有一个 ASR 或 TTS 请求在执行）。
- **Radio 通道 A / B**：各独占 1 条通道，并发各 1。对应最多 2 个电台的后台任务（转录/翻译 TTS）。通道内部串行（同一电台的 chunk 按顺序处理）。第 3 个电台的请求被拒绝，返回 `NoChannelAvailable`。
- **通道之间零依赖**：Chat 通道不关心 Radio 通道的状态，Radio 通道之间也互不关心。没有共享信号量、没有共享队列、没有"等待其他通道释放"的逻辑。

```kotlin
/**
 * 三条完全独立的请求通道。
 * 每条通道内部串行（Mutex），通道之间零依赖。
 */
class ChannelDispatcher {

    enum class Channel {
        CHAT,           // 独占，永远可用
        RADIO_A,        // background 电台 1
        RADIO_B,        // background 电台 2
    }

    /**
     * 在指定通道内获取执行许可。
     * - CHAT：立即获取（通道内串行，但不等待 Radio）
     * - RADIO_A / RADIO_B：通道内串行排队
     *
     * @throws NoChannelAvailableException 当 RADIO_A 和 RADIO_B 都被不同 session 占用时，
     *         第三个 session 的请求直接拒绝（不排队）
     */
    suspend fun acquire(channel: Channel): ChannelPermit

    fun release(permit: ChannelPermit)

    /**
     * 为一个 radio session 分配通道。
     * 如果该 session 已有通道则复用，否则分配空闲通道。
     * 两个通道都被占用时返回 null。
     */
    fun assignRadioChannel(sessionId: String): Channel?

    /** 释放 radio session 对通道的占用 */
    fun releaseRadioChannel(sessionId: String)
}

data class ChannelPermit(
    val channel: ChannelDispatcher.Channel,
    val permitId: String,
)
```

### Radio 通道分配规则

Radio 通道的分配以"session"为粒度（一个录制会话 / 一个转录任务 / 一个 live 会话 = 一个 session）：

- 第一个 radio session 启动时，分配 `RADIO_A`
- 第二个 radio session 启动时，分配 `RADIO_B`
- 第三个 radio session 启动时，`assignRadioChannel()` 返回 null → 调用方返回 `NoChannelAvailable` 错误
- session 结束后释放通道，供后续 session 使用
- 同一个 session 的所有请求（多个 chunk 的转录、翻译 TTS）走同一条通道，通道内串行保证顺序

### 与 v39 录制并发上限的关系

v39 录制并发上限 ≤2（`MaxConcurrentRecordings`）。v44 的 Radio 通道数也是 2。这不是巧合——每个录制会话绑定一条 Radio 通道，录制 + 转录 + TTS 在同一通道内串行处理，天然保证不超载。

## 流式转录接口

```kotlin
/**
 * AsrService 的流式转录方法。
 * 输入：麦克风 PCM 数据流
 * 输出：逐步产出的文字片段
 */
suspend fun transcribeStream(
    pcmFlow: Flow<ByteArray>,
    sampleRate: Int,            // 16000
    channelCount: Int,          // 1 (mono)
    language: String?,          // null = 自动检测
    channel: Channel,           // CHAT / RADIO_A / RADIO_B
): Flow<StreamTranscriptEvent>

sealed class StreamTranscriptEvent {
    /** 中间结果（可能被后续结果覆盖） */
    data class Partial(val text: String) : StreamTranscriptEvent()
    /** 最终确认结果 */
    data class Final(val text: String) : StreamTranscriptEvent()
    /** 错误 */
    data class Error(val errorCode: String, val message: String) : StreamTranscriptEvent()
}
```

底层实现策略（Analysis 阶段确定）：

- 方案 A：真流式 — 对接火山引擎流式 ASR WebSocket（`format=ogg, codec=opus`），延迟最低
- 方案 B：伪流式 — 每 5 秒攒一段 PCM，调用 Whisper API 文件转录，拼接结果
- v44 先实现方案 B（伪流式，固定 5 秒窗口），方案 A 作为后续优化
- 5 秒窗口与 v45 AudioSplitter 的切段粒度对齐，避免集成时调整

## Chat 语音输入交互

```
Chat 输入框 → 点击麦克风按钮 → 进入录音状态
  → 实时显示 partial 文字（灰色，在输入框上方浮层）
  → 松开 / 点击停止 → final 文字填入输入框
  → 用户可编辑后发送
```

最小实现：

- 输入框右侧新增麦克风图标按钮
- 按下后开始录音（`AudioRecord` → PCM Flow）
- PCM Flow 喂给 `AsrService.transcribeStream(channel=CHAT)`
- partial 结果实时显示，final 结果填入输入框
- 录音时长上限 60 秒（超时自动停止）
- ASR 未启用时（`asr.enabled=false`），麦克风按钮灰显，点击弹 toast 提示去 Settings 开启
- Chat 通道独占，语音输入期间不受任何 Radio 后台任务影响

## Settings 配置项

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `asr.enabled` | bool | `false` | 是否启用云端 ASR（首次开启时弹隐私提示） |
| `asr.provider` | enum | `openai_whisper` | ASR 提供商：`openai_whisper` / `aliyun` / `volcengine` |
| `tts.enabled` | bool | `false` | 是否启用云端 TTS（首次开启时弹隐私提示） |
| `tts.provider` | enum | `openai_tts` | TTS 提供商：`openai_tts` / `android_system` |
| `tts.voice.ja` | string | `alloy` | 日语默认 voice |
| `tts.voice.zh` | string | `alloy` | 中文默认 voice |
| `tts.voice.en` | string | `alloy` | 英语默认 voice |

provider 切换后，对应的 API key 配置项自动出现（如 `openai.apiKey` 已在现有 Settings 中）。

provider 切换的生效时机：已提交到通道内排队的请求用旧 provider 跑完，新请求用新 provider。不做热切换。

## 错误码集合

| error_code | 含义 |
|------------|------|
| `AsrDisabled` | ASR 未启用（Settings 中关闭） |
| `TtsDisabled` | TTS 未启用（Settings 中关闭） |
| `ProviderNotConfigured` | 选择的 provider 缺少 API key 或必要配置 |
| `NoChannelAvailable` | 2 条 Radio 通道都被占用，第 3 个 session 被拒绝 |
| `RecordingPermissionDenied` | 麦克风权限未授予 |
| `RecordingTimeout` | 录音超过 60 秒上限，自动停止 |
| `StreamConnectionFailed` | 流式 ASR 连接失败 |
| `StreamInterrupted` | 流式 ASR 连接中断 |
| `AsrNetworkError` | 网络不可达 |
| `AsrRemoteError` | 云端 API 返回非 2xx |
| `ProviderQuotaExceeded` | API 配额耗尽 |

注意：不再有 `ConcurrencyLimitReached`。Chat 通道永远可用不排队；Radio 通道内部串行排队是正常行为不报错；只有第 3 个 session 抢不到通道时才报 `NoChannelAvailable`。

## Acceptance（硬 DoD）

- 分层正确：`AsrService` 通过 `CloudAsrClient` 调用 provider，不直接持有 HTTP client；`TtsService` 同理通过 `TtsClient`。
- 通道隔离：2 个 Radio 通道各跑一个长任务时，Chat 通道的 ASR 请求立即执行，零等待。这是最核心的验收条件。
- 通道独立性：Radio 通道 A 的任务失败/超时不影响 Radio 通道 B 和 Chat 通道。
- 通道上限：第 3 个 radio session 请求通道时，返回 `NoChannelAvailable`。
- 文件转录：`AsrService.transcribeFile()` 行为与 v40 直接调用 `CloudAsrClient` 一致（透传，不改变结果）。
- 流式转录：`AsrService.transcribeStream()` 能产出 `Partial` 和 `Final` 事件（伪流式：每 5 秒一个 Final）。
- Chat 语音输入：麦克风按钮可用 → 录音 → ASR → 文字填入输入框，端到端可用。
- Settings：`asr.enabled=false` 时麦克风按钮灰显；切换 provider 后新请求使用新 provider，已排队请求不受影响。
- 隐私提示：首次开启 `asr.enabled` 或 `tts.enabled` 时弹出提示"音频数据将发送到 [provider 名称] 云端处理"。
- v40/v43 回归：重构后 `radio transcript` 和 `radio tts` 功能不退化。
- CLI help：无新增 CLI（v44 的 ASR/TTS 通过 Service 层被现有 CLI 间接使用）。

验证命令：

- `.\gradlew.bat :app:testDebugUnitTest`
- 真机冒烟：Chat 页签 → 麦克风按钮 → 说一句话 → 文字出现在输入框（同时后台有 Radio 转录任务在跑）

## Files（规划）

- Service 编排层：
  - `app/src/main/java/com/lsl/kotlin_agent_app/asr/AsrService.kt`（编排层，持有 `CloudAsrClient` + `ChannelDispatcher`）
  - `app/src/main/java/com/lsl/kotlin_agent_app/asr/StreamTranscriptEvent.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/tts/TtsService.kt`（编排层，持有 `TtsClient` + `ChannelDispatcher`）
- 通道调度：
  - `app/src/main/java/com/lsl/kotlin_agent_app/media/ChannelDispatcher.kt`
- Settings 扩展：
  - 现有 Settings 结构中新增 ASR/TTS 配置项
- Chat 语音输入：
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/chat/VoiceInputButton.kt`（Compose 组件）
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/chat/VoiceInputViewModel.kt`
- v40/v43 重构（改为通过 Service 层调用）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_transcript/TranscriptTaskManager.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_tts/TtsBilingualWorker.kt`
- Tests：
  - `ChannelDispatcher` 通道隔离单测
  - `AsrService` / `TtsService` mock provider 单测
  - `VoiceInputViewModel` 状态机单测
  - v40/v43 回归测试

## Steps（Strict / TDD）

1) Analysis：确定 `ChannelDispatcher` 的内部实现（3 个独立 Mutex，无共享状态）；确定流式转录的伪流式窗口（固定 5 秒）；列出 Settings 配置项的 UI 布局；确认麦克风权限申请流程（Android 13+ `RECORD_AUDIO`）。
2) TDD Red：`ChannelDispatcher` 通道隔离单测 —— Chat 通道在 Radio 通道满载时仍立即获取许可；Radio 通道 A/B 各自内部串行；第 3 个 session 分配通道返回 null；session 释放后通道可复用。
3) TDD Green：实现 `ChannelDispatcher`（3 个独立 `Mutex`，`assignRadioChannel` 用 `ConcurrentHashMap<String, Channel>` 跟踪 session→channel 映射）。
4) TDD Red：`AsrService` 单测 — `transcribeFile()` 透传 provider 结果 + 走指定通道；`transcribeStream()` 伪流式每 5 秒产出 Final 事件；`AsrDisabled` / `ProviderNotConfigured` 前置校验。
5) TDD Red：`TtsService` 单测 — `synthesize()` 透传 + 走指定通道；`TtsDisabled` / `ProviderNotConfigured` 前置校验。
6) TDD Green：实现 `AsrService` + `TtsService`，接入 `ChannelDispatcher`。
7) TDD Red：`VoiceInputViewModel` 状态机单测 — idle → recording → transcribing → done；超时 60 秒自动停止；ASR disabled 时拒绝启动；录音期间 Radio 后台任务不影响状态流转。
8) TDD Green：实现 `VoiceInputButton` + `VoiceInputViewModel` + 麦克风录音 → PCM Flow → AsrService（channel=CHAT）。
9) Refactor：v40 `TranscriptTaskManager` + v43 `TtsBilingualWorker` 改为通过 Service 层调用（使用 `assignRadioChannel(sessionId)` 获取通道），跑回归测试确认不退化。
10) Verify：UT 全绿；真机冒烟（Chat 语音输入 + 同时 2 个 Radio 转录任务在跑，Chat 零延迟响应）。

## Risks

- 伪流式延迟：每 5 秒才出一次结果，用户体验不如真流式。v44 先接受，后续版本可升级为火山引擎 WebSocket 真流式。
- 麦克风权限：Android 13+ 需要 `RECORD_AUDIO` 权限，首次使用时的权限申请流程需要测试覆盖。
- v40/v43 回归风险：重构调用链路后必须跑完整回归，确认 `radio transcript` 和 `radio tts` 不退化。
- provider 切换的生效时机：已提交的请求用旧 provider 跑完，新请求用新 provider。实现上需要在 `acquire` 时快照当前 provider 配置，而非在 `execute` 时读取。
- 隐私合规：首次启用 ASR/TTS 的隐私提示措辞需要审慎，明确告知"音频数据将上传到 [provider 名称] 云端处理"。
- 3 条通道 = 最多 3 个并发 HTTP 请求到同一个 API provider：如果 provider 有 rate limit（如 OpenAI Whisper 的 RPM 限制），3 条通道同时发请求可能触发限流。建议在 `CloudAsrClient` / `TtsClient` 实现层做 per-provider 的 rate limit 兜底（429 重试），不在通道层处理。

---

## Source：`docs/plan/v45-radio-live-translation-full.md`

<!-- merged by tools/merge_md.py -->

# v45 Plan：实时翻译管线 + MixController + 三模式混音

## Goal

交付"边听电台边实时翻译"的核心体验闭环：

- 电台音频流 → 实时 ASR → 实时翻译 → TTS 合成 → 混音播放
- 三种模式：交替（原声降音量 + 译文朗读）/ 仅译文（原声静音 + 译文朗读）/ 仅字幕（原声正常 + 不播 TTS）
- 实时字幕追加（复用 v42 SubtitleScreen，新增 streaming 追加模式）

## 实时翻译管线架构

```
RadioAudioStream（ExoPlayer 正在播放的电台）
  → AudioSplitter（独立 Player 拉同一 stream URL，每 5 秒切一段 PCM）
  → AsrService.transcribeStream(channel=RADIO_A/B)
  → TranslationClient.translateBatch(segments, context)
  → TtsService.synthesize(translatedText, channel=RADIO_A/B)  # subtitle_only 模式跳过
  → MixController（混音输出）                                    # subtitle_only 模式不参与
  → SubtitleScreen（实时追加 segment）
```

### AudioSplitter 技术方案

v45 采用"独立 Player 旁路解码"方案（与 v39 录制架构一致）：

```
┌──────────────────────────────────────────────────┐
│                  同一个 stream URL                 │
│                                                   │
│  ┌─────────────────┐    ┌──────────────────────┐ │
│  │ ExoPlayer (主)    │    │ Media3 Player (旁路)  │ │
│  │ 负责：播放原声     │    │ 负责：解码 → PCM      │ │
│  │ 输出：扬声器       │    │ 输出：AudioSplitter   │ │
│  │ 音量：受 Mix 控制  │    │ 音量：静音（不出声）    │ │
│  └─────────────────┘    └──────────────────────┘ │
│                                                   │
│  两个 Player 独立拉流，互不干扰                      │
│  代价：双倍带宽；收益：零侵入主播放器，技术风险最低    │
└──────────────────────────────────────────────────┘
```

设计要点：

- 旁路 Player 使用独立的 Media3 ExoPlayer 实例，配置为"仅解码不播放"（自定义 `AudioSink` 将 PCM 数据导出而非送往硬件）
- 旁路 Player 拉取与主 Player 相同的 stream URL，两者之间无数据依赖
- PCM 数据按 5 秒窗口切段，与 v44 伪流式 ASR 的窗口对齐
- 旁路 Player 的生命周期由 `LiveTranslationPipeline` 管理：`radio live start` 时创建，`radio live stop` 时销毁
- 与 v39 录制的 `RecordingSession` 架构一致（v39 也是独立 Player 解码 → PCM → 编码），降低认知负担和代码复用成本

备选方案（记录但不采用）：

- 方案 B：自定义 `AudioSink` 注入主 ExoPlayer 的 `RenderersFactory`，从主播放器的渲染管线中截取 PCM。优点是单路带宽；缺点是侵入主播放器架构，影响播放稳定性，且 ExoPlayer 的 AudioSink 接口在不同版本间有变化，维护成本高。Analysis 阶段评估后决定不采用。

### 延迟预算

| 环节 | 预估延迟 | 说明 |
|------|----------|------|
| PCM 攒段 | 5 秒 | 固定窗口，不可压缩 |
| ASR（伪流式） | 2-5 秒 | Whisper API 单次调用 |
| 翻译（LLM） | 1-3 秒 | 单批 1-3 segments |
| TTS 合成 | 1-2 秒 | 单段文本（subtitle_only 模式无此环节） |
| 端到端（含 TTS） | 9-15 秒 | 用户听到译文时，原声已过去约 10-15 秒 |
| 端到端（subtitle_only） | 8-13 秒 | 字幕出现时，原声已过去约 8-13 秒 |

这个延迟对"语言学习场景"可接受（非同声传译场景）。UI 上显示延迟指示器，让用户知道当前译文对应多少秒前的原声。

另外，由于主 Player 和旁路 Player 独立拉流，两者之间存在 0-3 秒的流位置偏差（取决于 CDN 缓存和连接时机）。这个偏差会叠加到端到端延迟中，但对学习场景影响可忽略。`radio live status` 的 `pipeline_latency_sec` 会反映实际观测到的总延迟。

## PRD Trace

- PRD-0034：REQ-0034-180 / REQ-0034-181

## Scope

做（v45）：

- `LiveTranslationPipeline`：串联 AudioSplitter → ASR → Translation → TTS 的管线编排器
- `AudioSplitter`：独立 Media3 Player 旁路解码 + 每 5 秒切一段 PCM
- `MixController`：控制原声音量 + 播放 TTS 音频，三种模式的状态机
- `radio live start|stop|status`：CLI 最小闭环
- v42 `SubtitleScreen` 扩展：streaming 追加模式（新 segment 实时追加到底部，自动滚动）
- 延迟指示器：字幕视图顶部显示"译文延迟 ~12s"

不做（v45）：

- 不做全链路落盘（v46 的 `--save_audio` flag 将提供 live + 录制的等效能力）
- 不做 AudioFocusManager 与 Chat 并发治理（v46）
- 不做复杂费用统计

## 三种模式对比

| mode | 原声 | TTS | 字幕 | API 消耗 | 适用场景 |
|------|------|-----|------|----------|----------|
| `interleaved` | duck 到 20% | ✅ | ✅ | ASR + LLM + TTS | 沉浸式听译 |
| `target_only` | 静音 | ✅ | ✅ | ASR + LLM + TTS | 纯译文听力 |
| `subtitle_only` | 100% | ❌ | ✅ | ASR + LLM | 只看字幕学习，省 TTS 费用 |

`subtitle_only` 模式下，`LiveTranslationPipeline` 在翻译完成后直接将 segment 推送到 `SubtitleScreen`，跳过 TTS 合成和 MixController 环节。

## MixController 状态机

### 交替模式（interleaved）

```
PLAYING_ORIGINAL（原声 100% 音量）
  │
  ├─ TTS 就绪 ──→ DUCKING（原声降至 20% 音量）
  │                  │
  │                  ├─ 播放 TTS
  │                  │
  │                  └─ TTS 播放完毕 ──→ PLAYING_ORIGINAL（原声恢复 100%）
  │
  └─ TTS 来不及（超时 or 管线延迟）──→ 保持 PLAYING_ORIGINAL
     跳过该 segment 的 TTS，等下一个就绪时恢复正常流程
```

### 仅译文模式（target_only）

```
MUTED_ORIGINAL（原声静音）
  │
  ├─ TTS 就绪 ──→ PLAYING_TTS（播放译文）
  │                  │
  │                  └─ TTS 播放完毕 ──→ WAITING（等待下一段，保持静音）
  │
  └─ TTS 来不及 ──→ WAITING（短暂静音，不恢复原声）
```

### 仅字幕模式（subtitle_only）

MixController 不参与。原声保持 100% 音量，无 TTS 播放。管线在翻译完成后直接输出到字幕视图。

### "来不及"的降级策略

```
每个 segment 有一个 deadline = segment.endSec + maxLatencyBudget(15s)

如果 TTS 在 deadline 前未就绪：
  - 标记该 segment 为 skipped
  - 字幕视图仍然显示文字（灰色标记"未朗读"）
  - 继续处理下一个 segment

连续 3 个 segment 被 skip：
  - 触发 PipelineStalled 警告（UI toast + _STATUS.md 记录）
  - 不自动停止（用户可能网络恢复后继续）

连续 10 个 segment 被 skip：
  - 自动暂停管线，提示用户"翻译管线持续超时，已暂停"
  - 用户可手动恢复
```

注意：`subtitle_only` 模式下不存在 TTS "来不及"的问题，降级策略仅适用于 `interleaved` 和 `target_only`。但 ASR 或翻译环节的连续失败仍然触发 `PipelineStalled`。

```kotlin
class MixController(
    private val radioPlayer: ExoPlayer,
    private val ttsPlayer: AudioTrack,
) {
    enum class Mode { INTERLEAVED, TARGET_ONLY, SUBTITLE_ONLY }

    sealed class State {
        object PlayingOriginal : State()        // interleaved: 原声正常
        object Ducking : State()                // interleaved: 原声降音量 + TTS 播放中
        object MutedOriginal : State()          // target_only: 原声静音
        object PlayingTts : State()             // target_only: TTS 播放中
        object Waiting : State()                // target_only: 等待下一段
        object Passthrough : State()            // subtitle_only: 原声直通，不干预
    }

    fun setMode(mode: Mode)
    fun onTtsReady(segment: TranslatedSegment, ttsAudio: File)
    fun onTtsSkipped(segment: TranslatedSegment)
    fun stop()

    val state: StateFlow<State>
    val currentOriginalVolume: StateFlow<Float>  // 0.0 ~ 1.0
}
```

## `radio live` 与 `radio record` 的关系

| 场景 | 允许？ | 说明 |
|------|--------|------|
| `radio live` 单独运行 | ✅ | 实时翻译，不录制 |
| `radio record` 单独运行 | ✅ | 纯录制，不翻译 |
| 同一电台同时 `live` + `record` | ❌ 互斥 | 避免三路拉流（主播放 + live 旁路 + record 旁路） |
| 不同电台分别 `live` 和 `record` | ❌ | `live` 并发上限 1 路（资源消耗大） |

> v46 补充：v46 的 `radio live start --save_audio` flag 将提供"边听边实时翻译 + 同时保存原声"的等效能力，无需同时运行 `radio record`。`--save_audio` 复用 live 管线的旁路 Player PCM 数据直接编码落盘，不额外拉流。

并发限制：

- `radio live` 全局最多 1 路（实时翻译链路已经占用 1 条 Radio 通道 + 旁路 Player 带宽 + ASR/LLM/TTS API 并发）
- `radio live` 运行时，`radio record` 不可启动（反之亦然，对同一电台）
- `radio live` 占用 v44 的一条 Radio 通道（RADIO_A 或 RADIO_B），剩余一条通道仍可用于其他电台的离线转录任务

### 与 v44 通道隔离的关系

`radio live` 启动时通过 `ChannelDispatcher.assignRadioChannel(liveSessionId)` 获取一条 Radio 通道。管线内的 ASR / Translation / TTS 请求全部走这条通道，通道内串行处理。Chat 通道不受影响。

## 实时字幕视图扩展

v42 的 `SubtitleScreen` 新增 `StreamingMode`：

```kotlin
enum class SubtitleMode {
    STATIC,     // v42: 加载完整 translation.json，可上下滚动
    STREAMING,  // v45: 实时追加，新 segment 出现在底部，自动滚动
}
```

streaming 模式下：

- 新 segment 追加到列表底部，带淡入动画
- 自动滚动到最新 segment（用户手动上滑时暂停自动滚动，点击"回到最新"恢复）
- 顶部显示延迟指示器：`译文延迟 ~12s`（基于最新 segment 的 startSec 与当前播放位置的差值）
- 被 skip 的 segment 显示文字但标记为灰色 + "未朗读"标签（仅 interleaved/target_only 模式）

## CLI

```
radio live start --station <stationId> --mode interleaved|target_only|subtitle_only \
  --source_lang ja --target_lang zh
radio live stop
radio live status
```

`radio live start` 的 result：

```json
{
  "session_id": "live_20260219_2000_a1b2c3",
  "station_id": "nhk_world",
  "mode": "interleaved",
  "source_language": "ja",
  "target_language": "zh",
  "state": "starting",
  "pipeline_latency_sec": null,
  "radio_channel": "RADIO_A",
  "message": "Live translation starting..."
}
```

`radio live status` 的 result：

```json
{
  "session_id": "live_20260219_2000_a1b2c3",
  "state": "running",
  "mode": "interleaved",
  "uptime_sec": 120,
  "segments_processed": 24,
  "segments_skipped": 1,
  "pipeline_latency_sec": 12.3,
  "radio_channel": "RADIO_A",
  "asr_provider": "openai_whisper",
  "translation_provider": "openai_gpt4",
  "tts_provider": "openai_tts"
}
```

注意：`subtitle_only` 模式下 `tts_provider` 为 `null`，`segments_skipped` 仅统计 ASR/翻译失败导致的 skip（不含 TTS skip）。

## 错误码集合

| error_code | 含义 |
|------------|------|
| `LiveSessionAlreadyActive` | 已有一个 live 会话在运行（全局限 1 路） |
| `StationNotPlaying` | 指定电台当前未在播放 |
| `RecordingConflict` | 该电台正在录制中，与 live 互斥 |
| `NoChannelAvailable` | v44 的 2 条 Radio 通道都被占用（不应出现，因为 live 限 1 路，但作为防御性错误码保留） |
| `AsrPipelineStalled` | ASR 连续 10 次超时，管线自动暂停 |
| `TranslationPipelineStalled` | 翻译连续 10 次失败，管线自动暂停 |
| `TtsPipelineStalled` | TTS 连续 10 次失败，管线自动暂停（subtitle_only 模式不会触发） |
| `PipelineWarning` | 连续 3 次 skip，警告（非致命） |
| `InvalidMode` | mode 参数不合法 |
| `AsrDisabled` | ASR 未启用（Settings） |
| `TtsDisabled` | TTS 未启用（Settings）；subtitle_only 模式不检查此项 |

## Acceptance（硬 DoD）

- 管线串联：`radio live start` 后，电台音频流经旁路 Player → PCM → ASR → 翻译 → TTS → 混音输出，端到端可用。
- 旁路 Player：live 启动时创建独立 Media3 Player 拉取同一 stream URL，live 停止时销毁；主 Player 播放不受影响。
- 交替模式：TTS 播放时原声降至 20% 音量，TTS 结束后恢复 100%；切换模式不崩溃不永久静音。
- 仅译文模式：原声静音，只听到 TTS 译文朗读。
- 仅字幕模式：原声保持 100% 音量，不播放 TTS，字幕正常追加；不消耗 TTS API。
- 来不及降级：TTS 未就绪时跳过该 segment，字幕仍显示文字；连续 10 次 skip 自动暂停并提示。
- 延迟指示器：字幕视图顶部显示当前管线延迟（秒）。
- 实时字幕：新 segment 实时追加到字幕视图底部，自动滚动。
- 并发限制：第二个 `radio live start` 必须返回 `LiveSessionAlreadyActive`。
- 互斥：对正在录制的电台执行 `radio live start` 必须返回 `RecordingConflict`。
- 通道占用：`radio live` 占用一条 Radio 通道，`radio live status` 的 result 中体现 `radio_channel`。
- TTS 门禁：`interleaved` 和 `target_only` 模式在 `tts.enabled=false` 时返回 `TtsDisabled`；`subtitle_only` 模式不检查 TTS 开关。
- CLI help：`radio live --help` 为 0。

验证命令：

- `.\gradlew.bat :app:testDebugUnitTest`
- 真机：开启 live interleaved + target_only + subtitle_only 各 2 分钟，观察音量切换、字幕追加、延迟指示器

## Files（规划）

- 实时翻译管线：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_live/LiveTranslationPipeline.kt`（管线编排器）
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_live/AudioSplitter.kt`（独立 Player 旁路解码 + PCM 切段）
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_live/MixController.kt`（混音状态机）
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_live/LiveSession.kt`（会话状态）
- 字幕视图扩展：
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/subtitle/SubtitleScreen.kt`（新增 STREAMING 模式）
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/subtitle/SubtitleViewModel.kt`（新增实时追加逻辑）
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/subtitle/LatencyIndicator.kt`（延迟指示器组件）
- CLI：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/radio/RadioCommand.kt`（新增 `live` 子命令）
- Tests：
  - `MixController` 状态机单测（三种模式 × 正常/来不及/连续 skip）
  - `LiveTranslationPipeline` 管线编排单测（mock ASR/Translation/TTS，验证串联顺序与超时处理；subtitle_only 模式验证跳过 TTS）
  - `AudioSplitter` 切段单测（mock Media3 Player PCM 输出，验证 5 秒切段）
  - CLI argv 门禁 + 并发限制 + 互斥校验 + 通道分配校验

## Steps（Strict / TDD）

1) Analysis：确定旁路 Player 的 `AudioSink` 实现方案（自定义 `AudioSink` 将 PCM 导出到 `AudioSplitter` 的 buffer，不送硬件）；确定延迟预算各环节的超时阈值；确定 MixController 的音量曲线（线性 vs 渐变）；在目标设备（Nova 9）上做旁路 Player PoC 验证（独立 Player 拉同一 stream URL + 自定义 AudioSink 导出 PCM）。
2) TDD Red：`MixController` 状态机单测 — interleaved 模式：TTS 就绪 → ducking → TTS 完毕 → 恢复；TTS 来不及 → 保持原声；连续 3 次 skip → 警告；连续 10 次 → 暂停。
3) TDD Red：`MixController` 状态机单测 — target_only 模式：TTS 就绪 → 播放 → 等待；来不及 → 静音等待。
4) TDD Red：`MixController` 状态机单测 — subtitle_only 模式：状态始终为 Passthrough；`onTtsReady` / `onTtsSkipped` 不改变状态；原声音量始终 1.0。
5) TDD Green：实现 `MixController`。
6) TDD Red：`LiveTranslationPipeline` 编排单测 — mock 全部下游（含 mock AudioSplitter），验证 PCM 段 → ASR → Translation → TTS → MixController 的调用顺序与数据传递；subtitle_only 模式验证 TTS 和 MixController 不被调用；验证通道分配（`assignRadioChannel`）。
7) TDD Red：`AudioSplitter` 单测 — mock Media3 Player 的 PCM 输出，验证每 5 秒产出一段；验证 `start()`/`stop()` 生命周期正确创建/销毁旁路 Player。
8) TDD Green：实现 `AudioSplitter`（独立 Player + 自定义 AudioSink + 5 秒切段）+ `LiveTranslationPipeline`。
9) TDD Red：CLI `radio live` argv 门禁 + `LiveSessionAlreadyActive` + `RecordingConflict` + `NoChannelAvailable` + `TtsDisabled`（仅 interleaved/target_only）+ subtitle_only 不检查 TTS 开关。
10) TDD Green：实现 CLI + 并发/互斥校验。
11) TDD Green：扩展 `SubtitleScreen` streaming 模式 + 延迟指示器。
12) Verify：UT 全绿；真机冒烟（三种模式各 2 分钟；确认旁路 Player 不影响主播放器音质和稳定性）。

## Risks

- 旁路 Player 带宽：双路拉流意味着双倍带宽消耗。对 WiFi 环境影响可忽略；移动数据环境下需要在 UI 上提示用户。建议 `radio live start` 时检测网络类型，移动数据下弹确认提示。
- 旁路 Player 与主 Player 的流位置偏差：两个独立连接拉同一个直播流，CDN 返回的起始位置可能有 0-3 秒差异。这个偏差会叠加到管线延迟中，但对学习场景可接受。不做主动同步（复杂度高、收益低）。
- 端到端延迟：9-15 秒（+ 0-3 秒流偏差）的延迟在学习场景可接受，但如果 ASR/LLM 响应波动大，可能偶尔超过 20 秒。降级策略（skip）是兜底，但频繁 skip 会严重影响体验。
- API 费用：实时翻译每分钟消耗 ASR + LLM + TTS 三个 API 调用（subtitle_only 模式省去 TTS），费用远高于离线模式。建议 UI 上显示"预估费用"或至少在启动时提示。
- 电池消耗：双路拉流 + 持续的网络请求 + 音频处理对移动设备电池压力大，需要在真机测试中观察。
- 旁路 Player 的 AudioSink 自定义：虽然不侵入主 Player，但自定义 AudioSink 仍需要正确处理 Media3 的 AudioSink 接口契约（`handleBuffer`/`flush`/`reset` 等）。Analysis 阶段的 PoC 必须覆盖：正常播放、流中断重连、codec 切换等场景。

---

## Source：`docs/plan/v46-radio-live-AudioFocusManager.md`

<!-- merged by tools/merge_md.py -->

# v46 Plan：全链路落盘 + AudioFocusManager + `radio live` 扩展

## Goal

为 v45 实时翻译管线补齐持久化与并发治理：

- 全链路落盘：live 会话的音频切片 + ASR/翻译 JSONL + TTS chunks 写入 VFS
- AudioFocusManager：app 内音频源优先级仲裁 + 接收系统 AudioFocus 事件的统一入口
- `radio live` CLI 扩展落盘开关

## PRD Trace

- PRD-0034：REQ-0034-182

## Scope

做（v46）：

- `LiveSessionStore`：live 会话全链路落盘到 `workspace/radio_recordings/live_*/`
- 落盘内容（均可选，通过 CLI flag 控制）：
  - 原声音频切片（`--save_audio`）
  - ASR 转录 JSONL（`--save_transcript`）
  - 翻译 JSONL（`--save_translation`）
  - TTS 合成音频（`--save_tts`）
- `AudioFocusManager`：统一管理 app 内所有音频输出的优先级仲裁，并作为系统 AudioFocus 事件的 app 内分发中心
- `radio live` CLI 扩展落盘参数
- live 会话目录可被 v42 Files 浏览器正常浏览

不做（v46）：

- 不做费用统计（另立 PRD）
- 不做落盘文件的二次编辑/裁剪

## 全链路落盘结构

```
workspace/radio_recordings/
  live_20260219_2000_a1b2c3/
    _meta.json
    _STATUS.md
    audio/                          # --save_audio
      chunk_001.ogg
      chunk_002.ogg
      ...
    transcript/                     # --save_transcript
      transcript.jsonl              # 追加写，每行一个 segment
    translation/                    # --save_translation
      translation.jsonl             # 追加写，每行一个 translated segment
    tts/                            # --save_tts
      tts_001.ogg
      tts_002.ogg
      ...
```

### _meta.json Schema

```json
{
  "schema": "kotlin-agent-app/live-session@v1",
  "sessionId": "live_20260219_2000_a1b2c3",
  "stationId": "nhk_world",
  "stationName": "NHK World",
  "mode": "interleaved",
  "sourceLanguage": "ja",
  "targetLanguage": "zh",
  "saveFlags": {
    "audio": true,
    "transcript": true,
    "translation": true,
    "tts": false
  },
  "state": "completed",
  "startedAt": "2026-02-19T20:00:00+08:00",
  "stoppedAt": "2026-02-19T20:35:00+08:00",
  "durationSec": 2100,
  "stats": {
    "audioChunks": 42,
    "segmentsTranscribed": 210,
    "segmentsTranslated": 205,
    "segmentsSkipped": 5,
    "ttsChunksGenerated": 0
  },
  "asrProvider": "openai_whisper",
  "translationProvider": "openai_gpt4",
  "ttsProvider": "openai_tts"
}
```

`state` 取值：`running | completed | stopped_by_user | failed`

### JSONL 格式

transcript.jsonl（每行一个 segment）：

```jsonl
{"seq":1,"chunkIndex":1,"id":0,"startSec":0.0,"endSec":3.2,"text":"こんにちは、NHKワールドニュースです","timestamp":"2026-02-19T20:00:05+08:00"}
{"seq":2,"chunkIndex":1,"id":1,"startSec":3.2,"endSec":7.8,"text":"今日のトップニュースをお伝えします","timestamp":"2026-02-19T20:00:08+08:00"}
```

translation.jsonl（每行一个 translated segment）：

```jsonl
{"seq":1,"chunkIndex":1,"id":0,"startSec":0.0,"endSec":3.2,"sourceText":"こんにちは、NHKワールドニュースです","translatedText":"你好，这里是NHK世界新闻","timestamp":"2026-02-19T20:00:12+08:00"}
{"seq":2,"chunkIndex":1,"id":1,"startSec":3.2,"endSec":7.8,"sourceText":"今日のトップニュースをお伝えします","translatedText":"为您播报今天的头条新闻","timestamp":"2026-02-19T20:00:15+08:00"}
```

选择 JSONL 而非 JSON 数组的理由：

- 追加写友好（不需要维护数组闭合括号）
- 进程崩溃时已写入的行不丢失
- 逐行解析，内存友好（live 会话可能持续数小时）

### 落盘写入原子性

- 音频 chunk：先写临时文件 `chunk_NNN.ogg.tmp`，写完后 rename 为 `chunk_NNN.ogg`
- JSONL：直接 append + flush，每行写入后立即 flush 到磁盘
- `_meta.json`：每次状态变更时全量重写（文件小，原子性通过 write-to-tmp + rename 保证）
- `_STATUS.md`：与 `_meta.json` 同步更新

## AudioFocusManager

### 职责

统一管理 app 内部多个音频输出源的优先级仲裁，同时作为 Android 系统 AudioFocus 事件在 app 内的分发中心。

### 双层 AudioFocus 架构

```
┌─────────────────────────────────────────────────────────┐
│  Android 系统 AudioFocus                                 │
│  （来电、其他 App 播放音乐、导航语音等）                    │
│                                                         │
│  MusicPlaybackService 已通过 MediaSession 注册系统焦点     │
│  系统事件 → MusicPlaybackService → AudioFocusManager      │
└────────────────────────┬────────────────────────────────┘
                         │ onSystemFocusChanged(event)
                         ▼
┌─────────────────────────────────────────────────────────┐
│  AudioFocusManager（v46：app 内仲裁 + 系统事件分发）       │
│                                                         │
│  app 内仲裁：                                            │
│    CHAT_TTS > RADIO_TTS > RADIO_PLAYBACK                │
│                                                         │
│  系统事件分发：                                           │
│    FOCUS_LOSS       → 全部暂停                           │
│    FOCUS_LOSS_DUCK  → 全部 duck                          │
│    FOCUS_GAIN       → 恢复到系统中断前的 app 内状态        │
│                                                         │
│  ├── CHAT_TTS → 直接控制 Chat TTS 播放器                 │
│  ├── RADIO_TTS → 通知 MixController 暂停/恢复 TTS        │
│  └── RADIO_PLAYBACK → 通知 MixController 调整原声音量     │
│        └── MixController（v45：模式内音量控制）            │
└─────────────────────────────────────────────────────────┘
```

设计要点：

- `MusicPlaybackService` 继续持有系统 AudioFocus 的注册/释放（通过 MediaSession，这是 v38 已有的行为，不改动）
- `MusicPlaybackService` 收到系统 AudioFocus 变化时，调用 `AudioFocusManager.onSystemFocusChanged(event)` 转发
- `AudioFocusManager` 内部维护两层状态：
  - `systemFocusState`：来自系统的焦点状态（`FOCUS_GAIN` / `FOCUS_LOSS` / `FOCUS_LOSS_DUCK` / `FOCUS_LOSS_TRANSIENT`）
  - `appSourceStates`：app 内各音频源的状态（`PLAYING` / `DUCKED` / `PAUSED` / `IDLE`）
- 系统事件优先级高于 app 内仲裁：系统 `FOCUS_LOSS` 时，即使 CHAT_TTS 正在播放也必须暂停
- 系统 `FOCUS_GAIN` 恢复时，`AudioFocusManager` 恢复到系统中断前的 app 内状态（而非全部恢复为 PLAYING）

```kotlin
class AudioFocusManager {

    enum class AudioSource {
        CHAT_TTS,       // Chat Agent 语音回复（app 内最高优先级）
        RADIO_TTS,      // Radio live 实时翻译 TTS
        RADIO_PLAYBACK, // Radio 电台原声播放
    }

    /**
     * 请求 app 内音频焦点。
     * 如果有更高优先级的源正在播放，排队等待。
     * 如果有更低优先级的源正在播放，触发其 duck/pause。
     * 如果系统焦点已丢失，挂起直到系统焦点恢复。
     */
    suspend fun requestFocus(source: AudioSource): FocusGrant

    /**
     * 释放 app 内音频焦点。
     * 恢复被 duck/pause 的低优先级源。
     */
    fun releaseFocus(grant: FocusGrant)

    /**
     * 由 MusicPlaybackService 调用，转发系统 AudioFocus 事件。
     * AudioFocusManager 据此暂停/duck/恢复 app 内所有音频源。
     */
    fun onSystemFocusChanged(event: SystemFocusEvent)

    /** 当前各源的状态（综合系统焦点 + app 内仲裁的最终结果） */
    val sourceStates: StateFlow<Map<AudioSource, AudioSourceState>>

    /** 当前系统焦点状态（供 UI 或诊断使用） */
    val systemFocusState: StateFlow<SystemFocusEvent>
}

enum class AudioSourceState {
    PLAYING,    // 正常播放
    DUCKED,     // 被降音量（仍在播放）
    PAUSED,     // 被暂停（app 内仲裁或系统焦点丢失）
    IDLE,       // 未播放
}

enum class SystemFocusEvent {
    FOCUS_GAIN,             // 系统焦点恢复
    FOCUS_LOSS,             // 永久丢失（如其他 app 开始播放音乐）
    FOCUS_LOSS_TRANSIENT,   // 短暂丢失（如来电）
    FOCUS_LOSS_DUCK,        // 短暂丢失但允许降音量（如导航语音）
}

data class FocusGrant(
    val source: AudioSource,
    val grantId: String,
)
```

### app 内优先级规则

| 事件 | RADIO_PLAYBACK | RADIO_TTS | CHAT_TTS |
|------|----------------|-----------|----------|
| CHAT_TTS 开始 | DUCKED（20%） | PAUSED | PLAYING |
| CHAT_TTS 结束 | 恢复原音量 | 恢复播放 | IDLE |
| RADIO_TTS 开始（interleaved） | DUCKED（20%） | PLAYING | 不受影响 |
| RADIO_TTS 开始（target_only） | PAUSED | PLAYING | 不受影响 |
| RADIO_TTS 结束 | 恢复 | IDLE | 不受影响 |

### 系统焦点事件的 app 内响应

| 系统事件 | app 内响应 | 恢复行为 |
|----------|-----------|----------|
| `FOCUS_LOSS` | 全部 PAUSED | 不自动恢复（用户需手动恢复） |
| `FOCUS_LOSS_TRANSIENT` | 全部 PAUSED | `FOCUS_GAIN` 时恢复到中断前的 app 内状态 |
| `FOCUS_LOSS_DUCK` | 全部 DUCKED（在当前音量基础上再降至 30%） | `FOCUS_GAIN` 时恢复到中断前的音量 |
| `FOCUS_GAIN` | 恢复到系统中断前的 app 内状态快照 | — |

关键规则：

- CHAT_TTS 优先级最高，到达时 RADIO_TTS 立即暂停（不是 duck，因为两个 TTS 同时播放会混乱）
- CHAT_TTS 结束后，RADIO_TTS 恢复播放（从暂停点继续，不跳过）
- RADIO_PLAYBACK 被 duck 时音量降至 20%，被 pause 时完全静音
- 所有恢复操作带 300ms 渐变（避免突兀的音量跳变）
- 系统 `FOCUS_LOSS_TRANSIENT` 恢复时，`AudioFocusManager` 先恢复 app 内状态快照，再让 app 内仲裁规则生效（例如：中断前 CHAT_TTS 正在播放导致 RADIO_TTS 被暂停，恢复后仍然是 CHAT_TTS 播放 + RADIO_TTS 暂停，而非全部恢复为 PLAYING）

### 与 v45 MixController 的关系

v45 的 `MixController` 负责 interleaved/target_only 模式下原声与 Radio TTS 的音量控制。v46 的 `AudioFocusManager` 在其上层，处理跨源仲裁（app 内 Chat TTS 抢占 + 系统焦点事件）。

`MixController` 不直接感知系统 AudioFocus，只接收 `AudioFocusManager` 下发的指令（duck/pause/resume）。

## CLI 扩展

v45 的 `radio live start` 扩展落盘参数：

```
radio live start --station <stationId> --mode interleaved|target_only|subtitle_only \
  --source_lang ja --target_lang zh \
  [--save_audio] [--save_transcript] [--save_translation] [--save_tts]

radio live stop
radio live status
radio live list          # 列出历史 live 会话（已落盘的）
```

不带任何 `--save_*` flag 时，不落盘（纯实时体验，退出后无痕）。

带任何一个 `--save_*` flag 时，自动创建 `live_*/` 目录 + `_meta.json` + `_STATUS.md`。

`radio live list` 的 result：

```json
{
  "sessions": [
    {
      "sessionId": "live_20260219_2000_a1b2c3",
      "stationName": "NHK World",
      "mode": "interleaved",
      "state": "completed",
      "durationSec": 2100,
      "saveFlags": { "audio": true, "transcript": true, "translation": true, "tts": false },
      "startedAt": "2026-02-19T20:00:00+08:00"
    }
  ]
}
```

## 错误码集合

| error_code | 含义 |
|------------|------|
| `LiveSessionNotFound` | 指定的 live 会话目录不存在 |
| `LiveSessionNotActive` | 尝试 stop 但没有活跃的 live 会话 |
| `DiskSpaceLow` | 磁盘剩余空间 < 100MB，拒绝开启落盘 |
| `LiveSessionSaveFailed` | 落盘写入失败（IO 错误） |
| `AudioFocusConflict` | 音频焦点仲裁异常（不应出现，防御性错误码） |

继承 v45 的错误码（`LiveSessionAlreadyActive`、`StationNotPlaying`、`RecordingConflict` 等）。

## Files 浏览集成

live 会话目录在 v42 的 Files 浏览器中可见：

- `live_*/` 目录显示为"实时翻译会话"卡片（复用 v42 的 `VfsRendererRegistry`）
- `_meta.json` 渲染为会话概览卡片（电台名、时长、模式、落盘内容摘要）
- `transcript.jsonl` / `translation.jsonl` 渲染为字幕视图（复用 v42 `SubtitleScreen`，JSONL 逐行解析为 segments）
- `audio/chunk_NNN.ogg` 可点击播放
- `tts/tts_NNN.ogg` 可点击播放

新增渲染器：

| 文件模式 | 渲染器 | 匹配规则 |
|----------|--------|----------|
| `_meta.json`（在 `live_*/` 下） | `LiveSessionCardRenderer` | 文件名为 `_meta.json` 且父路径匹配 `*/live_*/` |
| `*.jsonl`（在 `transcript/` 或 `translation/` 下） | `JsonlSubtitleRenderer` | `.jsonl` 后缀 + 父路径匹配 `*/live_*/transcript/` 或 `*/live_*/translation/` |

### JsonlSubtitleRenderer 懒加载策略

长时间 live 会话的 JSONL 可能达到数 MB（数千行），不能一次性全部加载到内存。采用类似聊天记录的倒序分页加载：

- 首次打开：只加载最后 200 行（最新内容），从文件末尾向前读取
- 用户上滑：触发加载更早的 200 行，追加到列表顶部
- 内存上限：最多保留 1000 行在内存中，超出时释放最早的页
- 加载指示器：列表顶部显示"加载更早内容..."（上滑触发）或"已到达开头"
- 实现方式：`RandomAccessFile` 从文件末尾向前扫描换行符，定位到目标行范围后逐行解析

```kotlin
class JsonlPagingSource(
    private val file: File,
    private val pageSize: Int = 200,
) {
    /** 加载最后 N 行（首次打开） */
    suspend fun loadTail(): List<JsonlLine>

    /** 加载更早的 N 行（用户上滑） */
    suspend fun loadPrevious(): List<JsonlLine>?  // null = 已到达文件开头

    /** 当前已加载的行范围 */
    val loadedRange: IntRange

    data class JsonlLine(
        val lineNumber: Int,    // 文件中的行号（从 1 开始）
        val content: String,    // 原始 JSON 字符串
    )
}
```

## Acceptance（硬 DoD）

- 落盘完整性：开启 `--save_audio --save_transcript --save_translation` 后，live 会话结束时目录内文件齐全（audio chunks + transcript.jsonl + translation.jsonl + _meta.json + _STATUS.md）。
- 落盘原子性：进程崩溃后重启，已写入的 JSONL 行和已完成的 audio chunk 不丢失、不损坏。
- 不落盘模式：不带 `--save_*` flag 时，`workspace/radio_recordings/` 下不产生任何新目录。
- 磁盘保护：磁盘剩余 < 100MB 时拒绝开启落盘，返回 `DiskSpaceLow`。
- AudioFocus — Chat 抢占：Chat TTS 播放时，Radio TTS 暂停、原声降至 20%；Chat TTS 结束后，Radio TTS 恢复、原声恢复。
- AudioFocus — 恢复：Chat TTS 抢占后恢复，Radio TTS 从暂停点继续（不跳过 segment）。
- AudioFocus — 渐变：所有音量变化带 300ms 渐变（可通过 UT 验证调用参数）。
- AudioFocus — 系统焦点：来电（`FOCUS_LOSS_TRANSIENT`）时全部暂停；挂断后恢复到中断前的 app 内状态。
- AudioFocus — 系统 duck：导航语音（`FOCUS_LOSS_DUCK`）时全部降音量；导航结束后恢复。
- AudioFocus — 永久丢失：其他 app 播放音乐（`FOCUS_LOSS`）时全部暂停，不自动恢复。
- JSONL 懒加载：打开一个 2000 行的 JSONL 文件，首次只加载最后 200 行；上滑可加载更早内容；不 OOM。
- Files 浏览：live 会话目录可在 Files 中正常浏览，`_meta.json` 渲染为卡片，JSONL 渲染为字幕视图。
- CLI：`radio live list` 列出历史会话；`radio live --help` 为 0。
- 清空确认：删除 live 会话目录需二次确认（复用现有 Files 删除确认机制）。

验证命令：

- `.\gradlew.bat :app:testDebugUnitTest`
- 真机：开启 live + 落盘 5 分钟 → 停止 → Files 浏览落盘内容 → 播放 audio chunk → 查看字幕 → 来电测试暂停/恢复

## Files（规划）

- 落盘模块：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_live/LiveSessionStore.kt`（落盘管理：目录创建、JSONL 追加、chunk 写入、meta 更新）
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_live/LiveSessionMetaV1.kt`（kotlinx.serialization）
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_live/JsonlWriter.kt`（JSONL 追加写 + flush 工具）
- AudioFocusManager：
  - `app/src/main/java/com/lsl/kotlin_agent_app/media/AudioFocusManager.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/media/FocusGrant.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/media/SystemFocusEvent.kt`
- Files 渲染器：
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/renderer/LiveSessionCardRenderer.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/renderer/JsonlSubtitleRenderer.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/renderer/JsonlPagingSource.kt`（JSONL 倒序分页加载）
- v45 集成：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_live/LiveTranslationPipeline.kt`（接入 LiveSessionStore + AudioFocusManager）
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_live/MixController.kt`（接入 AudioFocusManager 回调）
- MusicPlaybackService 桥接：
  - `app/src/main/java/com/lsl/kotlin_agent_app/media/MusicPlaybackService.kt`（新增：系统 AudioFocus 事件转发给 AudioFocusManager）
- CLI：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/radio/RadioCommand.kt`（`radio live` 扩展 `--save_*` flags + `list` 子命令）
- Tests：
  - `LiveSessionStore` 单测（目录创建、JSONL 追加、崩溃恢复模拟、磁盘空间检查）
  - `JsonlWriter` 单测（追加写、flush、并发安全）
  - `JsonlPagingSource` 单测（loadTail 200 行、loadPrevious 分页、到达文件开头返回 null、空文件处理）
  - `AudioFocusManager` 单测（app 内仲裁：Chat 抢占 → Radio 暂停 → Chat 释放 → Radio 恢复；系统焦点：LOSS → 全部暂停 → GAIN → 恢复快照；LOSS_DUCK → 全部降音量 → GAIN → 恢复；渐变参数验证）
  - `LiveSessionCardRenderer` / `JsonlSubtitleRenderer` canRender 单测
  - CLI argv 门禁 + `--save_*` flag 组合测试

## Steps（Strict / TDD）

1) Analysis：确定 JSONL flush 策略（每行 flush vs 每 N 行 flush，权衡性能与崩溃安全）；确定 `MusicPlaybackService` 转发系统 AudioFocus 事件的接口契约（回调 vs SharedFlow）；确定磁盘空间检查频率（启动时一次 + 每 60 秒周期检查，低于 100MB 时停止落盘并写 `_STATUS.md` 警告）；在目标设备（Nova 9）上做 IO benchmark（每 5 秒 flush JSONL + 写 audio chunk 的耗时）。
2) TDD Red：`JsonlWriter` 单测 — 追加写、flush、文件不存在时自动创建、并发写入安全。
3) TDD Green：实现 `JsonlWriter`。
4) TDD Red：`JsonlPagingSource` 单测 — 1000 行文件 loadTail 返回最后 200 行；连续 loadPrevious 返回更早的 200 行；到达开头返回 null；空文件 loadTail 返回空列表；单行文件正确处理。
5) TDD Green：实现 `JsonlPagingSource`。
6) TDD Red：`LiveSessionStore` 单测 — 创建目录结构、写入 `_meta.json`、追加 JSONL、写入 audio chunk（tmp + rename）、磁盘空间不足拒绝、崩溃后已写入内容完整。
7) TDD Green：实现 `LiveSessionStore`。
8) TDD Red：`AudioFocusManager` app 内仲裁单测 — requestFocus/releaseFocus 状态流转；Chat 抢占 Radio 场景；多次连续抢占/释放；渐变参数传递。
9) TDD Red：`AudioFocusManager` 系统焦点单测 — `FOCUS_LOSS_TRANSIENT` → 全部暂停 → `FOCUS_GAIN` → 恢复到中断前快照；`FOCUS_LOSS_DUCK` → 全部降音量 → `FOCUS_GAIN` → 恢复；`FOCUS_LOSS` → 全部暂停 → 不自动恢复；系统中断期间 app 内 requestFocus 挂起直到系统焦点恢复。
10) TDD Green：实现 `AudioFocusManager`。
11) TDD Red：v45 `LiveTranslationPipeline` 集成 `LiveSessionStore` 的单测 — 管线产出 segment 时 store 被正确调用；`--save_*` flag 控制哪些内容落盘。
12) TDD Green：集成 `LiveSessionStore` 到 `LiveTranslationPipeline`。
13) TDD Red：v45 `MixController` 集成 `AudioFocusManager` 的单测 — Chat TTS 到达时 MixController 收到暂停通知；Chat TTS 结束时收到恢复通知；系统 FOCUS_LOSS 时 MixController 收到全部暂停通知。
14) TDD Green：集成 `AudioFocusManager` 到 `MixController` + `MusicPlaybackService` 桥接。
15) TDD Red：Files 渲染器 `canRender` 单测（精确匹配 `*/live_*/` 路径模式）+ CLI `--save_*` flag 组合 + `radio live list` 测试。
16) TDD Green：实现渲染器 + CLI 扩展。
17) Verify：UT 全绿；真机冒烟（live + 落盘 5 分钟 → Files 浏览 → Chat TTS 抢占恢复 → 来电暂停/恢复 → JSON
