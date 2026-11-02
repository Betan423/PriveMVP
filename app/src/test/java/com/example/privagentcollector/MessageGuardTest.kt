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
            assertEquals(case.text, case.linkKind, facts.linkKind)
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
        assertTrue(packet.json.startsWith("{\"schema\":\"privagent.check_message_packet.v3\""))
        assertTrue(packet.json.contains("\"link_kind\":\"unknown\""))
        assertTrue(packet.json.contains("\"source_kind\":\"notification\""))
        assertTrue(Regex("^TASK_[A-Z]{8}$").matches(packet.alias))
        assertTrue(Regex("^REQ_[A-Z]{20}$").matches(packet.requestNonce))
        assertTrue(packet.issuedAtMs > 0)
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
        val packets = (1..50).map { MessageGuard.buildPacket("明天見", "pasted", false) }
        val aliases = packets.map { it.alias }.toSet()
        val nonces = packets.map { it.requestNonce }.toSet()
        assertEquals(50, aliases.size)
        assertEquals(50, nonces.size)
    }

    @Test fun quoteEscapesJsonSpecials() {
        assertEquals("\"a\\\"b\\\\c\\u000a\"", MessageGuard.quote("a\"b\\c\n"))
        assertEquals("\"\\ud800\"", MessageGuard.quote("\ud800"))
    }

    @Test fun spacingAndFormatControlsCannotHideScamSignals() {
        val attacks = listOf(
            "請立即匯 款給我",
            "請立即匯\u200b款給我",
            "請提 供驗 證 碼給我",
            "我是客\u2060服，請提供驗證碼給我"
        )
        for (text in attacks) {
            assertEquals(text, MessageGuard.Level.HIGH, MessageGuard.assess(MessageGuard.extractFacts(text, "pasted")).level)
        }
        assertFalse(MessageGuard.extractFacts("請 勿 提 供 驗 證 碼", "pasted").requestsSecret)
    }

    @Test fun punctuationAndSymbolsCannotHideScamSignals() {
        for (text in listOf("請立即轉-帳給我", "請立即匯💸款給我", "請提／供驗·證·碼給我")) {
            assertEquals(text, MessageGuard.Level.HIGH, MessageGuard.assess(MessageGuard.extractFacts(text, "pasted")).level)
        }
    }

    @Test fun formatControlsCannotHideIdentifiers() {
        val phone = MessageGuard.redact("電話 0912\u200b345678")
        val url = MessageGuard.redact("網址 https://exa\u2060mple.invalid/login")
        assertFalse(phone.contains("0912"))
        assertTrue(phone.contains("[PHONE]"))
        assertFalse(url.contains("example"))
        assertTrue(url.contains("[LINK]"))
    }

    @Test fun linkKindIsClassifiedOnDevice() {
        val cases = mapOf(
            "請上 https://www.post.gov.tw/track 查詢進度" to "known_official",
            "請上 https://esunbank.com.tw/login 查詢" to "known_official",
            "請點 https://taiwanpost-verify.xyz/login 補填資料" to "suspicious",
            "請點 https://post.gov.tw.verify.cc/login" to "suspicious",
            "請點 https://192.168.0.99/login" to "suspicious",
            "請點 https://xn--fiq228c.com/x" to "suspicious",
            "詳情 https://reurl.cc/abc 查看" to "shortener",
            "詳情 https://news.example.invalid/article" to "unknown",
            "明天下午一起吃飯。" to "none"
        )
        for ((text, expected) in cases) {
            val facts = MessageGuard.extractFacts(text, "pasted")
            assertEquals(text, expected, facts.linkKind)
            assertEquals(text, expected != "none", facts.hasLink)
        }
    }

    @Test fun mostSevereLinkKindWinsAndLiteralPlaceholderIsNotALink() {
        val mixed = MessageGuard.extractFacts("官方 https://www.gov.tw 公告，另見 https://promo.bad.xyz/go", "pasted")
        assertEquals("suspicious", mixed.linkKind)
        val literal = MessageGuard.extractFacts("對方傳來的內容是 [LINK] 這五個字，我沒有看到網址。", "pasted")
        assertFalse(literal.hasLink)
        assertEquals("none", literal.linkKind)
    }

    @Test fun officialLinkNoLongerEscalatesButSuspiciousLinkIsDecisive() {
        val official = MessageGuard.assess(MessageGuard.extractFacts(
            "您的包裹已到，請上 https://www.post.gov.tw/track 查詢，今天內未處理將退回", "pasted"))
        assertEquals(MessageGuard.Level.VERIFY, official.level)
        assertTrue(official.reasonCodes.contains("HAS_LINK"))
        val phishing = MessageGuard.assess(MessageGuard.extractFacts(
            "您的包裹已到，請點 https://taiwanpost-verify.xyz/login 補填收件資料", "pasted"))
        assertEquals(MessageGuard.Level.HIGH, phishing.level)
        assertTrue(phishing.reasonCodes.contains("LINK_SUSPICIOUS"))
        val shortener = MessageGuard.assess(MessageGuard.extractFacts(
            "您的包裹已到，請點 https://reurl.cc/abc 補填，今天內未處理將退回", "pasted"))
        assertEquals(MessageGuard.Level.HIGH, shortener.level)
        assertTrue(shortener.reasonCodes.contains("LINK_SHORTENER"))
    }
}
