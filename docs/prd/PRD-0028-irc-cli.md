# PRD-0028：IRC CLI（会话绑定的长连接 + 收发 + 可选自动递送）

日期：2026-02-18  
定位：在 `terminal_exec` 的“白名单 pseudo CLI（无 shell / 无外部进程）”框架下，为 App 增加一个 **IRC 长连接客户端**，并提供“懒加载连接 + 自动入频道 + 收发消息 + 可选自动递送给主 agent”最小闭环；连接必须与 **Agent session 生命周期绑定**，避免“每次发消息都重新登录/入频道”。

## Vision

让 Agent 与人类都能通过 `terminal_exec` 以可审计、可控风险的方式完成：

- 在一个 Agent session 内，IRC 连接只建立一次（懒加载），并自动加入配置的频道
- 发送消息只需要执行一次命令（无需显式登录/入频道流程）
- 拉取（pull）最近的新消息，并默认落盘（便于追溯、复盘与调试）
- 默认 `NICK` 长度不超过 **9 个字符**（超出直接拒绝并给出可解释错误）

并且：

- 凭据只从本地 `.env` 读取，不允许通过 argv 传入密码（避免审计落盘泄露）
- stdout/result 默认只输出摘要；需要更多细节时落盘为 artifacts 引用
- （可选开关）IRC 来的消息可在安全检查后自动递送给主 agent，由主 agent 决定是否反馈给人类

## Non-Goals（v28）

- 不做：多连接池（同一 session 内同时连多个 server）
- 不做：复杂管理命令（ban/kick/mode/WHO/WHOIS/CTCP/DCC/file transfer）
- 不做：完整的 IRC bouncer 替代（ZNC 等）
- 不做：把代理/证书等网络配置硬编码进仓库（只返回可解释错误与排障提示）
- 不做：通过 IRC 直接远程操控手机主会话 agent（仅留档想法，未来版本再做）

## Dependencies（Network）

- IRC TCP：`IRC_SERVER:IRC_PORT`（明文或 TLS，见配置）
- Android 端网络权限：`android.permission.INTERNET`（已具备）

## Library Choice（Java/Kotlin）

本 PRD 的工程口径：**优先采用成熟库**。v28 选择：

- **PircBotX（优先实现）**：成熟度高、功能覆盖广、社区历史长

备选（如遇到 Android 兼容/体积/依赖冲突风险）：

- **Kitteh IRC Client Library（client-lib）**
- 降级方案：最小协议实现（`Socket + 协程`），但接口与验收不变（通过测试证明行为一致）。

## Session Lifecycle Binding（必须）

### 为什么必须绑定

`terminal_exec` 的单次调用天然是“短事务”，但 IRC 的价值来自“长连接 + 保活 + 频道状态”。为了满足“发消息不需要每次登录/入频道”，必须把连接对象缓存到 **Agent session** 的生命周期中（session 结束即断开并清理）。

### 期望行为（v28）

- 第一次调用 `irc status` / `irc send` 时才创建连接（懒加载）
- 建连成功后自动 `JOIN` 默认频道（可带 channel key）
- 若连接断开：下一次 `irc send` 触发自动重连（限频 + 可解释错误）
- session 结束：连接必须 `QUIT`/关闭 socket 并释放资源
- 默认会维护一个“最近消息 ring buffer（内存）+ JSONL 落盘（磁盘）”，用于 `irc pull` 与审计复盘

## Secrets / Credentials

### `.env` 格式（示例）

> 禁止把真实密码提交到 git；`.env` 必须本地化存放。

