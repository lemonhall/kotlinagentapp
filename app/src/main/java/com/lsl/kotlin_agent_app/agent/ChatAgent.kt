package com.lsl.kotlin_agent_app.agent

import kotlinx.coroutines.flow.Flow
import me.lemonhall.openagentic.sdk.events.Event

interface ChatAgent {
    fun streamReply(prompt: String): Flow<Event>
    fun clearSession()
}
