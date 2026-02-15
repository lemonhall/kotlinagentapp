package com.lsl.kotlin_agent_app.agent

import kotlinx.coroutines.flow.Flow
import me.lemonhall.openagentic.sdk.runtime.ProviderStreamEvent
import com.lsl.kotlin_agent_app.ui.chat.ChatMessage

interface ChatAgent {
    fun streamReply(conversation: List<ChatMessage>): Flow<ProviderStreamEvent>
    fun clearSession()
}