```dotenv
# IRC 基本配置
IRC_SERVER=irc.example.com
IRC_PORT=6697
IRC_TLS=1

# 默认频道（v28 只保证 1 个默认频道）
IRC_CHANNEL=#lemon

# NICK 约束：<= 9 字符（超出将拒绝）
IRC_NICK=lemonbot

# 可选：服务器 PASS（极少数网络需要）
IRC_SERVER_PASSWORD=

# 可选：频道 key（有密码的频道才填）
IRC_CHANNEL_KEY=

# 可选：NickServ 认证（常见）
IRC_NICKSERV_PASSWORD=

# 可选：从 IRC 收到消息后自动递送给主 agent（默认关闭）
# 0/空：关闭；1：开启
IRC_AUTO_FORWARD_TO_AGENT=0
```

### 存放位置约定（v28）

默认读取：

- `.agents/skills/irc-cli/secrets/.env`

理由：与 `qqmail-cli` 等同一风格，凭据就近存放且不进入仓库。

## Project Rules（实现必须遵守：来自 paw-cli-add-workflow）

- `terminal_exec` 为白名单伪终端：**无 shell、无外部进程**。
- `TerminalCommands.kt` 只能做“命令注册表/默认 registry 构建”，禁止堆实现逻辑。
- 顶层命令必须独立目录：
  - `irc`：`app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/irc/*`
- `TerminalExecTool` 的 registry 初始化逻辑不改；只在 `TerminalCommands.defaultRegistry(...)` 注册新增命令。
- 禁止在命令行里使用 `;` / `|` / `>` 等 shell token（会被 parseAndValidateCommand 拒绝）。

## Command Set（v28）

统一约束：

- 所有命令输出同时提供：`stdout`（摘要）+ `result`（结构化 JSON）+（必要时）`artifacts[]`（落盘大输出）。
- 密码类字段不得出现在 argv / stdout / 审计落盘中（见 Safety）。
- 消息内容支持 `--text-stdin`（避免复杂 quoting，并减少 stdout/audit 暴露风险）。
- 任何“可能导致把大量文本喂给 agent”的命令必须内置截断策略（见 `Truncation`）。

### irc

#### `irc status`

- 命令：
  - `irc status`
- 行为：
  - 返回当前 session 的 IRC 运行时状态（未初始化 / 连接中 / 已连接 / 已入频道 / 最近错误）
  - 不输出任何 password/key 明文
- result（最小字段）：
  - `ok: boolean`
  - `command: "irc status"`
  - `state: "not_initialized" | "connecting" | "connected" | "joined" | "error"`
  - `server?: string`
  - `port?: number`
  - `tls?: boolean`
  - `nick?: string`
  - `channel?: string`
  - `last_error?: {error_code: string, message: string}`

#### `irc send`

- 命令：
  - `irc send [--to <#channel|nick>] (--text <text> | --text-stdin) [--confirm]`
- 行为：
  - `--to` 省略时使用 `IRC_CHANNEL`
  - 懒加载确保已连接并已加入默认频道（如发送目标就是默认频道）
  - `--text-stdin`：从 tool 的 `stdin` 读取消息体（推荐）
  - 发送类型：`PRIVMSG`
- 发送门禁（v28 折中设计）：
  - 默认 **向 `IRC_CHANNEL` 发送不需要 `--confirm`**（高频场景）
  - 若 `--to` 不是默认 `IRC_CHANNEL`（例如私聊或其他频道），必须显式 `--confirm`，否则拒绝（`ConfirmRequired`）
- result（最小字段）：
  - `ok`
  - `command: "irc send"`
  - `to`
  - `message_len: number`
  - `session_bound: true`
  - `connected: boolean`
  - `joined_default_channel: boolean`

#### `irc pull`

- 命令：
  - `irc pull [--from <#channel>] [--limit <n>] [--format summary|full] [--peek]`
- 行为：
  - `--from` 省略时使用 `IRC_CHANNEL`
  - 返回“自上次成功 pull（非 peek）以来”的新消息（去重，避免重复喂给 agent）
  - cursor 去重粒度：**按频道**维护（每个 `--from` 各自一份 cursor，不互相影响）
  - 默认 `--format summary`：每条消息只返回安全摘要（见 `Truncation`）
  - `--format full`：返回更长内容，但仍受总体长度上限与截断策略约束；如仍超限，必须落盘并返回 artifact 引用（v29+ 扩展点；v28 先保证 summary 可靠）
  - `--peek`：只查看，不更新“最后递送指针”
