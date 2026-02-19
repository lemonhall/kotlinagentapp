package com.lsl.kotlin_agent_app.radios

internal object RadioPathNaming {
    fun sanitizeSegment(name: String): String {
        val cleaned =
            name
                .trim()
                .replace('\u0000', ' ')
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .replace(Regex("\\s+"), " ")
                .trim()
        val noDot = cleaned.trimStart('.').trim()
        return noDot.ifBlank { "unknown" }
    }

    fun countryDirName(
        countryName: String,
        iso3166_1: String?,
    ): String {
        val code = iso3166_1?.trim()?.uppercase()?.takeIf { it.matches(Regex("^[A-Z]{2}$")) }
        val safe = sanitizeSegment(countryName)
        val stem = safe.take(48).trim().trimEnd('_').ifBlank { "country" }
        return if (code != null) "${code}__${stem}".trimEnd('_') else stem
    }

    fun stationFileName(
        stationName: String,
        stationUuid: String,
    ): String {
        val safeName = sanitizeSegment(stationName).take(64).trim().trimEnd('_').ifBlank { "station" }
        val shortId = stationUuid.trim().replace(Regex("[^a-zA-Z0-9]"), "").takeLast(10).ifBlank { "id" }
        return "${safeName}__${shortId}.radio"
    }
}

