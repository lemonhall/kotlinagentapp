# v26 Plan：SSH CLI（远程 exec / 文档锁口径）

## Goal

把 SSH client 的“远程命令执行”能力用 `terminal_exec` 的白名单命令 `ssh` 表达出来，并把落盘结构、输出契约与安全护栏写死在验收口径里；v26 M0 **只落文档，不写实现**。

## PRD Trace

- PRD-0026：REQ-0026-001 / REQ-0026-002 / REQ-0026-003
- PRD-0026：REQ-0026-010 / REQ-0026-011 / REQ-0026-012 / REQ-0026-013 / REQ-0026-014

## Scope

做（v26：文档阶段）：

- 锁定命令集合与 argv 约定（`ssh exec`）
- 锁定“远程命令必须走 stdin”的协议（规避 `terminal_exec` banned tokens）
- 锁定工作区落盘结构（`.agents/workspace/ssh/*` 与 `.agents/artifacts/ssh/*`）
- 锁定输出契约（stdout/result/artifacts/exit_code/error_code）
- 锁定 guardrails：
  - known_hosts 强制校验；未知 host 必须显式 `--trust-host-key`（TOFU 护栏）
  - 大输出必须 `--out` 落盘 + artifacts 引用
  - `--out` 路径必须受 `.agents` 根目录约束（拒绝绝对路径与 `..`）
  - secrets 禁止通过 argv 传入（`SensitiveArgv`），只能读 `.agents/skills/ssh-cli/secrets/.env`

不做（v26）：

- 任何代码实现（命令、单测、builtin skill 安装）
- 交互式 shell / 端口转发 / SFTP
- 代理链路（ProxyCommand / 外部命令）

## Acceptance（硬口径，面向后续实现）

1. `ssh exec`：远程命令必须来自 `stdin`；stdin 为空必须失败（`InvalidArgs`）。  
2. `ssh exec`：unknown host 且未传 `--trust-host-key` 必须失败（`HostKeyUntrusted`）。  
3. `ssh exec --out X`：落盘 `.agents/<X>`，且 tool output 的 `artifacts[]` 返回 `.agents/<X>`。  
4. secrets：argv 出现 `--password`/`--passphrase`/`--key`/`SSH_PASSWORD=` 等必须失败（`SensitiveArgv`）。  
5. 路径约束：所有 `--out` 必须在 `.agents/` 内，拒绝绝对路径与 `..`（`PathEscapesAgentsRoot` / `InvalidArgs`）。  
6. 文档验证：`docs/prd/PRD-0026-ssh-cli.md` 与本计划文档存在且可追溯。  

反作弊条款（必须）：

- 禁止“只注册命令但返回假数据”就宣称完成：后续单测必须验证 known_hosts 落盘、远程命令确实走 stdin、并能用 fake transport 覆盖 `HostKeyUntrusted/AuthFailed/NetworkError/RemoteNonZeroExit`。

## Files（规划：遵守 paw-cli-add-workflow；v26 不落地代码）

> 下列为后续实现阶段预计会改动/新增的路径清单（v26 M0 只写计划，不实际创建）。

- 注册表（只注册，不写实现）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalCommands.kt`
- 命令实现（拆分目录）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/ssh/SshCommand.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/ssh/*`（client + known_hosts + 模型 + secrets）
- 单测（Robolectric）：
  - `app/src/test/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalExecToolTest.kt`
- 内置 skill（让 Agent 能按说明书调用）：
  - `app/src/main/assets/builtin_skills/ssh-cli/SKILL.md`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/AgentsWorkspace.kt`
- secrets 模板（参考 stock/qqmail 模式）：
  - `app/src/main/assets/builtin_skills/ssh-cli/secrets/env.example`

## Steps（Strict）

> v26 M0 只做文档闭环，因此步骤只覆盖 Analysis/Design/Plan + Gate，不进入实现。

1) Analysis：确定 SSH exec 的输入边界（stdin/argv）与失败场景（unknown host / auth failed / timeout / remote nonzero）  
2) Design：锁定命令协议、错误码与输出契约（何时必须 `--out`）  
3) Plan：写清 Files / 验收口径 / 未来测试点（SensitiveArgv/HostKeyUntrusted/AuthFailed/NetworkError/RemoteNonZeroExit/路径越界）  
4) DoD Gate：检查 Acceptance 全部可二元判定且有明确验证命令/断言口径  
5) Doc QA Gate：术语一致（ssh/exec/known_hosts/trust/out/artifacts/workspace），Req IDs 追溯不断链  
6) Ship（文档）：`git add -A ; git commit -m "v26: doc: ssh cli prd" ; git push`  

## Risks

- `terminal_exec` banned tokens 约束：必须强制 stdin 承载远程命令，否则真实可用性会被锁死。  
- Android 算法/私钥格式兼容性：依赖库选择可能需要 ECN 调整。  

