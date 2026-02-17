# v8：Explore 子 agent + Tool Output 截断落盘（OpenCode 对齐）

PRD：`docs/prd/PRD-0005-explore-subagent-and-tool-truncation.md`

## Goal

把“工具大输出”外置为 artifacts，并提供一个 SDK 内置的 `explore` 子 agent 去处理这些 artifacts，从而稳定控制主会话与 session 历史的体积，并对齐 OpenCode 的 next-steps 体验。

## PRD Trace

- REQ-0005-001：ToolResult 有界 + 完整输出落盘
- REQ-0005-002：tool-output 命名与可追溯
- REQ-0005-010：SDK 内置 explore prompt
- REQ-0005-011：TaskRunner 组合/路由
- REQ-0005-020：Task description 注入 agents 列表（与参数一致）
- REQ-0005-030：截断 wrapper 自动提示 explore next-steps

## Scope

### In-scope

- SDK：在 runtime 执行工具后（写入 `tool.result` 之前）做“超大输出落盘 + wrapper 替换”
- SDK：新增内置 explore prompt + 内置 explore runner（可与 App 自定义 runner 组合）
- SDK：Task tool description 支持注入 agents 列表（来自 options 配置）
- App：接入 SDK 新能力（注册 explore agent、组合 runner、保持现有 webview/deep-research 不回归）

### Out-of-scope

- UI：artifact_path 的“可点击打开”与预览渲染（后续另开 PRD）
- retention：tool-output 的自动清理策略（后续按需要补）

## Acceptance

1) 大输出落盘
- 触发：构造一个工具返回 >= 50_000 chars 的输出
- 预期：
  - `sessions/<sid>/events.jsonl` 中 `tool.result.output` 为 wrapper（含 `artifact_path`）
  - `tool-output/*` 中存在对应文件，内容为完整输出

2) explore 子 agent 可用
- 触发：主会话调用 `Task(agent="explore", prompt="...artifact_path...")`
- 预期：
  - 子会话正常结束并返回 JSON（含至少 `summary` 字段）
  - 默认只读工具权限（Write/Edit/Task 不在可用列表）

3) Task 描述注入 agents 列表 + 自动提示
- 触发：构造 options.taskAgents 包含 explore/webview/deep-research
- 预期：
  - Task tool description 中出现 agents 列表（名字 + 描述）
  - wrapper.hint 在 Task 可用时包含 `Task(agent="explore")` 的 next-steps

## Files（预计变更）

- SDK：
  - `external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/runtime/RuntimeModels.kt`
  - `external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/runtime/OpenAgenticSdk.kt`
  - `external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/tools/OpenAiToolSchemas.kt`
  - `external/openagentic-sdk-kotlin/src/main/resources/me/lemonhall/openagentic/sdk/toolprompts/task.txt`
  - （新增）`external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/subagents/*`
  - （新增/可选）`external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/hooks/HookEngines.kt`
- SDK tests：
  - `external/openagentic-sdk-kotlin/src/test/kotlin/me/lemonhall/openagentic/sdk/runtime/*`
- App：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/OpenAgenticSdkChatAgent.kt`

## Steps（Strict）

### 1) TDD Red：新增单测覆盖“落盘 + wrapper”

- 新增 test：
  - 运行一次工具返回大输出（自定义 Tool），断言：
    - session 根目录出现 `tool-output` 文件
    - tool.result.output 为 wrapper 且含 `artifact_path`
- 运行（预期红）：`.\gradlew.bat test --tests "me.lemonhall.openagentic.sdk.runtime.*"`

### 2) TDD Green：SDK runtime 在写入 ToolResult 前截断并落盘

- 在 `runToolCall(...)` 执行 tool 后、写入 ToolResult 之前：
  - 检测输出大小
  - 超阈值：写 artifact；把 output 替换为 wrapper

### 3) TDD Red：新增单测覆盖“Task agent 列表注入”

- 构造 options.taskAgents，断言生成的 Task schema description 中包含 agents

### 4) TDD Green：实现 Task tool description agents 注入 + 模板修复

- `task.txt` 占位符修正为 `{{agents}}`（与模板引擎一致）
- schema/文案参数名对齐：`agent` / `prompt`

### 5) TDD Red/Green：内置 explore 子 agent + runner 组合

- 新增 `SubAgent` 定义与 `explore` prompt 常量
- 新增 `TaskRunner` 组合器：
  - 内置 explore runner（只读工具权限 + 专用 system prompt）
  - 允许 App 自定义 runner 兜底

### 6) App 接入（保持行为不回归）

- `OpenAgenticSdkChatAgent`：
  - 增加 `explore` 到 `taskAgents` 列表
  - runner 改为“SDK 内置 explore runner + 原有 App runner”组合

### 7) 验证（证据优先）

- SDK：`.\gradlew.bat test --tests "me.lemonhall.openagentic.sdk.*"` exit=0
- App（可选）：`.\gradlew.bat :app:assembleDebug` exit=0

