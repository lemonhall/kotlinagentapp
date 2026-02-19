# v36 Index：Files 页签挂载局域网 NAS（SMB，nas_smb/ VFS）

日期：2026-02-19

## Vision（引用）

- PRD：`docs/prd/PRD-0033-files-nas-smb-mount.md`
- 方法论引用：`tashan-development-loop`（PRD ↔ plan ↔ tests ↔ code 可追溯）、`paw-cli-add-workflow`（本轮不新增 terminal_exec 命令，但复用其 secrets/.env 纪律与“可审计/可测试”的工程约束）
- 本轮目标（v36）：在 `.agents/` 根目录新增 `nas_smb/`，通过 App 内部 VFS 把 SMB 共享“挂载”为目录树，让 Files 页签与 Agent 文件工具对其进行 LIST/READ/NEW/EDIT/MKDIR/DELETE。

## Milestones

### M1：nas_smb/ 目录 + 多挂载点 `.env` + VFS 路由最小闭环

- PRD Trace：
  - PRD-0033：REQ-0033-001 / REQ-0033-002 / REQ-0033-003
  - PRD-0033：REQ-0033-010 / REQ-0033-011
  - PRD-0033：REQ-0033-020 ~ REQ-0033-022
  - PRD-0033：REQ-0033-030 ~ REQ-0033-032
- DoD（硬口径）：
  - `.agents/nas_smb/` 与 `.agents/nas_smb/secrets/.env` 初始化可见，`.env` 缺失时会从模板生成但不覆盖用户 secrets；
  - `.env` 支持至少 2 个挂载点，且 mount 映射为 `.agents/nas_smb/<mount_name>/`；
  - `.agents/nas_smb/<mount_name>/**` 下的 LIST/READ/WRITE/MKDIR/DELETE 路由到 SMB 远端；
  - 稳定错误码：`Timeout` / `AuthFailed` / `ShareNotFound` / `PermissionDenied` / `HostUnreachable`（不得泄露 secrets）；
  - Verify：`.\gradlew.bat :app:testDebugUnitTest` exit code=0（含 Fake SMB 运行时测试，避免依赖真实 NAS）。

## Plan Index

- `docs/plan/v36-files-nas-smb-mount.md`

## ECN Index

- （本轮无）

## Review（Evidence）

- 2026-02-19：Unit tests：`.\gradlew.bat :app:testDebugUnitTest` ✅（exit code=0）
