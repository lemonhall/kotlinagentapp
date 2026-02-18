package com.lsl.kotlin_agent_app.agent

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeContextInfoTest {

    @Test
    fun build_includesLocalUtcAndZoneId() {
        val now = Instant.parse("2026-02-18T00:00:00Z")
        val zone = ZoneId.of("Asia/Shanghai")
        val text = TimeContextInfo.build(now = now, zoneId = zone)
        assertTrue(text.contains("local_datetime:"))
        assertTrue(text.contains("utc_datetime:"))
        assertTrue(text.contains("time_zone: Asia/Shanghai"))
        assertTrue(text.contains("2026-02-18T08:00:00+08:00"))
        assertTrue(text.contains("2026-02-18T00:00:00Z"))
    }
}

