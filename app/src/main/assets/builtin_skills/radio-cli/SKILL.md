---
name: radio-cli
description: 通过 `terminal_exec` 控制/查询电台播放器状态（radio status/play/pause/resume/stop）。
---

# radio-cli（Pseudo Radio CLI）

## Goal

为 App 内置 Radio 电台播放器提供“堆外控制面”（可审计、可测试）：用 `terminal_exec` 执行 `radio ...` 命令查询/控制播放状态。

## Commands（v33）

### Help

使用工具 `terminal_exec` 执行：

- `radio --help`
- `radio help`
- `radio play --help`
- `radio help play`

期望：
- `exit_code=0`

### 状态

使用工具 `terminal_exec` 执行：

- `radio status`

期望：
- `exit_code=0`
- `result.state` 为 `idle|playing|paused|stopped|error`
- 播放电台时 `result.station.path` 为 `workspace/radios/**.radio`

### 播放控制（仅允许 radios/ 子树）

使用工具 `terminal_exec` 执行（示例）：

- `radio play --in workspace/radios/demo.radio`
- `radio pause`
- `radio resume`
- `radio stop`

期望：
- `exit_code=0`
- `radio play` 成功后：`radio status` 的 `result.state=playing`

## Rules

- 必须实际调用 `terminal_exec`，不要臆造 stdout/result/artifacts。
- `radio play` 只允许 `workspace/radios/**.radio`；越界应返回 `exit_code!=0` 且 `error_code` 可解释（如 `NotInRadiosDir/NotRadioFile/NotFound`）。
- `terminal_exec` 不支持 `;` / `&&` / `|` / 重定向；命令必须单行。
- 若工具返回 `exit_code!=0` 或包含 `error_code`，直接报告错误并停止。

