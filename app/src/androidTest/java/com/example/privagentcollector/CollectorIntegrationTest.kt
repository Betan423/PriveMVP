package com.example.privagentcollector

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Run only on a disposable emulator: these tests clear the target app's test data. */
@RunWith(AndroidJUnit4::class)
class CollectorIntegrationTest {
    @Test fun messageGuardPatternsInitializeOnAndroid() {
        // Android uses ICU's regex engine, which does not accept every flag supported by the
        // desktop JVM. Exercising the real packet path here prevents click-time initializer crashes.
        val raw = "王小明您好，請立即轉-帳並回撥 0912-345-678，網址 https://example.invalid"
        val packet = MessageGuard.buildPacket(raw, "pasted", allowExcerpt = true)
        assertEquals(MessageGuard.Level.HIGH, MessageGuard.assess(packet.facts).level)
        assertFalse(packet.json.contains("王小明"))
        assertFalse(packet.json.contains("0912"))
        assertFalse(packet.json.contains("example.invalid"))
    }

    @Test fun encryptedStorageAndStopStartAreIsolated() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ResearchLog.clear(context)
        CollectionControl.setConsent(context, true)
        DataSource.entries.forEach { CollectionControl.select(context, it, it == DataSource.DEVICE) }
        val first = CollectionControl.start(context)
        try {
            assertTrue(ResearchLog.append(context, DataSource.DEVICE, first, "test_device", JSONObject().put("name", "測試資料")))
            CollectionControl.stop()
            assertFalse(ResearchLog.append(context, DataSource.DEVICE, first, "stale", JSONObject()))
            val second = CollectionControl.start(context)
            assertNotEquals(first, second)
            assertFalse(ResearchLog.append(context, DataSource.DEVICE, first, "stale", JSONObject()))
            val disk = context.filesDir.resolve("raw_encrypted").listFiles()!!.first().readText()
            assertFalse(disk.contains("測試資料"))
            val output = ByteArrayOutputStream()
            assertEquals(1, ResearchLog.export(context, output))
            val record = JSONObject(output.toString("UTF-8").trim())
            assertEquals(first, record.getString("sessionId"))
            assertEquals("測試資料", record.getJSONObject("payload").getString("name"))
            ResearchLog.clear(context)
            assertFalse(ResearchLog.hasData(context))
        } finally {
            ResearchLog.clear(context)
            CollectionControl.setConsent(context, false)
        }
    }
    @Test fun api28AppInventoryAndDeniedPermissionsDoNotAbortSnapshot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ResearchLog.clear(context)
        CollectionControl.setConsent(context, true)
        DataSource.entries.forEach {
            CollectionControl.select(context, it, it == DataSource.APPS || it == DataSource.CONTACTS || it == DataSource.BATTERY)
        }
        try {
            val result = Collectors.collectAll(context, CollectionControl.start(context)) {}
            assertEquals(3, result.size)
            assertEquals("ok", result.first { it.first == DataSource.APPS }.second)
            assertEquals("ok", result.first { it.first == DataSource.BATTERY }.second)
        } finally { ResearchLog.clear(context); CollectionControl.setConsent(context, false) }
    }
}
