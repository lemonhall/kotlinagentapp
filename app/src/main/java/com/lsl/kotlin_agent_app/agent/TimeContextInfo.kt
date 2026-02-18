package com.lsl.kotlin_agent_app.agent

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal object TimeContextInfo {
    private val isoOffset: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun build(
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val local = ZonedDateTime.ofInstant(now, zoneId)
        val utc = ZonedDateTime.ofInstant(now, ZoneOffset.UTC)
        return """
            当前时间/时区（来自 Android 本机）：
            - local_datetime: ${isoOffset.format(local)}
            - time_zone: ${zoneId.id}
            - utc_datetime: ${isoOffset.format(utc)}

            解释规则：
            - 当用户说“今天/明天/昨天/本周/下周/节假日”等相对时间，一律以上述 time_zone 为准。
        """.trimIndent()
    }
}

