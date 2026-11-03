package com.example.privagentcollector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LineAutomationRulesTest {
    @Test fun recipientMatchingIsExactAfterPresentationNormalization() {
        assertTrue(LineAutomationRules.isExactRecipient(" 王小美 ", "王小美"))
        assertTrue(LineAutomationRules.isExactRecipient("王 小美", "王小美"))
        assertFalse(LineAutomationRules.isExactRecipient("王小美（女兒）", "王小美"))
        assertFalse(LineAutomationRules.isExactRecipient("小美", "王小美"))
    }

    @Test fun onlyAllowlistedSendLabelsAreAccepted() {
        assertTrue(LineAutomationRules.isSendControl("傳送"))
        assertTrue(LineAutomationRules.isSendControl("Send"))
        assertFalse(LineAutomationRules.isSendControl("傳送給王小美"))
        assertFalse(LineAutomationRules.isSendControl("下一步"))
    }

    @Test fun normalChatDoesNotLookLikeTheShareRecipientScreen() {
        assertTrue(LineAutomationRules.isShareScreenMarker("分享對象"))
        assertTrue(LineAutomationRules.isShareScreenMarker("選擇傳送對象"))
        assertTrue(LineAutomationRules.isShareScreenMarker("Share with"))
        assertFalse(LineAutomationRules.isShareScreenMarker("分享"))
        assertFalse(LineAutomationRules.isShareScreenMarker("聊天室"))
    }

    @Test fun voiceConfirmationFailsClosed() {
        assertEquals(LineAutomationRules.VoiceDecision.CONFIRM,
            LineAutomationRules.voiceDecision("確認傳送"))
        assertEquals(LineAutomationRules.VoiceDecision.CANCEL,
            LineAutomationRules.voiceDecision("不要傳送"))
        assertEquals(LineAutomationRules.VoiceDecision.UNKNOWN,
            LineAutomationRules.voiceDecision("好"))
        assertEquals(LineAutomationRules.VoiceDecision.UNKNOWN,
            LineAutomationRules.voiceDecision("傳給別人"))
    }
}
