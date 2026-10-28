package com.example.privagentcollector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeSettingRulesTest {
    @Test fun parsesValidSettings() {
        val settings = RealtimeSettingRules.parse("30", "60", true)
        assertEquals(30, settings.baselineSeconds)
        assertEquals(60, settings.intervalSeconds)
        assertTrue(settings.immediateEvents)
    }

    @Test fun acceptsDocumentedBoundaries() {
        assertFalse(RealtimeSettingRules.parse("5", "10", false).immediateEvents)
        RealtimeSettingRules.parse("600", "3600", true)
    }

    @Test fun rejectsInvalidOrUnsafeCadence() {
        listOf("0" to "60", "601" to "60", "30" to "9", "30" to "3601", "x" to "60")
            .forEach { (baseline, interval) ->
                assertTrue(runCatching {
                    RealtimeSettingRules.parse(baseline, interval, true)
                }.isFailure)
            }
    }
}
