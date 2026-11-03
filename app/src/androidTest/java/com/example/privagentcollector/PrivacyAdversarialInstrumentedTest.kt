package com.example.privagentcollector

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Exhaustive transition tests run with Android's real Handler/SystemClock implementation. */
@RunWith(AndroidJUnit4::class)
class PrivacyAdversarialInstrumentedTest {
    @Test fun forwardCapabilityRejectsEveryIllegalPhaseEdge() {
        val allowed = setOf(
            LineChatForwardSession.Phase.FIND_CONTEXT_SHARE to
                LineChatForwardSession.Phase.FIND_SELECTED_MESSAGE_SHARE,
            LineChatForwardSession.Phase.FIND_SELECTED_MESSAGE_SHARE to
                LineChatForwardSession.Phase.FIND_MORE,
            LineChatForwardSession.Phase.FIND_MORE to
                LineChatForwardSession.Phase.WAIT_RECIPIENT_SCREEN
        )
        for (from in LineChatForwardSession.Phase.entries) {
            for (to in LineChatForwardSession.Phase.entries) {
                LineChatForwardSession.abort()
                val id = startAt(from)
                try {
                    val accepted = LineChatForwardSession.advance(id, from, to)
                    assertEquals("unexpected edge $from -> $to", (from to to) in allowed, accepted)
                } finally {
                    LineChatForwardSession.abort()
                }
            }
        }
    }

    private fun startAt(target: LineChatForwardSession.Phase): Long {
        val id = LineChatForwardSession.start("SYNTHETIC RECIPIENT")
        if (target == LineChatForwardSession.Phase.WAIT_TARGET_POINT) return id
        check(LineChatForwardSession.selectPoint(id, 500, 900, 1080, 2400))
        if (target == LineChatForwardSession.Phase.FIND_CONTEXT_SHARE) return id
        check(LineChatForwardSession.advance(
            id,
            LineChatForwardSession.Phase.FIND_CONTEXT_SHARE,
            LineChatForwardSession.Phase.FIND_SELECTED_MESSAGE_SHARE
        ))
        if (target == LineChatForwardSession.Phase.FIND_SELECTED_MESSAGE_SHARE) return id
        check(LineChatForwardSession.advance(
            id,
            LineChatForwardSession.Phase.FIND_SELECTED_MESSAGE_SHARE,
            LineChatForwardSession.Phase.FIND_MORE
        ))
        if (target == LineChatForwardSession.Phase.FIND_MORE) return id
        check(LineChatForwardSession.advance(
            id,
            LineChatForwardSession.Phase.FIND_MORE,
            LineChatForwardSession.Phase.WAIT_RECIPIENT_SCREEN
        ))
        return id
    }
}
