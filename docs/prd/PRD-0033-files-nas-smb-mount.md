#!/usr/bin/env markdown
# PRD-0033：Files 页签挂载局域网 NAS（SMB，nas_smb/ VFS）

日期：2026-02-19  
定位：在 Files 页签把局域网 NAS 的 SMB 共享“挂载”为 App 内 `.agents` 工作区的一个目录子树：对用户/Agent 来说它仍然是一个目录（可 LIST/READ/NEW/EDIT/MKDIR/DELETE），但底层读写通过 SMB 协议发生在远端；并支持在同一个 `.env` 里配置多个 SMB 主机/共享/挂载点。

## Vision

用户能在 App 内完成以下闭环（不依赖 adb / 不依赖电脑 / 不需要 root）：

1) `.agents/` 根目录下新增 `nas_smb/`（**严格小写**）并在 Files 页签可见，且与 `sessions/`、`skills/` 同级  
2) 进入 `nas_smb/` 后可见 `secrets/` 目录与若干“挂载点目录”（每个挂载点对应一台 NAS 的某个 share + 目标目录）  
3) 挂载点目录内的任何文件操作（LIST/READ/NEW/EDIT/MKDIR/DELETE）都作用于远端 SMB 目录，并且对 Agent 的 file 工具保持兼容  
4) 同一个 `.env` 支持配置多个挂载点（多台 NAS / 多个 share / 不同 target 目录 / 不同本地名称），并且可热更新（用户编辑 `.env` 后回到目录刷新即可生效）

## 背景与现实约束

- Android **非 root** 场景下无法做真正的“系统级挂载”（内核层 mount，让所有 App 看到一个系统路径）。本 PRD 的“挂载”定义为：**App 内部 VFS（Virtual File System）挂载**——在本 App 的 `.agents` 工作区里呈现为目录树，但实际 I/O 走 SMB。
- 本项目已有“特殊目录子树启用额外能力”的范式（例如 `.agents/workspace/musics/`、`.agents/workspace/radios/`），本 PRD沿用“把能力绑定到目录边界”的策略。

## 目标用户

- 柠檬叔：在局域网里有 NAS（SMB），希望把 NAS 目录“变成 Files 里一个目录”，让 Agent 的文件工具直接对 NAS 读写（例如读日志、写配置、批量生成文件）。

## Non-Goals（本期不做）

- 不做：系统级挂载/SAF Provider（让其他 App 也能浏览 SMB）  
- 不做：局域网自动发现 NAS（mDNS/NetBIOS 扫描）  
- 不做：复杂权限管理/多用户隔离（按 `.env` 配置为准）  
- 不做：离线缓存/全量镜像同步（不把远端目录“同步成一份本地副本”）  
- 不做：SMB1 强兼容（默认面向 SMB2/SMB3；SMB1 仅在确认安全风险后再评估）  

## 方案选择（库与实现方式）

### SMB 库候选

- 候选 A：SMBJ（Java/Kotlin，可走 SMB2/SMB3，主流、无 JNI）——**推荐**
- 候选 B：jcifs-ng（Java，历史更久，兼容面更广，但协议/加密特性与 Android 兼容性需额外验证）
- 其他：基于 C 库的 JNI（libsmb2 等）——本项目不优先（维护成本与上架风险较高）

本 PRD 默认采用 **SMBJ** 作为实现库；若遇到 Android 兼容性问题，再通过 ECN 切换为 jcifs-ng。

### VFS 映射方式（推荐：纯远端 I/O + 本地挂载点占位）

目标是让“对 Agent 来说还是目录”，但避免把远端文件同步到本地。

- `.agents/nas_smb/`：真实目录（本地）  
  - `secrets/`：真实目录（本地，用户可在 Files 里编辑）  
  - `<mount_name>/`：真实目录（本地占位，空目录 + marker 文件），进入后通过 VFS 路由到远端（list/read/write 等都走 SMB）

占位目录仅用于：
- 让 Files 页签“能导航进去”（目录必须存在）  
- 提供一个稳定锚点（marker 文件可做 mount metadata 与版本迁移）  

真正的文件内容与目录结构不在本地落盘（除非用户显式导出/复制）。

## Workspace & 目录结构（Locked）

```
.agents/
  nas_smb/
    secrets/
      .env              # 多挂载点配置（真实文件，用户编辑）
    <mount_name_1>/     # 挂载点占位目录（真实目录）
      .mount.json       # 非敏感 metadata（真实文件）
    <mount_name_2>/
      .mount.json
```

约束：
- `nas_smb/`、`secrets/` 固定小写
- `<mount_name>` 必须是安全文件名（建议：`[a-z0-9][a-z0-9_-]{0,31}`），避免空格与特殊字符导致路径/转义问题
- `.mount.json` 内 **不得**出现密码/token

## `.env` 协议（多挂载点）

存放位置：`.agents/nas_smb/secrets/.env`

### 关键字段（v1）

- `NAS_SMB_MOUNTS`：逗号分隔的 mount id 列表（示例：`home,work`）

对每个 mount id（记为 `X`，大小写不敏感，解析时统一转大写）：

