# v33 Plan：Radio CLI 控制面（terminal_exec radio）+ 可选收听行为日志（需用户同意）

## Goal

把“电台播放器”变成可被 agent/人类稳定操控、可审计、可测试的能力：

- `terminal_exec` 增加白名单命令 `radio`（遵守 paw-cli-add-workflow：无 shell/无外部进程/单行/禁用 token/help 必须可用/审计落盘）；  
- CLI 能查询/控制电台播放状态（最小：status/play/pause/resume/stop）；  
- 引入可选收听行为日志（默认关闭、同意开启、可一键清空且二次确认），为未来推荐/分析预留基础设施与口径。  

## PRD Trace

- PRD-0030：REQ-0030-050 / REQ-0030-051 / REQ-0030-052
- PRD-0030：REQ-0030-060 / REQ-0030-061 / REQ-0030-062

## Scope

做（v33）：

- 新增 `terminal_exec radio`：
  - `radio --help` / `radio help`
  - `radio status`
  - `radio play --in "<agents-path>"`（只允许 `.agents/workspace/radios/**` 下 `.radio`）
  - `radio pause|resume|stop`
- 新增 builtin skill（例如 `radio-cli`）：
  - 说明书必须实际调用 `terminal_exec`，并写清可验证输出（stdout 关键字/result 字段）
- 新增收听行为日志（默认关闭）：
  - Settings 提供开关 + 说明 + 日志路径透明
  - 开启后记录最小事件集（JSONL）
  - 提供清空入口（二次确认）
- Robolectric 单测：
  - `radio` 命令成功/失败路径
  - 日志开关与清空门禁

不做（v33）：

- 复杂目录同步 CLI（如 `radio sync ...`）  
- 推荐算法/排序个性化  
- 把日志自动上传或自动喂给 AI（只做“可选记录 + 可被用户导出/清空”的基础设施）  

## Command Protocol（按 paw-cli-add-workflow 固化）

统一输出：

- `stdout`：人类可读摘要（短）
- `result`：结构化 JSON（稳定字段，供 agent 消费）
- `artifacts[]`：必要时落盘引用（大输出/调试细节）
- `exitCode`：0 成功；非 0 失败
- `errorCode` / `errorMessage`：失败必须可解释

### `radio status` 建议 result schema（最小）

- `state`: `idle|playing|paused|stopped|error`
- `station`: `{ path?, id?, name?, country?, faviconUrl? }`（缺则为空或省略）
- `startedAtMs?` / `positionMs?`（直播流 position 可选；不可得则省略）

### `radio play` 输入门禁（强制）

- 只允许 `--in "<agents-path>"` 指向：
  - `.agents/workspace/radios/**.radio`
- 其他路径一律拒绝（例如 `PathNotAllowed` / `NotInRadiosDir` / `NotRadioFile`），避免越权读取任意文件。

## Listening Log（JSONL，最小化，默认关闭）

### 目标

记录“足够用于推荐/分析的最小事件”，但避免记录敏感信息；并把开关权交给用户。

### 建议落盘路径（透明可见）

- `.agents/artifacts/listening_history/`（目录名可调整，但必须在 UI 明示）
  - `events.jsonl`（追加写）

### 建议事件 schema（v1）

每行一条 JSON：

- `schema`: `kotlin-agent-app/listening-event@v1`
- `ts`: ISO-8601（UTC）
- `source`: `music|radio`
- `action`: `play|pause|resume|stop|error|favorite_add|favorite_remove`
- `item`：
  - music：`{ path }`
  - radio：`{ stationId?, radioFilePath?, name?, country? }`
- `userInitiated`: boolean（用户点击 vs 自动恢复/自动重试）
- `error`（仅失败时）：`{ code, message? }`

隐私约束（强制写进实现与测试）：

- 默认关闭；未开启时不得写入事件；
- 不记录完整 `streamUrl`（尤其不得记录 query/token）；如必须记录用于排障，仅允许记录 host + path 的脱敏形式，并落盘到 artifacts（且用户可清空）。

### 清空门禁

- 清空属于危险操作：必须二次确认（UI 文案需明确“将删除本机收听记录”）。

## Files（规划：遵守 paw-cli-add-workflow）

- 命令注册表（只注册，不写实现）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalCommands.kt`
- 命令实现（独立目录）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/radio/*`
- 单测（优先扩）：
  - `app/src/test/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalExecToolTest.kt`
- 内置 skill：
  - `app/src/main/assets/builtin_skills/radio-cli/SKILL.md`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/AgentsWorkspace.kt`
- 日志与开关（建议）：
  - Settings UI（现有 settings 结构中新增项）
  - 日志写入模块（建议新增包：`app/src/main/java/com/lsl/kotlin_agent_app/listening_history/*`）

## Steps（Strict / TDD）

1) Analysis：锁定 `radio` argv 协议与 `status` 的 result schema（字段稳定），并定义错误码集合（NotInRadiosDir/NotRadioFile/InvalidArgs/…）。  
2) TDD Red：在 `TerminalExecToolTest.kt` 写 `radio --help` / `radio help` / `radio <sub> --help` 用例，要求 `exit_code=0` 且 stdout/result 关键字段存在。  
3) TDD Red：写 `radio play --in "<bad path>"` 被拒绝用例（路径越界/扩展名不对/文件不存在）。  
4) TDD Green：实现 `RadioCommand`（先把 help + argv 校验 + 结构化输出跑通），并对接现有播放器运行时（复用 controller/provider）。  
5) TDD Red：为“日志默认关闭不写入/开启后写入/清空需二次确认门禁”写单测（可先从纯 Kotlin 模块的 logger 测起，再接 UI）。  
6) TDD Green：实现 listening history logger（JSONL 追加写 + 脱敏规则）与 Settings 开关；实现清空流程（带确认）。  
7) 接入：注册命令到 `TerminalCommands.defaultRegistry(...)`；新增 builtin skill 并在 `AgentsWorkspace.ensureInitialized()` 安装。  
8) Verify：`.\gradlew.bat :app:testDebugUnitTest` exit code=0。  

## Risks

- CLI 与 UI 状态一致性：需要保证 `radio` CLI 操作与 UI 播放器共享单一事实来源（controller），避免“CLI 改了 UI 不刷新”。  
- 日志敏感性：一旦默认开启或记录了可识别信息，会引发信任与合规风险；必须默认关闭并透明可控。  