- result（最小字段）：
  - `ok`
  - `command: "irc pull"`
  - `from`
  - `returned: number`
  - `cursor_before?: string`
  - `cursor_after?: string`（仅非 peek 且 ok 时更新）
  - `messages: [{id, ts, nick, text}]`（text 可能为截断后的安全摘要）
  - `truncated: boolean`
  - `dropped_count?: number`（因 ring buffer 上限导致丢弃的数量，用于提示用户“可能漏消息”）

## Safety（必须）

1) 凭据不出现在 argv / stdout / audit  
- 禁止 `--password/--key` 等通过 argv 传入；只允许从 `.env` 读取。
- `terminal_exec` 审计会记录 `command/argv/stdout/stderr`，因此**任何敏感信息不得进入这些字段**。

2) Nick 约束  
- `IRC_NICK`（或未来 argv 覆盖的 nick）长度 > 9 必须拒绝（`NickTooLong`）。

3) 输出与隐私  
- stdout/result 只输出摘要（server/nick/channel/长度/状态等）。
- 若需要调试细节（握手/认证/重连原因）：必须 `--out`（v29+）落盘并通过 artifacts 引用返回（v28 先保留扩展点）。

4) Inbound 消息的“第三者模型”与可选自动递送  
- IRC 来的消息默认视为“第三者给主人的消息”，**不直接展示给 Chat 页签的人类**。
- 当 `IRC_AUTO_FORWARD_TO_AGENT=1` 时：新入站消息在通过安全检查后自动递送给主 agent（由主 agent 决定是否/如何对人类反馈）。
- 当开关关闭时：只在用户/agent 明确触发（例如调用 `irc pull`）才会把消息内容返回给 agent。

## Truncation（防御过长，v28 必须）

目标：任何进入主 agent 上下文的 IRC 文本都必须被限制长度，且可解释、可复现、可避免“长消息攻击”。

v28 采用的截断策略（用于 `irc pull` 的 message text，以及自动递送模式）：

- 先做单条消息的最大长度裁剪（例如 512 或 1024 字符级别的安全上限；具体上限以实现侧常量为准，并在测试断言）
- 再做整批输出的总长度上限（例如 8k/16k 级别；与 `terminal_exec` stdout/result 截断上限协同）
- 当总长度超限时：采用 “head + marker + tail” 拼接：
  - 取头部 N 条消息（或 N 字符）
  - 插入固定 marker：`[...TRUNCATED...]`
  - 取尾部 M 条消息（或 M 字符）
- `cursor` 的更新规则：
  - 非 peek 且 ok：`cursor_after` 指向“本次成功递送给 agent 的最后一条消息”
  - 被截断时：`cursor_after` 仍以“尾部最后一条”更新（避免重复递送）
  - 如果 ring buffer 溢出导致漏消息：必须在 result 中以 `dropped_count` 明示风险

## UI（v28 必须）

### Chat 页签：IRC 状态指示器

在 Chat 页签右上角最顶部（TopAppBar/Toolbar 区域）增加一个紧凑指示器：

- 文本：`IRC`（小字号）
- 状态灯：三色（红/黄/绿）之一
- 状态映射（v28）：
  - **红**：未初始化 / 断开 / 错误（not_initialized/error/disconnected）
  - **黄**：连接中 / 重连中（connecting/reconnecting）
  - **绿**：已连接且已加入默认频道（connected+joined）

指示器只展示状态，不展示 server/channel/nick（避免泄露与 UI 拥挤）。

## Acceptance（v28 文档口径）

