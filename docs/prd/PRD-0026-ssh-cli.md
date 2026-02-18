#!/usr/bin/env markdown
# PRD-0026：SSH Client（远程命令执行 / SSH CLI / Pseudo Terminal Commands）

日期：2026-02-18  
定位：在 `terminal_exec` 的“白名单 pseudo CLI（无 shell / 无外部进程）”框架下，提供一个**仅客户端**的 SSH 能力（`ssh` CLI），用于 Agent/用户在 App 内对远程 Linux 主机执行命令，并以“摘要（stdout/result）+ 大输出落盘（artifacts）”的方式稳定消费结果；本 PRD 先锁口径（协议、数据模型、输出契约、落盘与护栏），实现按 v26 计划另行落地。

## Vision

让用户与 Agent 都可以在 App 内仅通过 `terminal_exec`：

- 使用 `ssh exec` 对远程主机执行一次性命令（非交互式）
- 具备明确的 host key 验证与“首次信任（trust on first use, TOFU）”护栏
- 不把敏感信息写入 audit（`terminal_exec` 审计文件）或 stdout/stderr（尽可能）
- 以 artifacts 方式落盘完整输出，避免 stdout/stderr 截断导致误判

并且：

- **无外部进程**：仅 App 内置 Kotlin + 纯 Java SSH 客户端库
- **可审计**：每次调用由 `terminal_exec` 统一审计落盘（工具已自带）
- **可回归**：协议/错误码/落盘结构写死，后续按 `paw-cli-add-workflow` 做 TDD 落地

## Background

远程运维/排障/拉日志/执行脚本是 Agent 自动化的高频需求。SSH 是最通用的远程通道，但对移动端 App 来说存在如下现实约束：

- `terminal_exec` 明确禁止 shell-like token（`;`、`|`、`>` 等），因此“远程命令字符串”**不能**直接写在 pseudo CLI 的 command 行里，否则会被拒绝
- `terminal_exec` 的 audit 会落盘 stdout/stderr（且不写 stdin），因此更适合把敏感/复杂内容通过 `stdin` 传入
- Android 环境中不允许启动系统 shell / OpenSSH 进程（上架风险、可控性差），必须用内置库实现

## Non-Goals（v26）

- 不做：交互式 shell（`ssh shell` / PTY）
- 不做：端口转发（local/remote/dynamic）
- 不做：SFTP/scp（后续可另开 PRD）
- 不做：复杂代理链路（例如 ProxyCommand / 外部命令）；后续可考虑内置 SOCKS5（视需求）
- 不做：known_hosts 的复杂管理 UI（仅工作区文件）

## Dependencies（实现建议，后续可由 ECN 调整）

### SSH 客户端库

实现建议选用：

- `com.github.mwiede:jsch`（JSch 维护分支）：纯 Java、体量可控、Android 兼容性较好；支持 exec channel、host key、私钥认证等

> 说明：如后续验证发现 JSch 在 Android 的算法/密钥支持不足，可通过 ECN 切换为 SSHJ 或 Apache MINA SSHD，但必须保持本 PRD 的命令协议与错误码稳定。

## Project Rules（实现必须遵守：来自 paw-cli-add-workflow）

- `TerminalCommands.kt` 只能做“命令注册表/默认 registry 构建”，禁止堆实现逻辑。
- 顶层命令必须独立目录：
  - `ssh`：`app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/ssh/*`
- `TerminalExecTool` 的 registry 初始化逻辑不改；只在 `TerminalCommands.defaultRegistry(...)` 注册新增命令。

## Workspace（落盘结构）

默认工作区（实现必须使用固定结构）：

- `.agents/workspace/ssh/known_hosts`：known_hosts（TOFU/校验用；格式可为库原生或文本，要求可增量写入）
- `.agents/artifacts/ssh/exec/*.json`：`--out` 的结构化产物（包含完整 stdout/stderr 或引用）
- `.agents/artifacts/ssh/exec/*.stdout.txt`：完整 stdout（可选；当输出很大时建议拆文件）
- `.agents/artifacts/ssh/exec/*.stderr.txt`：完整 stderr（可选）

> 约束：所有 `--out` 路径必须在 `.agents/` 内；拒绝绝对路径与 `..`（`PathEscapesAgentsRoot` / `InvalidArgs`）。

## Secrets（本地 .env）

敏感信息只允许从以下路径读取：

- `.agents/skills/ssh-cli/secrets/.env`

`.env`（建议字段；实现可按需增减，但不得要求把 secret 放到 argv）：

```dotenv
# 认证（二选一）
SSH_PASSWORD=
SSH_PRIVATE_KEY_PATH=skills/ssh-cli/secrets/id_ed25519
SSH_PRIVATE_KEY_PASSPHRASE=

# host key 存储（可选，默认 workspace/ssh/known_hosts）
SSH_KNOWN_HOSTS_PATH=workspace/ssh/known_hosts
```

安全规则：

- 禁止在 argv 里出现 `--password`/`--passphrase`/`--key`/`--secret` 等敏感 flag（返回 `SensitiveArgv`）
- 禁止在 argv 里出现形如 `SSH_PASSWORD=...` 的敏感键值（返回 `SensitiveArgv`）

