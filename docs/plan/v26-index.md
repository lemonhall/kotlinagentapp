# v26 Index：SSH CLI（协议锁口径 / 文档闭环）

日期：2026-02-18

## Vision（引用）

- PRD：`docs/prd/PRD-0026-ssh-cli.md`
- 本轮目标（v26）：先把 SSH client 的 `ssh` 命令协议、落盘结构、输出契约与安全护栏写硬（PRD + Plan），为后续按 `paw-cli-add-workflow` 做 TDD 落地提供可追溯锚点。

> 说明：v26 M0 先不实现代码，只把协议与验收写死，避免“想到哪写到哪”。

## Milestones

### M0：文档闭环（PRD + Plan）

- PRD Trace：
  - PRD-0026：REQ-0026-001 / REQ-0026-002 / REQ-0026-003
  - PRD-0026：REQ-0026-010 / REQ-0026-011 / REQ-0026-012 / REQ-0026-013 / REQ-0026-014
- DoD（硬口径）：
  - `docs/prd/PRD-0026-ssh-cli.md` 存在且包含 Req IDs、Non-Goals、Acceptance；
  - `docs/plan/v26-ssh-cli.md` 存在且包含可执行 Steps 与 Files 规划；
  - 计划中的 DoD/Acceptance 均为可二元判定（pass/fail），无模糊词；
  - 提交与推送：`git status --porcelain=v1` 为空。
- Verify：
  - 纯文档里程碑：无需跑测试；但必须 `git commit` + `git push`。
- Plan：
  - `docs/plan/v26-ssh-cli.md`

### M1：实现闭环（命令 + 单测 + builtin skill）

- PRD Trace：
  - PRD-0026：REQ-0026-001 / REQ-0026-002 / REQ-0026-003
  - PRD-0026：REQ-0026-010 / REQ-0026-011 / REQ-0026-012 / REQ-0026-013 / REQ-0026-014
- DoD（硬口径）：
  - `terminal_exec` 新增白名单命令 `ssh`，至少支持子命令 `ssh exec`；
  - `ssh exec` 的远程命令必须来自 `stdin`（stdin 为空返回 `InvalidArgs`）；
  - unknown host 且未传 `--trust-host-key` 必须失败（`HostKeyUntrusted`）；
  - `--out <rel>` 写入 `.agents/<rel>`，且 tool output 的 `artifacts[]` 返回 `.agents/<rel>`；
  - argv 出现敏感字段（`--password`/`--passphrase`/`--key`/`SSH_PASSWORD=` 等）必须失败（`SensitiveArgv`）；
  - `--out` 路径必须在 `.agents/` 内，拒绝绝对路径与 `..`（`PathEscapesAgentsRoot` / `InvalidArgs`）；
  - builtin skill：`app/src/main/assets/builtin_skills/ssh-cli/SKILL.md` 存在且 App 初始化会安装到 `.agents/skills/ssh-cli/`；
  - secrets 模板：`.agents/skills/ssh-cli/secrets/.env` 启动后若缺失会从 `env.example` 生成（不覆盖用户已有 `.env`）。
- Verify：
  - `.\gradlew.bat :app:testDebugUnitTest`
- Plan：
  - `docs/plan/v26-ssh-cli-impl.md`

## Plan Index

- `docs/plan/v26-ssh-cli.md`
- `docs/plan/v26-ssh-cli-impl.md`

## ECN Index

- （本轮无）