1. `terminal_exec` 新增顶层命令 `irc`，并至少实现 `status/send/pull`。
2. 连接与频道状态必须与 **Agent session 生命周期绑定**：同一 session 内多次 `irc send` 不应重复创建新连接；session 结束必须断开并清理。
3. 凭据只能从 `.agents/skills/irc-cli/secrets/.env` 读取，不允许通过 argv 传入任何 password/key。
4. `IRC_NICK` 长度 > 9 必须返回可解释错误（`NickTooLong`）。
5. `irc send`：
   - `--to` 省略时默认向 `IRC_CHANNEL` 发送，不需要 `--confirm`；
   - `--to` 指向非默认频道或私聊时，缺 `--confirm` 必须拒绝（`ConfirmRequired`）。
6. `irc pull`：
   - 默认只返回“自上次成功 pull 以来”的新消息（去重，不重复递送）；
   - 返回内容必须经过截断策略（head/marker/tail），并在 `result.truncated` 明示。
7. Chat 页签右上角存在 `IRC` 三色灯指示器，且能反映 IRC 状态（红/黄/绿）。
8. 可选：当 `IRC_AUTO_FORWARD_TO_AGENT=1` 时，入站消息通过安全检查后自动递送给主 agent；默认关闭。
9. `.\gradlew.bat :app:testDebugUnitTest` exit code=0（覆盖：缺配置、nick 太长、confirm 门禁、连接复用、pull cursor 去重、截断策略、自动递送开关默认关闭）。

## Requirements（Req IDs）

### A. 命令面

- REQ-0028-001：新增 `irc status` 返回结构化 `result`（不泄露 secret）。
- REQ-0028-002：新增 `irc send`，支持 `--text` 与 `--text-stdin`，并满足门禁策略。
- REQ-0028-003：新增 `irc pull`，支持 cursor 去重与 `--peek`，默认返回安全摘要。
- REQ-0028-004：`irc pull` 的 cursor 必须按频道维护（每个 `--from` 各自 cursor）。

### B. 会话与连接

- REQ-0028-010：IRC 连接对象必须与 Agent session 生命周期绑定，并可复用（懒加载）。
- REQ-0028-011：断线后下一次 `irc send` 触发自动重连（至少一次重试 + 可解释错误）。
- REQ-0028-012：入站消息必须落盘（JSONL）并维持内存 ring buffer（用于 pull 与可追溯）。

### C. 安全面

- REQ-0028-020：凭据只从 `.env` 读取；argv/stdout/audit 不包含 server pass/channel key/nickserv pass。
- REQ-0028-021：`NICK` 长度限制：> 9 字符拒绝（`NickTooLong`）。
- REQ-0028-022：`irc send` 的 confirm 门禁：非默认目标缺 `--confirm` 必拒绝（`ConfirmRequired`）。
- REQ-0028-023：截断策略：`irc pull` 与自动递送模式必须执行 head/marker/tail 总长度防御，并明示 `truncated`。
- REQ-0028-024：自动递送开关：`IRC_AUTO_FORWARD_TO_AGENT=1` 才允许自动递送；默认关闭。

### D. 工程规矩

- REQ-0028-030：命令实现必须放在独立目录 `commands/irc/*`；`TerminalCommands.kt` 只做注册表。
- REQ-0028-031：Chat 页签必须展示 IRC 三色灯状态指示器（不泄露敏感信息）。

## Future Idea（留档，不实现：v28）

### 通过 IRC 远程操控手机主会话 agent（高风险）

这是一种“远程控制面板”能力，风险高（越权、社工、消息注入、泄露、误操作）。仅留档方向，后续版本若要做，必须先出独立 PRD 并至少包含：

- 发送者强认证（例如 allowlist + shared secret + challenge/response 或签名）
- 只允许在特定频道/特定 nick/特定时间窗口生效
- 命令白名单与速率限制
- 明确的“人类确认门禁”（尤其是会改动外部世界状态的命令）
- 完整审计与可回放证据链

## Open Questions（需要你确认，避免 v28 返工）

（已确认）`irc pull` cursor 按频道各自维护（每个 `--from` 各自 cursor）。
