package com.lsl.kotlin_agent_app.agent

import com.lsl.kotlin_agent_app.config.SharedPreferencesLlmConfigRepository
import com.lsl.kotlin_agent_app.ui.chat.ChatRole
import com.lsl.kotlin_agent_app.ui.chat.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import me.lemonhall.openagentic.sdk.providers.OpenAIResponsesHttpProvider
import me.lemonhall.openagentic.sdk.providers.ResponsesRequest
import me.lemonhall.openagentic.sdk.runtime.ProviderStreamEvent

class OpenAiResponsesChatAgent(
    private val configRepository: SharedPreferencesLlmConfigRepository,
) : ChatAgent {
    override fun streamReply(conversation: List<ChatMessage>): Flow<ProviderStreamEvent> {
        val config = configRepository.get()
        val baseUrl = config.baseUrl.trim()
        val apiKey = config.apiKey.trim()
        val model = config.model.trim()

        require(baseUrl.isNotEmpty()) { "base_url 未配置" }
        require(apiKey.isNotEmpty()) { "api_key 未配置" }
        require(model.isNotEmpty()) { "model 未配置" }

        val provider = OpenAIResponsesHttpProvider(baseUrl = baseUrl)
        val inputItems: List<JsonObject> =
            conversation
                .asSequence()
                .filter { it.role == ChatRole.User || it.role == ChatRole.Assistant }
                .map { it.role to it.content.trim() }
                .filter { (_, content) -> content.isNotBlank() }
                .map { (role, content) ->
                    buildJsonObject {
                        put("role", JsonPrimitive(if (role == ChatRole.User) "user" else "assistant"))
                        put("content", JsonPrimitive(content))
                    }
                }
                .toList()

        require(inputItems.isNotEmpty()) { "empty conversation input" }

        val req =
            ResponsesRequest(
                model = model,
                input = inputItems,
                apiKey = apiKey,
                // Prefer compatibility: always send full history; do not rely on previous_response_id.
                previousResponseId = null,
            )
        return provider.stream(req).onEach { ev ->
            if (ev is ProviderStreamEvent.Completed) {
                val next = ev.output.responseId?.trim()?.ifEmpty { null }
                if (next != null) configRepository.setPreviousResponseId(next)
            }
        }
    }

    override fun clearSession() {
        configRepository.setPreviousResponseId(null)
    }
}
