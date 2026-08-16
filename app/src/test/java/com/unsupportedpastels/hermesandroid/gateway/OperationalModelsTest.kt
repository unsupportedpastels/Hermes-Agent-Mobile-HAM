package com.unsupportedpastels.hermesandroid.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationalModelsTest {
    @Test
    fun parsesProfileScopedStatusWithBoundedComponentsAndPressure() {
        val result = Json.parseToJsonElement(
            """
            {
              "version":"0.20.1",
              "overall":"ok",
              "components":{
                "gateway":{"status":"ok","state":"running"},
                "storage":{"status":"degraded"}
              },
              "memory":{"pressure":"warning"},
              "disk":{"pressure":"ok"},
              "ignored":"${"x".repeat(10_000)}"
            }
            """.trimIndent(),
        ).jsonObject

        val parsed = parseOperationalStatus(result, profile = "work")

        assertEquals("work", parsed.profile)
        assertEquals("0.20.1", parsed.version)
        assertEquals(OperationalHealth.Ok, parsed.overall)
        assertEquals(2, parsed.components.size)
        assertEquals(OperationalHealth.Degraded, parsed.components[1].health)
        assertEquals(OperationalPressure.Warning, parsed.memoryPressure)
        assertEquals(OperationalPressure.Ok, parsed.diskPressure)
    }

    @Test
    fun malformedAndUnknownHealthValuesAreUnknownNotHealthyAndComponentListIsBounded() {
        val components = (1..40).joinToString(",") { index ->
            "\"component-$index\":{\"status\":\"not-a-health-value\"}"
        }
        val parsed = parseOperationalStatus(
            Json.parseToJsonElement(
                "{\"overall\":\"not-a-health-value\",\"components\":{$components},\"memory\":{\"pressure\":\"mystery\"}}",
            ).jsonObject,
            profile = "default",
        )

        assertEquals(OperationalHealth.Unknown, parsed.overall)
        assertEquals(OperationalHealth.Unknown, parsed.components.first().health)
        assertEquals(OperationalPressure.Unknown, parsed.memoryPressure)
        assertTrue(parsed.components.size <= MAX_OPERATIONAL_COMPONENTS)
        assertFalse(parsed.overall == OperationalHealth.Ok)
    }

    @Test
    fun cronDeliveryErrorAndCompletedFailureRequireAttentionBeforeAggregation() {
        val completedWithDeliveryError = CronJob(
            jobId = "delivery",
            name = "Delivery",
            schedule = "every 1h",
            lastStatus = "completed",
            lastDeliveryError = "chat unavailable",
        )
        val failed = CronJob(
            jobId = "failed",
            name = "Failed",
            schedule = "every 1h",
            lastStatus = "failed",
        )
        val running = CronJob(
            jobId = "running",
            name = "Running",
            schedule = "every 1h",
            lastStatus = "running",
        )

        assertTrue(completedWithDeliveryError.requiresOperationalAttention())
        assertTrue(failed.requiresOperationalAttention())
        assertFalse(running.requiresOperationalAttention())
    }
}
