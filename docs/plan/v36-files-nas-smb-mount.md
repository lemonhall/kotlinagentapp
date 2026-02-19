# v36 Plan：Files 页签挂载局域网 NAS（SMB，nas_smb/ VFS）

日期：2026-02-19  
PRD：`docs/prd/PRD-0033-files-nas-smb-mount.md`

## Goal

交付一个“对用户/Agent 来说就是目录”的 SMB 挂载最小闭环：在 `.agents/` 根目录出现 `nas_smb/`，配置 `.agents/nas_smb/secrets/.env` 后可挂载多个远端目录，并且现有 file 工具在该子树内保持兼容。

## PRD Trace

- PRD-0033：REQ-0033-001 / REQ-0033-002 / REQ-0033-003
- PRD-0033：REQ-0033-010 / REQ-0033-011
- PRD-0033：REQ-0033-020 ~ REQ-0033-022
- PRD-0033：REQ-0033-030 ~ REQ-0033-032

## Scope

### In Scope（v36）

- 初始化 `nas_smb/` 目录与 secrets `.env` 模板  
- 解析 `.env` 的多 mount 协议，并生成挂载点占位目录与 `.mount.json`  
- 为 `.agents/nas_smb/<mount_name>/**` 提供 VFS 路由：list/read/write/mkdir/delete  
- 用 Fake SMB Runtime 做单测闭环（不依赖真实 NAS）

### Out of Scope（明确不做）

- 系统级挂载、SAF provider  
- 局域网发现/扫描 NAS  
- 远端文件的离线缓存/全量同步  
- SMB1 强兼容与降级策略（若要做，走 ECN）

## Acceptance（可测试口径）

1) `AgentsWorkspace.ensureInitialized()` 后，`.agents/nas_smb/secrets/.env` 存在（不存在时自动从模板生成）。  
2) 给定 `.env` 里 `NAS_SMB_MOUNTS=home,work`，Files 列出 `.agents/nas_smb/home/` 与 `.agents/nas_smb/work/` 两个挂载点目录。  
3) 对 `.agents/nas_smb/home/test.txt`：
   - 写入 `"hello"` → 读取结果为 `"hello"`；  
4) 对 `.agents/nas_smb/home/subdir/`：
   - mkdir 后 list 可见；  
5) 错误口径：
   - auth 失败返回 `AuthFailed`；超时返回 `Timeout`；share 不存在返回 `ShareNotFound`；  
6) Verify：`.\gradlew.bat :app:testDebugUnitTest` exit code=0。  

## Files（预计改动清单）

> 本清单是实现期的“锚点”；如果实现中发现需要新增/调整文件，必须同步更新本计划文档。

- `app/src/main/java/com/lsl/kotlin_agent_app/agent/AgentsWorkspace.kt`（初始化 `nas_smb/` 与 `.env` 模板；listDir/read/write/mkdir/delete 的 VFS 路由入口）
- `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/mail/DotEnv.kt`（复用或抽到更通用位置，避免重复解析器）
- `app/src/main/java/com/lsl/kotlin_agent_app/agent/vfs/nas_smb/*`（新增：配置解析、mount 映射、SMB 客户端封装、错误码）
- `app/src/main/assets/builtin_nas_smb/secrets/env.example`（新增：`.env` 模板，拷贝为 `.agents/nas_smb/secrets/.env`）
- `app/src/test/java/com/lsl/kotlin_agent_app/agent/NasSmbVfsTest.kt`（新增：Fake SMB Runtime + VFS 路由单测）

## Steps（严格按顺序，TDD）

1) **RED：dotenv 多 mount 解析测试**
   - 写单测：给定 `.env` 文本，解析出 2 个 mount（含默认值：port=445、remote_dir=/、mount_name 默认等）
   - 运行：`.\gradlew.bat :app:testDebugUnitTest`（预期失败：缺实现）

2) **GREEN：实现 `NasSmbMountConfigLoader`**
   - 复用 `DotEnv.load(...)`（或在不破坏现有调用者的前提下抽通用解析）
   - 强制字段校验与稳定错误码（MissingCredentials/InvalidConfig 等）

3) **RED：VFS 路由测试（list/read/write/mkdir/delete）**
   - 用 Fake SMB Runtime（内存树）实现一个 `SmbClient` 接口桩
   - 写单测：对 `.agents/nas_smb/home/**` 的操作必须命中 Fake SMB，而不是本地文件系统

4) **GREEN：接入 `AgentsWorkspace` 路由**
   - 识别 `.agents/nas_smb/<mount_name>/` 子树
   - 对该子树下的 list/read/write/mkdir/delete 转发到 SMB 实现
   - 错误码与 message 打码（不得泄露 host/username/password）

5) **初始化：生成 `.env` 模板 + 挂载点占位目录**
   - `AgentsWorkspace.ensureInitialized()` 创建 `.agents/nas_smb/` 与 `secrets/`
   - `.agents/nas_smb/secrets/.env` 缺失时从 assets `env.example` 生成（不覆盖）
   - 解析 `.env` 后确保每个挂载点目录存在，并写入 `.mount.json`（不含 secrets）

6) **Verify**
   - `.\gradlew.bat :app:testDebugUnitTest` exit code=0
   - 在 `docs/plan/v36-index.md` 的 Review 段补齐证据（命令 + exit code）

## Risks & Mitigations

- 风险：把 SMB 逻辑写进 UI/Fragment 导致耦合与不可测  
  - 缓解：必须抽出 `agent/vfs/nas_smb`，并由单测锁死路由行为  
- 风险：错误信息泄露 secrets  
  - 缓解：错误码稳定化 + message 统一打码 + 单测覆盖“错误不含敏感字段”  

