# PRD（初稿）：Kotlin Agent App

> 目标：基于 `openagentic-sdk-kotlin` 构建一个 Android 聊天型 Agent 应用，支持与大模型对话、工具调用、Skill 管理与应用内文件系统管理，并预留 WebView 自动化能力。

## 1. 背景与愿景

- 个人/小团队开发与实验性质的 Agent App，用于在移动端验证「对话 + 工具 + Skill + 本地文件」的闭环体验。
- 通过统一的 Agent/Tool/Skill 抽象，把“能做事的聊天”从桌面/CLI 延伸到 Android。

## 2. 目标用户与使用场景

- **开发者/极客用户**：希望随时随地调用大模型，执行工具（例如文件整理、文本处理、Skill 安装与管理）。
- **自托管/多厂商用户**：需要配置自定义 `base_url` 与 `api_key`，不绑定单一平台。

## 3. 成功标准（MVP 可验收）

- 能在主界面完成：输入消息 → 触发模型 → 展示流式/非流式回复 → 记录会话。
- Agent 能触发工具调用（至少包含：应用内文件读写/列表、Skill 安装/启停/列举）。
- 有设置页可配置：`base_url` / `api_key` / 默认模型（或 model id）以及 Tool 所需的额外 key。
- 有文件管理页：可查看应用内部文件系统树、预览文本、删除/新建文件（带确认）。

## 4. 范围与功能拆分

### 4.1 MVP（v0.1）

**A. 聊天（Agent Chat）**
- 会话列表 + 单会话聊天页（消息气泡、时间、角色、复制/删除）。
- 支持流式输出（若 SDK/后端支持），失败可重试。
- 显示工具调用轨迹（例如“调用：file.list / skill.install”）与结果摘要。

**B. Tool 能力（内置工具集）**
- `file.*`：列目录、读文本、写文本、创建目录、删除文件/目录（删除需二次确认）。
- `skill.*`：列出已安装 skills、安装/卸载、启用/禁用、查看 skill 元信息与说明。
- 预留 `web.*`：后续支持 WebView 自动化（v0.2+）。

**C. Skills 管理**
- Skill 列表页：已安装、版本/来源、启用状态、搜索/过滤。
- “对话式安装 Skill”：在聊天中输入“安装 xxx skill”，由 File/Skill Agent 执行并给出结果。

**D. 文件管理（App 内部存储）**
- 浏览：应用沙箱目录（`context.filesDir` / `context.cacheDir` 等）。
- 操作：新建/重命名/删除（确认）、文本预览/编辑（MVP 可仅支持小文件）。
- 安全：不越界访问系统/外部存储（除非后续明确申请权限与范围）。

**E. 设置（Settings）**
- LLM：`base_url`、`api_key`、默认模型、超时、是否流式。
- Tools：按工具类型维护 key（例如搜索类、网页类、第三方 API）。
- 调试：开启/关闭详细日志、导出诊断信息（MVP 可只做开关）。

### 4.2 后续版本（v0.2+）

- WebView 容器页 + Agent 操作 WebView（脚本注入、DOM 读取、点击/输入等）。
- 更完整的文件编辑器（语法高亮、diff、批量操作、压缩/解压）。
- 多 Agent 协作（例如 Planner / File Agent / Web Agent），与 Skill 选择路由。
- 会话/Skill/配置的导入导出、云同步（可选，非 MVP）。

### 4.3 非目标（明确不做/暂不做）

- 不做账号体系、社交/共享社区（除非后续单独立项）。
- 不做访问用户外部存储的“全盘文件管理器”（避免权限与隐私风险）。
- 不追求所有模型供应商兼容，优先满足“可配置 base_url 的 OpenAI 兼容接口”。

## 5. 体验与信息架构（IA）

底部导航（当前工程模板为 3 Tab）建议 MVP 对应：
- **Chat**：会话列表/聊天页
- **Files / Skills**：文件管理 + skill 管理（可二级 Tab 或列表入口）
- **Settings**：模型与工具 key 配置、调试开关

## 6. 技术方案（建议）

### 6.1 架构分层（建议）

- UI：Activity/Fragment（当前模板）或迁移到 Compose（见选型）
- Domain：`AgentService`（封装 SDK 调用、工具注册、流式回调）
- Data：`DataStore`（配置与 key），必要时引入 `Room`（会话/消息持久化）
- Tool/Skill：一组可注册的工具实现 + Skill 仓库（本地目录 + 元信息索引）

### 6.2 关键数据

- `AppConfig`：`baseUrl`、`apiKey`、`model`、`timeouts`、`debugFlags`
- `ToolSecrets`：按 tool namespace 存储（仅本地，注意加密/混淆）
- `Conversation` / `Message`：会话与消息（可先本地 JSON，后续再 Room）
- `SkillManifest`：skill 元数据（名称、描述、入口、权限需求、来源）

### 6.3 选型备选（需要确认）

1) **继续沿用 Fragment + XML（推荐先这样）**
   - 优点：与当前工程模板一致，改动小；ViewBinding 已开启。
   - 缺点：聊天流式 UI 与复杂列表的迭代速度稍慢。

2) **迁移到 Jetpack Compose**
   - 优点：更适合聊天/列表/状态流；组件化更自然。
   - 缺点：一次性迁移成本较高；需要统一导航与主题方案。

3) **混合：核心聊天页用 Compose，其他保留 XML**
   - 优点：逐步迁移；风险可控。
   - 缺点：UI 栈混合带来维护复杂度。

## 7. 安全与隐私

- `api_key` 与 tool keys **严禁**写入 git；仅存本地（`DataStore`），必要时使用 `EncryptedSharedPreferences` / Jetpack Security。
- 默认只操作应用私有目录；删除操作必须二次确认。
- 调试日志避免打印完整密钥与完整响应内容（可打码/截断）。

## 8. 里程碑（建议）

- v0.1：聊天 + 设置 + 文件管理（内部存储）+ skill 管理（本地）
- v0.2：工具体系完善（更多 tool）+ WebView 容器 + 轻量自动化
- v0.3：持久化与可移植（导入/导出）+ 多 Agent 路由

## 9. 待确认（只列关键）

- SDK 集成方式：`openagentic-sdk-kotlin` 是否已有 Maven 坐标？还是以 git submodule / composite build 引入？
- UI 技术栈：MVP 是否先用 Fragment + XML，还是直接 Compose？
- 模型接口：是否以“OpenAI 兼容 Chat Completions / Responses”为主？默认模型名是什么？

