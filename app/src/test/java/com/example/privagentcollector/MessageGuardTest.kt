package com.example.privagentcollector

import org.junit.Assert.*
import org.junit.Test

class MessageGuardTest {
    private fun flags(f: MessageGuard.Facts) = listOf(f.asksTransfer, f.requestsSecret, f.urgency, f.hasLink,
        f.offersReward, f.requestsRemoteControl, f.contextSufficient).joinToString("") { if (it) "1" else "0" }

    @Test fun matchesPythonReferenceOnGoldenCases() {
        for (case in goldenCases) {
            assertEquals(case.text, case.redacted, MessageGuard.redact(case.text))
            val facts = MessageGuard.extractFacts(case.text, "pasted")
            assertEquals(case.text, case.flags, flags(facts))
            assertEquals(case.text, case.impersonation, facts.impersonation)
            val assessment = MessageGuard.assess(facts)
            assertEquals(case.text, case.level, assessment.level.wire)
            assertEquals(case.text, case.codes, assessment.reasonCodes.joinToString(","))
        }
    }

    @Test fun knownContactNamesAreRedacted() {
        assertEquals("[NAME]說明天要來", MessageGuard.redact("美玲說明天要來", listOf("美玲")))
    }

    @Test fun excerptIsOptInAndWithheldForHighRisk() {
        val benign = "明天下午一起吃飯。"
        assertNull(MessageGuard.buildPacket(benign, "pasted", allowExcerpt = false).excerpt)
        assertEquals(benign, MessageGuard.buildPacket(benign, "pasted", allowExcerpt = true).excerpt)
        assertNull(MessageGuard.buildPacket("請立即轉帳到 SYN_ACCOUNT_PRIVATE_1，否則停權", "pasted", true).excerpt)
    }

    @Test fun packetJsonCarriesNoRawValues() {
        val raw = "王小明您好，請回撥 0912-345-678，驗證碼 482913，網址 https://x.example.com"
        val packet = MessageGuard.buildPacket(raw, "notification", allowExcerpt = true)
        for (leak in listOf("王小明", "0912", "482913", "example", "https")) {
            assertFalse(leak, packet.json.contains(leak))
        }
        assertTrue(packet.json.startsWith("{\"schema\":\"privagent.planner_packet.v1\""))
        assertTrue(packet.json.contains("\"source_kind\":\"notification\""))
        assertTrue(Regex("^TASK_[A-Z]{8}$").matches(packet.alias))
    }

    @Test fun excerptIsCutByCodePointsAndStaysValidUtf16() {
        val packet = MessageGuard.buildPacket("a".repeat(299) + "😀😀", "pasted", allowExcerpt = true)
        assertEquals(300, packet.excerpt!!.codePointCount(0, packet.excerpt!!.length))
        assertTrue(packet.excerpt!!.endsWith("😀"))
        // Round-trip through a real JSON parser: pairs survive, nothing is left half-encoded.
        assertEquals(packet.excerpt, org.json.JSONObject(packet.json).getString("redacted_excerpt"))
        val lone = MessageGuard.buildPacket("明天下午大家一起\ud800見面", "pasted", allowExcerpt = true)
        val excerpt = org.json.JSONObject(lone.json).getString("redacted_excerpt")
        assertFalse(excerpt.any { it.isSurrogate() })
        assertEquals(excerpt.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8), excerpt)
    }

    @Test fun aliasIsFreshPerTask() {
        val aliases = (1..50).map { MessageGuard.buildPacket("明天見", "pasted", false).alias }.toSet()
        assertEquals(50, aliases.size)
    }

    @Test fun quoteEscapesJsonSpecials() {
        assertEquals("\"a\\\"b\\\\c\\u000a\"", MessageGuard.quote("a\"b\\c\n"))
        assertEquals("\"\\ud800\"", MessageGuard.quote("\ud800"))
    }
}
