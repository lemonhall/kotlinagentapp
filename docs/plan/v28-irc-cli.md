# v28 Plan：IRC CLI（会话绑定长连接 + 收发 + 指示器）

## Goal

实现 PRD-0028 的 `irc` 白名单命令最小闭环（`status/send/pull`），并把“连接复用、NICK<=9、confirm 门禁、pull cursor 去重、截断策略、secrets 只读 .env、Chat 顶部 IRC 三色灯指示器”写死到单测与验收口径中，避免“能连上但不可控/不可回归”的假交付。

## PRD Trace

- PRD-0028：REQ-0028-001 / REQ-0028-002
- PRD-0028：REQ-0028-003
- PRD-0028：REQ-0028-010 / REQ-0028-011 / REQ-0028-012
- PRD-0028：REQ-0028-020 / REQ-0028-021 / REQ-0028-022 / REQ-0028-023 / REQ-0028-024
- PRD-0028：REQ-0028-030 / REQ-0028-031

## Scope

做：

- 新增命令：
  - `irc status`
  - `irc send`
  - `irc pull`
- `.env` 凭据读取（仅本地）：
  - 默认读取 `.agents/skills/irc-cli/secrets/.env`
- 会话绑定：
  - 连接与频道状态绑定到 Agent session，可复用且懒加载
  - 入站消息 ring buffer + JSONL 落盘（用于 pull 与追溯）
- 安全策略（必须）：
  - `IRC_NICK` > 9 拒绝
  - 禁止 argv 传入任何 password/key
  - `irc send` 非默认目标强制 `--confirm`
  - `irc pull` 与（可选）自动递送执行截断策略（head/marker/tail）
  - `irc pull` cursor 去重按频道维护（每个 `--from` 各自 cursor）
  - Chat 顶部 IRC 三色灯指示器（红/黄/绿）

不做（v28）：

- 多 server/多连接、复杂管理命令、DCC/文件传输
- `--out` 调试日志落盘（v29+ 再补）
- 通过 IRC 远程操控手机主会话 agent（仅留档，未来版本再做）

## Acceptance（硬口径）

见 `docs/prd/PRD-0028-irc-cli.md` 的 Acceptance。

## Files（规划：遵守 paw-cli-add-workflow）

- 注册表（只注册，不写实现）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalCommands.kt`
- 命令实现（拆分目录）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/irc/IrcCommand.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/irc/*`（可选：IRC runtime + client adapter + .env loader）
- Chat UI（指示器）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/MainActivity.kt`（如 TopAppBar 在此管理）
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/chat/*`（以实际 Chat 代码位置为准）
  - `app/src/main/res/*`（必要的 layout/compose 资源）
- 单测（Robolectric）：
  - `app/src/test/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalExecToolTest.kt`
  - `app/src/test/java/com/lsl/kotlin_agent_app/agent/tools/irc/*Test.kt`（如需要更细）
- 内置 skill（让 Agent 能按说明书调用）：
  - `app/src/main/assets/builtin_skills/irc-cli/SKILL.md`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/AgentsWorkspace.kt`

## Steps（Strict / TDD）

1) Analysis：锁定 `.env` 读取位置 + session 绑定策略（如何在测试里验证“复用连接”）  
2) TDD Red：为 nick<=9、confirm 门禁、缺配置、连接复用、pull cursor 去重、截断策略写测试并跑红  
3) TDD Green：实现 `irc status/send/pull`（含 IRC runtime 管理器 + 入站消息缓冲/落盘）并跑绿  
4) UI：Chat 顶部增加 `IRC` 三色灯指示器（红/黄/绿），并加可测的状态映射点  
5) Refactor：抽取 `.env` loader / session runtime cache / truncation 工具函数，保持命令文件职责清晰  
6) 接入：注册命令 + 安装内置 skill（含“IRC 有新消息”触发 pull 的说明）  
7) Verify：`.\gradlew.bat :app:testDebugUnitTest`  

## Risks

- Android 兼容：选定 IRC 库可能引入不适配依赖（或方法数/体积风险）→ 需要 adapter + 可替换实现。
- 可测性：真实 IRC 网络不适合单测直连 → 需要 client 抽象 + fake，保证 Robolectric 可跑且能验证“复用连接”。
- 网络环境：端口/证书/代理限制 → 错误必须可解释，并给出用户侧排障提示。
