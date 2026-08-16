package com.unsupportedpastels.hermesandroid.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CronModelsTest {
    @Test
    fun parseOfficialRunsEnvelopeIsBoundedAndKeepsReturnedSessionFields() {
        val rows = (1..30).joinToString(",") { index ->
            """{"id":"cron_job_$index","title":"Run $index","preview":"preview $index","source":"cron","started_at":1700000000.0,"ended_at":1700000001.0,"last_active":1700000001.0,"is_active":false,"message_count":$index,"tool_call_count":2,"input_tokens":10,"output_tokens":20}"""
        }
        val parsed = parseCronJobRuns(
            Json.parseToJsonElement("""{"runs":[$rows],"limit":99}""").jsonObject,
            CronJobScope("https://hermes.example", "work", "job"),
            limit = 20,
        )

        assertEquals(20, parsed.size)
        assertEquals("cron_job_20", parsed.last().id)
        assertEquals("cron", parsed.first().source)
        assertEquals(10L, parsed.first().inputTokens)
        assertEquals(20L, parsed.first().outputTokens)
    }

    @Test
    fun parseOfficialRunsSkipsMalformedRowsAndDoesNotInventTimingOrOutcome() {
        val parsed = parseCronJobRuns(
            Json.parseToJsonElement(
                """{"runs":[{"id":"run-1","title":"Returned"},{"title":"missing id"},{"id":"run-2","preview":"only preview"}]}""",
            ).jsonObject,
            CronJobScope("https://hermes.example", "default", "job-1"),
        )

        assertEquals(listOf("run-1", "run-2"), parsed.map(CronJobRun::id))
        assertEquals(null, parsed.first().startedAt)
        assertEquals(null, parsed.first().endedAt)
        assertEquals(null, parsed.first().isActive)
        assertTrue(parsed.first().title == "Returned")
    }
}
