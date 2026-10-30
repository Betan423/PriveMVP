package com.example.privagentcollector

import org.junit.Assert.*
import org.junit.Test

class LiveMessageWatchTest {
    @Test fun newestChatLineWinsOverExpandedText() {
        assertEquals("最新一句", LiveMessageWatch.messageText("最新一句", "舊的\n最新一句", "最新一句"))
        assertEquals("完整長訊息", LiveMessageWatch.messageText(null, "完整長訊息", "完整長…"))
        assertEquals("短訊息", LiveMessageWatch.messageText(null, null, "  短訊息 "))
        assertNull(LiveMessageWatch.messageText(null, null, "   "))
        assertEquals(20_000, LiveMessageWatch.messageText(null, "a".repeat(30_000), null)!!.length)
    }

    @Test fun repostedMessageIsCheckedOnce() {
        val body = "測試重複 " + System.nanoTime()
        assertTrue(LiveMessageWatch.firstTime("jp.naver.line.android", body))
        assertFalse(LiveMessageWatch.firstTime("jp.naver.line.android", body))
        assertTrue(LiveMessageWatch.firstTime("com.google.android.apps.messaging", body))
        // A message that never got a queue slot is forgotten, so its next repost is checked.
        LiveMessageWatch.forget("jp.naver.line.android", body)
        assertTrue(LiveMessageWatch.firstTime("jp.naver.line.android", body))
    }

    @Test fun onlyHighRiskInterruptsTheUser() {
        assertTrue(LiveMessageWatch.shouldAlert(MessageGuard.Level.HIGH))
        assertFalse(LiveMessageWatch.shouldAlert(MessageGuard.Level.VERIFY))
        assertFalse(LiveMessageWatch.shouldAlert(MessageGuard.Level.INSUFFICIENT))
    }
}
