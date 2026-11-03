package com.example.privagentcollector

import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** Run only on a disposable emulator: these tests clear the target app's test data. */
@RunWith(AndroidJUnit4::class)
class CollectorIntegrationTest {
    @Test fun productHomeShowsOnlyTheTwoApprovedScenarios() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            var visibleValues = emptyList<String>()
            instrumentation.runOnMainSync {
                val values = mutableListOf<String>()
                fun collect(view: View) {
                    if (view is TextView) values += view.text.toString()
                    if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
                }
                collect(activity.window.decorView)
                visibleValues = values
            }
            val visibleText = visibleValues.joinToString("\n")
            assertTrue(visibleText.contains("安心傳送照片"))
            assertTrue(visibleText.contains("詐騙訊息檢查"))
            assertFalse(visibleValues.any { it.trim() == "手機救援" })
            assertFalse(visibleValues.any { it.trim() == "研究資料蒐集" })
        } finally { activity.finish() }
    }

    @Test fun relationBindingCanContinueTheShareChainWithoutDuplicates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        RelationBindings.clear(context, "daughter")
        try {
            RelationBindings.add(context, "daughter", "測試女兒")
            RelationBindings.add(context, "daughter", "測試女兒")
            assertEquals(listOf("測試女兒"), RelationBindings.names(context, "daughter"))
            assertNull(RelationBindings.lineName(context, "daughter", "測試女兒"))
            RelationBindings.setLineName(context, "daughter", "測試女兒", "LINE 小美")
            assertEquals("LINE 小美", RelationBindings.lineName(context, "daughter", "測試女兒"))
        } finally { RelationBindings.clear(context, "daughter") }
        assertNull(RelationBindings.lineName(context, "daughter", "測試女兒"))
    }

    @Test fun photoSanitizerRemovesGpsAndDeviceExif() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "source-with-private-exif.jpg")
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        ExifInterface(source.absolutePath).apply {
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "25/1,1/1,588/100")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "121/1,33/1,5544/100")
            setAttribute(ExifInterface.TAG_MAKE, "PRIVATE_DEVICE_MAKE")
            setAttribute(ExifInterface.TAG_MODEL, "PRIVATE_DEVICE_MODEL")
            saveAttributes()
        }
        try {
            val result = PhotoSanitizer.prepare(context, Uri.fromFile(source), "TEST_SANITIZED")
            assertTrue(result.hadLocation)
            val output = ExifInterface(result.file.absolutePath)
            assertNull(output.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertNull(output.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
            assertNull(output.getAttribute(ExifInterface.TAG_MAKE))
            assertNull(output.getAttribute(ExifInterface.TAG_MODEL))
        } finally {
            source.delete()
            PhotoSanitizer.clear(context)
        }
    }

    @Suppress("DEPRECATION")
    @Test fun lineShareIntentTargetsLineAndGrantsTheSanitizedUri() {
        val uri = Uri.parse("content://com.example.privagentcollector.share/shared/PHOTO_1.jpg")
        val intent = LineShare.imageIntent(uri, directToLine = true)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/jpeg", intent.type)
        assertEquals(LineShare.PACKAGE_NAME, intent.`package`)
        assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM))
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertNotEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertNull(LineShare.imageIntent(uri, directToLine = false).`package`)
    }

    @Test fun sharedImageIngressAcceptsOnlyOneExplicitContentImage() {
        val uri = Uri.parse("content://jp.naver.line.android/selected/image-1")
        val valid = Intent(Intent.ACTION_SEND).setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, uri)
        assertEquals(uri, SharedImageIntake.extract(valid))
        assertNull(SharedImageIntake.extract(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)))
        assertNull(SharedImageIntake.extract(Intent(Intent.ACTION_SEND).setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///sdcard/private.jpg"))))
        val multiple = ClipData.newRawUri("one", uri).apply {
            addItem(ClipData.Item(Uri.parse("content://jp.naver.line.android/selected/image-2")))
        }
        assertNull(SharedImageIntake.extract(Intent(Intent.ACTION_SEND).setType("image/jpeg").apply {
            clipData = multiple
        }))
        val resolvers = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_SEND).setType("image/jpeg"), PackageManager.MATCH_DEFAULT_ONLY)
        assertTrue(resolvers.any { it.activityInfo.name == MainActivity::class.java.name })
    }

    @Test fun lineAutomationSessionIsOneShotAndOrdered() {
        LineAutomationSession.abort()
        val id = LineAutomationSession.start("LINE 小美")
        try {
            assertEquals(LineAutomationSession.Phase.FIND_RECIPIENT, LineAutomationSession.snapshot()!!.phase)
            assertTrue(LineAutomationSession.matchesRecipient(id, " LINE 小美 "))
            assertFalse(LineAutomationSession.matchesRecipient(id, "LINE 小華"))
            assertFalse(LineAutomationSession.snapshot().toString().contains("小美"))
            val page = LineAutomationSession.pageFingerprint(id, listOf("最近的傳送對象", "LINE 小華"))!!
            assertEquals(LineAutomationSession.ScrollDecision.ALLOW, LineAutomationSession.beginScroll(id, page))
            assertEquals(LineAutomationSession.ScrollDecision.SAME_PAGE, LineAutomationSession.beginScroll(id, page))
            page.fill(0)
            assertTrue(LineAutomationSession.beginRecipientClick(id))
            assertFalse(LineAutomationSession.beginRecipientClick(id))
            assertTrue(LineAutomationSession.beginVoiceConfirmation(id))
            assertFalse(LineAutomationSession.beginSendClick(id))
            assertTrue(LineAutomationSession.confirm(id))
            assertTrue(LineAutomationSession.beginSendClick(id))
            assertFalse(LineAutomationSession.beginSendClick(id))
            assertTrue(LineAutomationSession.complete(id))
            assertNull(LineAutomationSession.snapshot())
        } finally { LineAutomationSession.abort() }
    }

    @Test fun voiceConfirmationAlwaysOffersAnExplicitManualFallback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        LineAutomationSession.abort()
        val id = LineAutomationSession.start("LINE 小美")
        assertTrue(LineAutomationSession.beginRecipientClick(id))
        assertTrue(LineAutomationSession.beginVoiceConfirmation(id))
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, LineVoiceConfirmActivity::class.java)
                .putExtra(LineVoiceConfirmActivity.EXTRA_SESSION_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            var confirm: Button? = null
            instrumentation.runOnMainSync {
                fun find(view: View) {
                    if (view is Button && view.text.toString() == "確認傳送") confirm = view
                    if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))
                }
                find(activity.window.decorView)
                assertNotNull(confirm)
                confirm!!.performClick()
            }
            instrumentation.waitForIdleSync()
            assertEquals(LineAutomationSession.Phase.CONFIRMED, LineAutomationSession.snapshot()!!.phase)
        } finally {
            activity.finish()
            LineAutomationSession.abort()
        }
    }

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
