# v12 Plan：移植 `apn-pushtool` 为 App 内置伪终端命令（Kotlin）

日期：2026-02-17（计划于 2026-02-18 开始执行）

## Goal

把桌面端 `apn-pushtool` 的能力移植为 App 内置实现（Kotlin），以 `terminal_exec` 提供 `apn ...` 白名单命令，并新增内置 skill 文档，使 Agent 能按“命令式说明书”完成一次真实推送或安全失败（需显式确认）。

## PRD Trace

- PRD-0011：REQ-0011-001 / REQ-0011-002 / REQ-0011-010

## Requirements（本轮 Req IDs）

### A. 命令与协议

- REQ-0012-001：新增 `apn` 白名单命令，入口为 `terminal_exec` 的 `command="apn ..."`。
- REQ-0012-002：支持子命令：
  - `apn doctor --config <path>`
  - `apn send --config <path> --title <t> --body <b> --confirm`
  - （可选）`apn send --json` 输出结构化 JSON（stdout 仍可保留简短摘要）
- REQ-0012-003：命令参数解析严格（拒绝歧义与缺参），错误必须可解释（`error_code/error_message`）。

### B. 安全与审计

- REQ-0012-010：私钥与 token 不得出现在：
  - git 仓库文件（禁止把 `.p8`、device token 写入仓库）
  - `terminal_exec` 审计文件（落盘必须打码/不落盘）
  - tool output（返回给 LLM 的 JSON 里也不得包含全量 token/私钥）
- REQ-0012-011：真实推送必须显式确认：缺少 `--confirm` 时 `apn send` 必须拒绝（exit_code!=0，`error_code="ConfirmRequired"`）。
- REQ-0012-012：网络访问域名必须受限：只允许访问 APNs 域名（`api.push.apple.com` / `api.sandbox.push.apple.com`），其他域名拒绝（`error_code="HostNotAllowed"`）。

### C. 依赖策略（关键）

- REQ-0012-020：不得引入/执行 Python 运行时；不得通过 `ProcessBuilder` 调用外部 `apn-pushtool.exe`。
- REQ-0012-021：APNs 推送由 Kotlin 实现：
  - HTTP/2：OkHttp（Android）
  - JWT ES256：`java.security`（P-256 ECDSA）
  - `.p8`：PKCS8 PEM 解析为 EC 私钥

## Scope

做：
- `terminal_exec` 注册 `apn` 命令（Kotlin 内置）
- 实现 `doctor`（配置校验 + mask 输出）
- 实现 `send`（显式确认 + 真实推送 + 结构化结果）
- 新增内置 skill：`builtin_skills/apn-pushtool/SKILL.md`（命令式说明书）

不做（v12 不包含）：
- 不做 UI 配置页（先用 `.agents/*` 文件落盘配置，后续再做 Settings）
- 不做批量/长文本拆分（`send-long`）与 legacy 迁移（`init-from-legacy`）
- 不做 iOS 侧实现（本轮仅 Android）

## Config 约定（App 私有工作区）

> 仅建议约定，具体落盘路径由实现锁定。

- `.agents/secrets/apns.json`（不入 git）
- `.agents/secrets/apns_authkey.p8`（不入 git）

`apns.json` 最小字段（示例）：
- `env`: `production|sandbox`
- `team_id`
- `key_id`
- `bundle_id`（topic）
- `device_token`
- `p8_path`（相对 `.agents` 路径，例如 `secrets/apns_authkey.p8`）

## Tool Output（结构化）

`apn doctor` 返回（示例字段）：
- `ok: boolean`
- `env/topic`
- `team_id_masked/key_id_masked/device_token_masked`
- `has_p8: boolean`
- `errors: string[]`

`apn send` 返回（示例字段）：
- `ok: boolean`
- `status_code: number`
- `apns_id: string?`
- `reason: string?`（失败时）
- `device_token_masked: string`

## Acceptance（硬口径）

1. `apn doctor`：
   - config 缺字段 → `exit_code!=0` 且 `error_code="InvalidConfig"`
   - config 完整 → `exit_code=0` 且输出包含 mask 后的 bundle_id + token（不得全量）
2. `apn send`：
   - 未给 `--confirm` → 必拒绝（`ConfirmRequired`）
   - 给 `--confirm` 且推送成功 → `exit_code=0` 且返回 `apns_id`
3. 审计落盘检查：
   - `.agents/artifacts/terminal_exec/runs/*.json` 中不得出现 `.p8` 内容与 device token 全量（允许 mask）
4. `.\gradlew.bat :app:testDebugUnitTest` exit code=0

## Files（预期变更）

- `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalCommands.kt`（注册 `apn`）
- `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalExecTool.kt`（必要时：能力/域名 allowlist 钩子）
- `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/apns/ApnsClient.kt`（OkHttp + JWT + send）
- `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/apns/ApnsConfig.kt`（config 读取 + mask）
- `app/src/main/assets/builtin_skills/apn-pushtool/SKILL.md`（内置 skill 文档）
- `app/src/test/java/com/lsl/kotlin_agent_app/agent/tools/apns/ApnsJwtTest.kt`
- `app/src/test/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalApnCommandTest.kt`

## Steps（Strict）

1) Analysis：锁定 config 文件位置与字段；补齐“审计打码”策略（不得记录 secret）
2) TDD Red：先写测试
   - config 缺字段报错
   - doctor 输出不含全量 token
   - send 未确认必须拒绝
3) TDD Green：实现 ApnsClient（JWT + HTTP/2）与 apn 命令路由
4) Refactor：将“mask/审计/域名 allowlist”抽为可复用函数
5) Verify：`.\gradlew.bat :app:testDebugUnitTest`
6) E2E（手动最小验收）：真机执行一次 `apn send --confirm` 并收到推送（需用户允许）

## Risks

- PRD-0011 写“terminal 默认无网络”与 APNs 推送冲突：执行时如需变更口径，必须走 ECN 并在 PRD 中落痕。
- HTTP/2 与 APNs 证书链/系统时间/代理可能导致失败：需在 doctor 输出中给出可诊断信息（但不泄露 secret）。

