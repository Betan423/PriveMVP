package com.example.privagentcollector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingAssistantTest {
    @Test fun smallMovementIsATap() {
        assertTrue(FloatingAssistant.isTap(3f, -5f, 16))
        assertFalse(FloatingAssistant.isTap(20f, 0f, 16))
        assertFalse(FloatingAssistant.isTap(0f, -16f, 16))
    }

    @Test fun spokenTextSkipsSourceLineAndBullets() {
        assertEquals("找到可能的原因。鈴聲是靜音",
            FloatingAssistant.spoken("找到可能的原因\n・鈴聲是靜音\n\n判讀來源：手機本機規則"))
        assertEquals("", FloatingAssistant.spoken("判讀來源：Gemini"))
    }

    @Test fun alertTitleNamesAppAndOptionalSender() {
        assertEquals("小心！LINE「客服中心」的訊息可能是詐騙", ScamAlertActivity.title("LINE", "客服中心"))
        assertEquals("小心！簡訊的訊息可能是詐騙", ScamAlertActivity.title("簡訊", ""))
    }

    @Test fun alertReasonsUseFixedWordingOnly() {
        val reasons = ScamAlertActivity.reasons(listOf("ASKS_TRANSFER", "NOT_A_REAL_CODE"))
        assertEquals(1, reasons.size)
        assertEquals(MessageGuard.reasonText["ASKS_TRANSFER"], reasons[0])
    }
}