- `NAS_SMB_X_HOST`：NAS IP/域名（必填）
- `NAS_SMB_X_PORT`：默认 `445`
- `NAS_SMB_X_DOMAIN`：可选（无则空）
- `NAS_SMB_X_USERNAME`：必填（允许 guest 时可为空，但必须显式 `NAS_SMB_X_GUEST=true`）
- `NAS_SMB_X_PASSWORD`：必填（guest 模式可为空）
- `NAS_SMB_X_SHARE`：共享名（必填）
- `NAS_SMB_X_REMOTE_DIR`：远端目录（默认 `/`）
- `NAS_SMB_X_MOUNT_NAME`：本地挂载点目录名（默认与 mount id 相同）
- `NAS_SMB_X_READ_ONLY`：默认 `false`

> 注意：`.env` 是 secrets 文件；任何日志、stdout、result 都不得回显用户名/密码/host（至少要打码）。

## Requirements（Req IDs）

### A. 初始化与 secrets

- REQ-0033-001：App 初始化 `.agents` 工作区时，必须创建 `.agents/nas_smb/` 与 `.agents/nas_smb/secrets/`。
- REQ-0033-002：首次初始化时，若 `.agents/nas_smb/secrets/.env` 不存在，则从 assets 模板 `env.example` 生成 `.env`；若已存在则**绝不覆盖**。
- REQ-0033-003：`.env` 支持多 mount；任何缺字段/非法字段必须返回可解释错误（稳定 `errorCode`），不得 silent fail。

### B. 挂载点目录呈现

- REQ-0033-010：根据 `.env` 解析出的 mount 列表，确保在 `.agents/nas_smb/` 下存在每个 `<mount_name>/` 占位目录与 `.mount.json`（无 secrets）。
- REQ-0033-011：在 Files 页签中，`nas_smb/` 必须与 `sessions/`、`skills/` 同级出现；进入后能看到 `secrets/` 与挂载点目录。

### C. 远端目录的文件操作兼容（核心）

- REQ-0033-020：当用户/Agent 对 `.agents/nas_smb/<mount_name>/**` 发起文件操作时，必须路由到 SMB 远端而不是本地空目录。
- REQ-0033-021：至少支持：
  - LIST：列出远端目录
  - READ：读取远端文本文件（UTF-8）
  - NEW/EDIT：创建/覆盖写入远端文本文件（UTF-8）
  - MKDIR：创建远端目录（递归）
  - DELETE：删除远端文件/目录（目录删除需 recursive=true 的显式语义）
- REQ-0033-022：所有路径必须禁止 `..`、禁止 escape `.agents` 根；并且**必须**把“本地路径”与“远端路径”严格区分（避免误删本地 secrets）。

### D. 连接管理与错误口径

- REQ-0033-030：每个 mount 的 SMB 连接必须有超时（连接/认证/读写），超时返回稳定 `errorCode="Timeout"`。
- REQ-0033-031：认证失败返回稳定 `errorCode="AuthFailed"`；share 不存在返回 `ShareNotFound`；权限不足返回 `PermissionDenied`；主机不可达返回 `HostUnreachable`。
- REQ-0033-032：错误信息不得泄露 secrets（不得把密码/完整用户名/完整 host 写入 errorMessage）。

## Acceptance（硬口径）

1) `.agents/nas_smb/` 在 Files 根目录可见（与 `sessions/`、`skills/` 同级），且存在 `secrets/.env`（不存在时自动生成模板）。  
2) 在 `.env` 配置至少 2 个 mount 后，`.agents/nas_smb/` 下出现 2 个挂载点目录。  
3) 在任意挂载点目录下：
   - 新建一个文本文件并写入内容 → 用 READ 再读回一致；  
   - 新建一个子目录 → 用 LIST 可见；  
   - 删除文件/目录按预期生效；  
4) 反作弊：对 `.agents/nas_smb/<mount_name>/` 的文件操作**不得**落到本地占位目录；必须有单测证明“远端实现被调用”。  
5) Verify：`.\gradlew.bat :app:testDebugUnitTest` exit code=0（覆盖：dotenv 解析 + mount 映射 + 关键错误码 + VFS 路由）。  

## Risks

- SMB 实现复杂：连接复用、超时、断线重连、权限错误都需要稳定口径，否则 Agent 会反复撞墙。  
- 秘钥明文风险：`.env` 在本机落盘，存在设备丢失风险；后续可选用 Jetpack Security 加密存储（若做，必须走 ECN）。  
- UI 与 VFS 耦合：如果把 SMB 逻辑写进 UI，很快会失控；必须抽象出“AgentsFs / VFS Router”，并用单测锁死行为。  

## Locked Decisions（已确认，作为实现口径）

1) 目录入口：使用 `.agents/nas_smb/`（与 `sessions/`、`skills/` 同级），不放到 `.agents/workspace/`。  
2) secrets 存放：使用 `.agents/nas_smb/secrets/.env`（不走 `.agents/skills/<skill>/secrets`）。  
3) 实现形态：App 内部 VFS 挂载，不做系统级挂载；挂载点目录为本地占位 + 远端 I/O 路由。  
4) `.env` 协议：`NAS_SMB_MOUNTS` + `NAS_SMB_<ID>_*` 的多 profile 设计，支持多个 NAS/多个 share/多个 target。  

