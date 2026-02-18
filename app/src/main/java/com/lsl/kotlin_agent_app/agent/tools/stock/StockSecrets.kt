package com.lsl.kotlin_agent_app.agent.tools.stock

import com.lsl.kotlin_agent_app.agent.tools.mail.DotEnv
import java.io.File

internal data class StockSecrets(
    val apiKey: String,
    val baseUrl: String,
)

internal object StockSecretsLoader {
    const val skillName: String = "stock-cli"
    const val defaultBaseUrl: String = "https://finnhub.io/api/v1"

    fun loadFromAgentsRoot(agentsRoot: File): StockSecrets? {
        val env = File(agentsRoot, "skills/$skillName/secrets/.env")
        val values = DotEnv.load(env)
        val apiKey = values["FINNHUB_API_KEY"]?.trim().orEmpty()
        if (apiKey.isBlank()) return null
        val baseUrl = values["FINNHUB_BASE_URL"]?.trim().takeIf { !it.isNullOrBlank() } ?: defaultBaseUrl
        return StockSecrets(apiKey = apiKey, baseUrl = baseUrl)
    }
}

