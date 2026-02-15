# PRD-0001：Kotlin Agent App（Android）

## Vision

在 Android 端实现一个“能做事的聊天”应用：用户在聊天界面与 Agent 对话，Agent 可按需调用工具（Tools）与技能（Skills）来完成任务，并能安全地管理应用内文件系统；同时提供设置页用于配置模型与工具密钥，并为未来 WebView 自动化留出架构扩展点。

## Background

- 当前仓库为 Android Studio Bottom Navigation 模板起步工程（Fragment + XML + ViewBinding + Navigation）。
- 目标 SDK：`openagentic-sdk-kotlin`（后续以明确方式集成）。

## Personas

- P1 开发者/极客：需要快速验证“对话 + 工具 + skills”的闭环，且可自托管/多厂商切换。
- P2 自托管用户：需要自定义 `base_url` 与 `api_key`，并按 tool 类型配置额外 key。

## Non-Goals（本 PRD 迭代范围外）

- 不做账号体系/社区分享/云同步（除非后续单独立项）。
- 不做对外部存储的“全盘文件管理器”（避免权限/隐私风险；仅限 App 沙箱目录）。
- 不追求所有模型供应商兼容；优先 OpenAI 兼容接口（或 SDK 支持的接口形态）。

## Constraints & Principles

- 密钥与敏感数据不得提交到 git；日志需打码/截断。
- 文件操作默认仅限 App 私有目录；删除必须二次确认。
- 采用混合 UI：**Chat 页使用 Compose**；其余页面先沿用 Fragment + XML（可逐步迁移）。

## Requirements（Req IDs）

### Chat（对话）

- REQ-0001-001：用户可在 Chat 页输入消息并发送，消息以气泡列表展示（区分 user/assistant）。
  - Acceptance：
    - 发送后列表新增一条 user 消息；
    - 收到回复后列表新增一条 assistant 消息（MVP 可先用 echo/mock）。
- REQ-0001-002：Chat 页显示“工具调用轨迹”的占位区（MVP 为静态/模拟事件，后续接 SDK）。
  - Acceptance：当产生 tool 事件时，UI 能展示事件名称与简要结果（可折叠）。
- REQ-0001-003：对话状态具备基本错误处理与重试入口（MVP：失败提示 + 重新发送）。

### Settings（设置）

- REQ-0001-010：提供设置页可配置 LLM `base_url`、`api_key`、默认 `model`（或 model id）。
  - Acceptance：写入本地持久化后，重新进入页面仍能读回同值。
- REQ-0001-011：提供设置页可配置 Tools 所需的额外 key（按 tool 类型/namespace 管理，MVP 可先提供“自定义 key/value 列表”）。

### Files（文件管理：App 内部存储）

- REQ-0001-020：提供 Files 页可浏览 App 私有目录（至少 `filesDir`）的目录列表。
- REQ-0001-021：支持文本文件预览（MVP 限制大小；超限提示）。
- REQ-0001-022：支持新建/删除文件或目录（删除需二次确认）。

### Skills（技能）

- REQ-0001-030：提供 Skills 页可列出已安装 skills（名称、描述、启用状态、来源）。
- REQ-0001-031：支持安装/卸载/启用/禁用 skill（MVP 可先定义本地 skill 目录与 manifest 规范占位）。
- REQ-0001-032：支持“对话式安装 skill”的最短闭环（MVP：自然语言 → 解析 → 调用本地安装逻辑 → 回显结果；后续接 SDK/Agent 路由）。

### SDK Integration（集成）

- REQ-0001-040：以可替换方式集成 `openagentic-sdk-kotlin`（不把 SDK 细节散落到 UI）。
  - Acceptance：SDK 入口封装在 `AgentService`（或同等）中，UI 只依赖接口。

### WebView（未来能力预留）

- REQ-0001-050：为 WebView 自动化预留模块/接口与导航入口（MVP 不实现实际自动化）。

## Milestones（建议）

- v1（MVP-1）：Chat 页（Compose）+ 基本设置持久化 + 工具轨迹占位（mock）+ 可运行的本地验证
- v2：Files 页（内部存储）+ 初版 Skills 列表/本地目录
- v3：接入 SDK（真实对话 + tool 调用）+ 对话式 skill 安装
- v4：WebView 容器 + 自动化（最小闭环）

## Open Questions（待确认）

1. `openagentic-sdk-kotlin` 集成方式：Maven 坐标 / composite build / git submodule？
2. 模型接口形态：OpenAI Chat Completions 还是 Responses？默认模型名是什么？
3. Tool/Skill 目录的规范（manifest 格式、安装来源：本地 zip / git / registry）？

