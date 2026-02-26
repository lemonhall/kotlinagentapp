# 如何添加新的 Provider

本项目的 LLM Provider 架构分为两层：SDK 层（格式转换 + HTTP 通信）和 App 层（配置 + UI）。

## 1. SDK 层：实现 Provider

所有 provider 都实现 `StreamingResponsesProvider` 接口。SDK 核心循环发送 Responses 格式的 input，provider 内部负责转换为目标 API 格式。

### 1.1 选择基础模式

| 目标 API 格式 | 参考实现 | 说明 |
|---|---|---|
| OpenAI Chat Completions (`/v1/chat/completions`) | `OpenAIChatCompletionsHttpProvider` | DeepSeek、Groq、Together、Ollama 等 |
| Anthropic Messages (`/v1/messages`) | `AnthropicMessagesHttpProvider` | Claude 系列 |
| OpenAI Responses (`/responses`) | `OpenAIResponsesHttpProvider` | OpenAI 原生 |

如果新 provider 兼容 OpenAI Chat Completions 格式，直接复用 `OpenAIChatCompletionsHttpProvider`，只需在 App 层添加配置即可，无需新建 SDK 文件。

### 1.2 需要新建 SDK Provider 的情况

如果目标 API 格式不兼容以上任何一种，需要新建三个文件：

```
external/openagentic-sdk-kotlin/src/main/kotlin/.../providers/
├── XxxHttpProvider.kt          # 主 provider，实现 StreamingResponsesProvider
├── XxxParsing.kt               # 格式转换工具函数
└── XxxSseDecoder.kt            # SSE 流式事件解码器
```

关键接口：

```kotlin
class XxxHttpProvider(
    override val name: String = "xxx",
    private val baseUrl: String = "https://api.xxx.com",
) : StreamingResponsesProvider {

    // 非流式
    override suspend fun complete(request: ResponsesRequest): ModelOutput { ... }

    // 流式（优先使用）
    override fun stream(request: ResponsesRequest): Flow<ProviderStreamEvent> { ... }
}
```

`ResponsesRequest` 包含：`model`, `input`（Responses 格式消息列表）, `tools`（Responses 格式工具 schema）, `apiKey`。

Provider 需要：
1. 将 `input` 转换为目标 API 的消息格式
2. 将 `tools` 转换为目标 API 的工具格式
3. 将响应转换回 `ModelOutput`（`assistantText`, `toolCalls`, `usage`）
4. SSE 流中发射 `ProviderStreamEvent.TextDelta` 和最终的 `ProviderStreamEvent.Completed`

## 2. App 层：添加配置

### 2.1 `LlmConfig.kt` — 添加字段

```kotlin
val xxxBaseUrl: String = "",
val xxxApiKey: String = "",
val xxxModel: String = "",
```

### 2.2 `SharedPreferencesLlmConfigRepository.kt` — 读写新字段

在 `get()`、`set()`、debug seeding、companion object KEY 常量中添加对应项。

### 2.3 `build.gradle.kts` — BuildConfig 字段

release block 添加空默认值：
```kotlin
buildConfigField("String", "DEFAULT_XXX_BASE_URL", "\"\"")
buildConfigField("String", "DEFAULT_XXX_API_KEY", "\"\"")
buildConfigField("String", "DEFAULT_XXX_MODEL", "\"\"")
```

debug block 从 `.env` 读取：
```kotlin
val xxxBaseUrl = dotenv["XXX_BASE_URL"].orEmpty()
val xxxApiKey = dotenv["XXX_API_KEY"].orEmpty()
val xxxModel = dotenv["XXX_MODEL"].orEmpty()
buildConfigField("String", "DEFAULT_XXX_BASE_URL", "\"${escapedForBuildConfig(xxxBaseUrl)}\"")
// ...
```

### 2.4 `.env.example` — 添加配置示例

```
# ===== Xxx (可选) =====
# XXX_API_KEY=
# XXX_BASE_URL=https://api.xxx.com/v1
# XXX_MODEL=xxx-model
```

### 2.5 `OpenAgenticSdkChatAgent.kt` — resolveProvider 添加分支

```kotlin
"xxx" -> {
    val baseUrl = config.xxxBaseUrl.trim()
    val apiKey = config.xxxApiKey.trim()
    val model = config.xxxModel.trim()
    require(baseUrl.isNotEmpty()) { "xxx_base_url 未配置" }
    require(apiKey.isNotEmpty()) { "xxx_api_key 未配置" }
    require(model.isNotEmpty()) { "xxx_model 未配置" }
    // OpenAI 兼容的用 OpenAIChatCompletionsHttpProvider
    Triple(OpenAIChatCompletionsHttpProvider(name = "xxx", baseUrl = baseUrl), apiKey, model)
    // 自定义格式的用 XxxHttpProvider
    // Triple(XxxHttpProvider(baseUrl = baseUrl), apiKey, model)
}
```

### 2.6 Settings UI

`SettingsFragment.kt` — 在 `providerOptions` 列表中添加 `"xxx"`，添加字段绑定。

`fragment_settings.xml` — 添加 header TextView + base_url / api_key / model 输入框，更新约束链。

## 3. 快速检查清单

- [ ] `.env.example` 添加配置段
- [ ] `build.gradle.kts` release + debug BuildConfig 字段
- [ ] `LlmConfig.kt` 添加字段
- [ ] `SharedPreferencesLlmConfigRepository.kt` 读写 + debug seeding
- [ ] `OpenAgenticSdkChatAgent.kt` resolveProvider 分支
- [ ] `SettingsFragment.kt` dropdown + 字段绑定
- [ ] `fragment_settings.xml` UI 布局
- [ ] （如需）SDK 层新建 Provider + Parsing + SseDecoder