## Command Set（v26 目标命令协议）

统一输出契约：

- `exit_code`：0 成功；非 0 失败
- `stdout`：人类可读摘要（不超过 16k）
- `result`：结构化 JSON（稳定字段）
- `artifacts[]`：大输出落盘引用（path/mime/description）
- 失败必须提供：`error_code` + `error_message`

统一 guardrails：

- remote command 必须走 `stdin`（见下文），避免触发 `terminal_exec` 的 banned tokens
- 默认超时（实现建议 15s，可通过 `--timeout-ms` 调整并上限）
- 大输出必须走 `--out` 落盘；stdout/result 只给摘要
- known_hosts 强制校验：未知 host 必须显式 `--trust-host-key` 才允许写入（TOFU）

### 1) `ssh exec`（一次性远程命令执行）

命令行（单行）：

- `ssh exec --host 192.0.2.10 --port 22 --user root --trust-host-key --out artifacts/ssh/exec/last.json`

stdin（必须）：

- `stdin` 内容为远程命令文本（允许包含 `;`、`|`、`>` 等），例如：
  - `uname -a ; id ; df -h`

行为：

- 使用 `.agents/workspace/ssh/known_hosts`（或 `.env` 覆盖）进行 host key 校验
- 若 host 未被信任：
  - 无 `--trust-host-key`：失败 `HostKeyUntrusted`
  - 有 `--trust-host-key`：写入 known_hosts 后继续
- 认证方式：
  - 若 `.env` 中存在 `SSH_PRIVATE_KEY_PATH` 则优先使用私钥；否则用 `SSH_PASSWORD`
- 远程 exit status 非 0：
  - 视为失败：`exit_code=1`，`error_code="RemoteNonZeroExit"`，并返回 `result.remote_exit_status`

result（最小字段）：

- `ok`
- `command: "ssh exec"`
- `host` / `port` / `user`
- `remote_exit_status`（若可获取）
- `duration_ms`
- `stdout_preview` / `stderr_preview`（截断预览）
- `out`（若传入）

## Error Codes（统一口径）

- `InvalidArgs`：参数缺失/格式错误/stdin 为空
- `SensitiveArgv`：argv 出现敏感 flag 或敏感键值
- `MissingCredentials`：`.env` 缺少可用认证信息
- `HostKeyUntrusted`：known_hosts 中不存在且未传 `--trust-host-key`
- `HostKeyMismatch`：known_hosts 已存在但指纹不匹配
- `AuthFailed`：认证失败
- `NetworkError`：网络/连接/超时
- `RemoteNonZeroExit`：远程命令 exit status != 0
- `OutRequired`：该子命令要求 `--out`（如未来加入强制落盘的模式）
- `PathEscapesAgentsRoot`：`--out` 路径越界（含 `..` 或绝对路径）

## Acceptance（v26 交付口径：文档阶段）

> v26 M0 为文档阶段：锁定协议与验收口径；不写实现代码。

1. `docs/prd/PRD-0026-ssh-cli.md` 存在，且包含 Req IDs、Non-Goals、Acceptance、Workspace、Secrets、Command Set、Error Codes。  
2. `docs/plan/v26-ssh-cli.md` 与 `docs/plan/v26-index.md` 存在，且包含可执行 Steps 与 Files 规划。  
3. 文档中的 DoD/Acceptance 全部可二元判定（pass/fail），无模糊词。  
4. 提交与推送：`git status --porcelain=v1` 为空。  

## Requirements（Req IDs）

### A. 命令面（远程 exec）

- REQ-0026-001：新增 `ssh exec`，通过 `stdin` 接收远程命令文本并执行（argv 不包含远程命令）。
- REQ-0026-002：`ssh exec` 支持 `--out` 落盘完整 stdout/stderr 与结构化结果，并在 `artifacts[]` 返回 `.agents/<out_path>`。
- REQ-0026-003：远程 exit status != 0 必须可被 Agent 稳定判定（`RemoteNonZeroExit` + `remote_exit_status`）。

### B. 安全/工程

- REQ-0026-010：实现必须放在独立目录 `commands/ssh/*`；`TerminalCommands.kt` 只做注册表。
- REQ-0026-011：known_hosts 必须默认落盘到 `.agents/workspace/ssh/known_hosts`，且未知 host 必须显式 `--trust-host-key` 才可写入（TOFU 护栏）。
- REQ-0026-012：敏感信息不得通过 argv 传入（`SensitiveArgv`），认证信息必须来自 `.agents/skills/ssh-cli/secrets/.env`。
- REQ-0026-013：所有 `--out` 路径必须在 `.agents/` 内；拒绝绝对路径与 `..`（`PathEscapesAgentsRoot` / `InvalidArgs`）。
- REQ-0026-014：错误码必须可解释且稳定（见 Error Codes）。

## Risks

- Android 兼容性：SSH 库的加密算法/私钥格式支持不一致 → 以 ECN 调整依赖，但保持 CLI 协议稳定。
- 输出过大截断：远程命令可能输出大量日志 → 默认摘要 + `--out` 落盘必须好用。
- Host key 安全：TOFU 可能被首次 MITM → 必须强制 `--trust-host-key` 显式确认，且 result 返回指纹（供用户核对）。

