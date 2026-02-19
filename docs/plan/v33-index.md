# v33 Index：Radio CLI 控制面（terminal_exec radio）+ 可选收听行为日志（需用户同意）

日期：2026-02-19

## Vision（引用）

- PRD：`docs/prd/PRD-0030-radio-player.md`
- 方法论引用：`paw-cli-add-workflow`（白名单 pseudo CLI：无 shell / 可审计落盘 / help 必须可用 / TDD）
- 本轮目标（v33）：在 v32 电台播放器运行时基础上，为 `terminal_exec` 增加顶层命令 `radio`，让 agent/人类能用 CLI 查询/控制电台播放状态；并引入“可选收听行为日志”能力（默认关闭、需用户明确同意、可一键清空且需二次确认），为后续推荐/分析留出基础设施与口径。

## Milestones

### M1：`terminal_exec radio` 最小闭环（status/play/pause/resume/stop + help）

- PRD Trace：
  - PRD-0030：REQ-0030-050 / REQ-0030-051 / REQ-0030-052
- DoD（硬口径）：
  - `TerminalCommands.defaultRegistry(...)` 注册 `radio` 顶层命令；
  - `radio --help` / `radio help` / `radio <sub> --help` / `radio help <sub>` 全部可用，且 `exit_code=0`；
  - 子命令至少支持：`radio status`、`radio play --in "<agents-path>"`、`radio pause|resume|stop`；
  - `radio play` 输入必须受控：只允许播放 `.agents/workspace/radios/**` 下的 `.radio` 文件；越界一律拒绝并返回稳定 `errorCode`；
  - stdout 短摘要 + result 结构化 JSON +（必要时）artifacts 引用，遵守 paw-cli-add-workflow；
  - Verify：`.\gradlew.bat :app:testDebugUnitTest` exit code=0（覆盖：help + 成功/失败路径）。

### M2：收听行为日志（默认关闭 + 同意开关 + 清空二次确认）

- PRD Trace：
  - PRD-0030：REQ-0030-060 / REQ-0030-061 / REQ-0030-062
- DoD（硬口径）：
  - 日志默认关闭：未开启时不得写入可还原个人偏好的收听事件；
  - UI 提供明确开关与说明（包含日志落盘路径）；开启需用户明确同意；
  - 开启后按 schema 写 JSONL（最小化字段），并保证不回显敏感信息（尤其是 URL query）；
  - 提供“一键清空”入口，清空前必须二次确认；
  - Verify：`.\gradlew.bat :app:testDebugUnitTest` exit code=0（覆盖：开关行为 + 清空门禁）。

## Plan Index

- `docs/plan/v33-radio-cli-and-listening-logs.md`

## ECN Index

- （本轮无）

## Review（Evidence）

- Unit tests：`.\gradlew.bat :app:testDebugUnitTest` ✅（2026-02-19）
- Install debug（smoke）：`.\gradlew.bat :app:installDebug` ✅（2026-02-19，installed on 1 device）
