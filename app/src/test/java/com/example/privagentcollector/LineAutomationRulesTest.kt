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

    @Test fun chatImageAndItsShareControlMustBeSemanticAndLargeEnough() {
        assertTrue(LineAutomationRules.isMessageShareControl("分享"))
        assertTrue(LineAutomationRules.isMessageShareControl("Share"))
        assertFalse(LineAutomationRules.isMessageShareControl("分享對象"))
        assertTrue(LineAutomationRules.isMessageSelectionScreenMarker("選擇訊息"))
        assertTrue(LineAutomationRules.isSelectedMessageShareControl("分享 (1)"))
        assertFalse(LineAutomationRules.isSelectedMessageShareControl("分享 (2)"))
        assertTrue(LineAutomationRules.isMoreControl("更多"))
        assertFalse(LineAutomationRules.isMoreControl("更多選項說明"))
        assertTrue(LineAutomationRules.isSafeTargetPoint(540, 900, 1080, 2400))
        assertFalse(LineAutomationRules.isSafeTargetPoint(540, 20, 1080, 2400))
        assertFalse(LineAutomationRules.isSafeTargetPoint(1080, 900, 1080, 2400))
        assertTrue(LineAutomationRules.isPlausibleChatImage(
            "android.widget.ImageView", null, "照片", 500, 600, 1080, 2400))
        assertFalse(LineAutomationRules.isPlausibleChatImage(
            "android.widget.ImageView", null, "分享", 72, 72, 1080, 2400))
        assertFalse(LineAutomationRules.isPlausibleChatImage(
            "android.view.View", "匯款訊息", null, 500, 600, 1080, 2400))
    }

    @Test fun normalChatDoesNotLookLikeTheShareRecipientScreen() {
        assertTrue(LineAutomationRules.isShareScreenMarker("分享對象"))
        assertTrue(LineAutomationRules.isShareScreenMarker("選擇傳送對象"))
        assertTrue(LineAutomationRules.isShareScreenMarker("Share with"))
        assertFalse(LineAutomationRules.isShareScreenMarker("分享"))
        assertFalse(LineAutomationRules.isShareScreenMarker("聊天室"))
    }

    @Test fun selectedRecipientMarkerIsStrictAndCounted() {
        assertTrue(LineAutomationRules.isSelectedShareScreenMarker("已選 (1)"))
        assertTrue(LineAutomationRules.isSelectedShareScreenMarker("Selected (1)"))
        assertTrue(LineAutomationRules.isSelectedShareScreenMarker("選択済み 2"))
        assertFalse(LineAutomationRules.isSelectedShareScreenMarker("已選"))
        assertFalse(LineAutomationRules.isSelectedShareScreenMarker("已選 (0)"))
        assertFalse(LineAutomationRules.isSelectedShareScreenMarker("已選照片 (1)"))
        assertFalse(LineAutomationRules.isSelectedShareScreenMarker("分享"))
    }

    @Test fun verticalScrollingNeverUsesTheHorizontalLinePager() {
        assertEquals(LineAutomationRules.ScrollChoice.DOWN,
            LineAutomationRules.verticalScrollChoice(true, false, false, true, 20, 1))
        assertEquals(LineAutomationRules.ScrollChoice.FORWARD,
            LineAutomationRules.verticalScrollChoice(false, false, false, true, 20, 1))
        assertEquals(LineAutomationRules.ScrollChoice.NONE,
            LineAutomationRules.verticalScrollChoice(false, true, true, true, 1, 3))
        assertEquals(LineAutomationRules.ScrollChoice.NONE,
            LineAutomationRules.verticalScrollChoice(false, false, false, true, 1, 3))
        assertEquals(LineAutomationRules.ScrollChoice.NONE,
            LineAutomationRules.verticalScrollChoice(false, false, false, true, null, null))
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
