package com.example.privagentcollector

import java.security.SecureRandom
import java.text.Normalizer

/**
 * On-device redaction, risk features and planner packet for CHECK_MESSAGE.
 * Mirrors receiver/phone_sim.py rule for rule; MessageGuardTest pins both to the same golden cases.
 * The original text never leaves this object: only [Packet.json] is sent.
 */
object MessageGuard {
    const val PACKET_SCHEMA = "privagent.check_message_packet.v4"
    const val CLIENT_POLICY_VERSION = "phone-msg-4"
    const val MAX_EXCERPT_CHARS = 300
    val ALLOWED_SOPS = listOf("MESSAGE_CAUTION", "MESSAGE_VERIFY", "MESSAGE_LOW_SIGNAL", "MESSAGE_INSUFFICIENT", "ASK_USER")

    enum class Level(val wire: String) { LOW_SIGNAL("low_signal"), INSUFFICIENT("insufficient"), CAUTION("caution"), HIGH("high");
        companion object { fun fromWire(value: String) = entries.firstOrNull { it.wire == value } }
    }

    data class Facts(
        val asksTransfer: Boolean,
        val requestsSecret: Boolean,
        val urgency: Boolean,
        val hasLink: Boolean,
        /** On-device domain grade of the riskiest link; the URL itself never leaves the phone. */
        val linkKind: String,
        val offersReward: Boolean,
        val requestsRemoteControl: Boolean,
        val actionNegated: Boolean,
        val financialPromise: Boolean,
        val accountThreat: Boolean,
        val contactChange: Boolean,
        val antiScamContext: Boolean,
        val factCheckContext: Boolean,
        val directRequest: Boolean,
        val contextSufficient: Boolean,
        val impersonation: String,
        val messageStance: String,
        val requestedAction: String,
        val credentialTarget: String,
        val sourceKind: String
    )

    data class Assessment(val level: Level, val reasonCodes: List<String>)

    data class Packet(
        val alias: String,
        val requestNonce: String,
        val issuedAtMs: Long,
        val facts: Facts,
        val excerpt: String?,
        val json: String
    )

    private val syntheticKinds = mapOf(
        "ACCOUNT" to "[BANK_ACCOUNT]", "OTP" to "[OTP_CODE]", "NAME" to "[NAME]", "PHONE" to "[PHONE_NUMBER]",
        "ADDRESS" to "[ADDRESS]", "RECORD" to "[RECORD]", "ID" to "[NATIONAL_ID]", "PASSWORD" to "[SECRET]",
        "LOCATION" to "[ADDRESS]"
    )
    private const val SEP = """[\s\-./()]"""
    private val urlPattern = Regex("""(?i)(?:https?://|www\.)[^\s，。、「」！？]+""")
    private val bareDomainPattern = Regex("""(?i)\b[a-z0-9-]+(?:\.[a-z0-9-]+)*\.[a-z]{2,}\b(?:/[^\s，。、「」！？]*)?""")

    // The URL never leaves the phone ([LINK] in the excerpt, has_link in the facts). To tell a
    // phishing link from an official notification without disclosing the URL, each link is graded
    // locally and only the category is sent. Keep lists and order in sync with receiver/phone_sim.py.
    private val linkKinds = listOf("none", "known_official", "unknown", "shortener", "suspicious")
    // Registrable domains treated as official: all of gov.tw / edu.tw plus the retail banks elders
    // actually use. Matching is on whole DNS labels (host == entry or host ends with "." + entry), so
    // "post.gov.tw.verify.cc" is NOT official. Demo list, not a complete registry.
    // receiver/tests/test_link_lists.py pins these four lists to the Python reference.
    private val officialDomains = listOf(
        "gov.tw", "edu.tw", "bot.com.tw", "landbank.com.tw", "tcb-bank.com.tw", "firstbank.com.tw",
        "hncb.com.tw", "chb.com.tw", "megabank.com.tw", "tbb.com.tw", "ctbcbank.com", "cathaybk.com.tw",
        "esunbank.com.tw", "esunbank.com", "taishinbank.com.tw", "sinopac.com", "scsb.com.tw", "fubon.com",
        "linebank.com.tw", "ubot.com.tw", "skbank.com.tw", "yuantabank.com.tw", "kgibank.com.tw",
        "bankchb.com", "taipeifubon.com.tw", "o-bank.com"
    )
    // A URL shortener hides the destination, so it can never count as official.
    private val shortenerDomains = listOf(
        "bit.ly", "tinyurl.com", "tiny.cc", "goo.gl", "t.co", "is.gd", "t.ly", "cutt.ly", "rb.gy",
        "shorturl.at", "ow.ly", "buff.ly", "reurl.cc", "ppt.cc", "pse.is", "lihi.cc", "lihi1.com",
        "lihi2.net", "lurl.cc", "risu.io", "0rz.tw", "lin.ee"
    )
    // TLDs with near-zero legitimate use in zh-TW messages but heavy phishing abuse.
    private val highRiskTlds = setOf("tk", "ml", "ga", "cf", "gq", "xyz", "top", "icu", "vip", "buzz", "cfd", "sbs", "click")
    // Distinctive official tokens appearing inside a NON-official host mean a lookalike domain
    // ("gov-tw.com", "esunbank-tw.net"). Hosts are compared with dots and hyphens removed.
    private val lookalikeTokens = listOf(
        "govtw", "ctbcbank", "cathaybk", "esunbank", "megabank", "taishinbank", "landbank",
        "hncb", "sinopac", "firstbank", "tcbbank", "scsb", "fubon"
    )
    // Generic words count only as a whole dot/hyphen segment ("165-gov.com", "my-bank-login.com"):
    // as substrings they would flag citibank.com.tw or shop1650.com.tw.
    private val lookalikeSegments = listOf("165", "bank")
    // zh-TW messages often run a URL straight into text ("請至https://www.post.gov.tw查詢"). A complete
    // ASCII host followed by CJK text ends the link there; a host that is itself non-ASCII stays whole.
    private val cjkText = Regex("""[\u3000-\u303f\u3400-\u9fff\uff00-\uffef]""")
    private val asciiHost = Regex("""(?i)[a-z0-9-]+(?:\.[a-z0-9-]+)*\.[a-z]{2,}(?::[0-9]+)?""")
    // Order matters: specific identifiers first, generic number runs last. Input is NFKC-normalised.
    // Do not use Java's inline (?U) flag here: Android's ICU regex engine rejects it. Character
    // classes are explicit where Unicode letters/numbers matter so these patterns behave on both.
    private val redactions: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        Regex("""SYN_(?:V5_)?([A-Z]+)_PRIVATE_[A-Z0-9_]+""") to { m -> syntheticKinds[m.groupValues[1]] ?: "[SECRET]" },
        Regex("""SYN_[A-Z0-9_]+""") to { _ -> "[SECRET]" },
        Regex("""[\p{L}\p{N}_.+-]+@[\p{L}\p{N}_-]+\.[\p{L}\p{N}_.-]+""") to { _ -> "[EMAIL]" },
        urlPattern to { _ -> "[LINK]" },
        bareDomainPattern to { _ -> "[LINK]" },
        Regex("""\b[A-Z][12][0-9]{8}\b""") to { _ -> "[NATIONAL_ID]" },
        Regex("""((?:帳號|帳戶|匯到|轉入|轉至)[^0-9\[\]\n]{0,8})[0-9](?:$SEP{0,2}[0-9]){3,}""") to
            { m -> m.groupValues[1] + "[BANK_ACCOUNT]" },
        Regex("""(?i)(?:(?:NT\$|TWD|新臺幣|台幣|\$)\s*)?[0-9][0-9,.\s]{2,}(?=\s*(?:元|塊|萬))""") to
            { _ -> "[AMOUNT]" },
        Regex("""(?i)((?:驗證碼|認證碼|OTP|密碼|控制碼|代碼)[^\[\]A-Za-z0-9]{0,6}?)(?=[A-Za-z]*[0-9])[A-Za-z0-9]{4,10}\b""") to
            { m -> m.groupValues[1] + "[OTP_CODE]" },
        Regex("""(?<![0-9])(?:\+?886$SEP*|0)9(?:$SEP*[0-9]){8}(?![0-9])""") to { _ -> "[PHONE_NUMBER]" },
        Regex("""(?<![0-9])(?:\+?886$SEP*|0)[2-8](?:$SEP*[0-9]){6,8}(?![0-9])""") to { _ -> "[PHONE_NUMBER]" },
        Regex("""(?<![0-9])[0-9](?:$SEP{0,2}[0-9]){3,}""") to { _ -> "[NUMBER]" }
    )
    // Regexes cannot recognise arbitrary names: catch salutations and the contact names the phone knows.
    private val salutation = Regex("""[\x{4e00}-\x{9fff}]{2,4}(?=(您好|你好|先生|小姐|女士|阿姨|伯伯|奶奶|爺爺|阿公|阿嬤))""")

    // Negation covers only the action right after it and never crosses 請 or punctuation.
    private const val ACTIONS = "(?:付款|轉帳|匯款|支付|繳費|告訴|提供|分享|輸入|給)"
    private val negatedAction = Regex(
        // "不會要求民眾提供" is how anti-scam notices phrase it; keep in sync with phone_sim.py.
        "(?:不需要|不需|不用|無需|毋需|不必|請勿|不要|切勿|勿|別|" +
            "(?:絕不會|不會|從不|不得)(?:要求|請)?(?:你|您|民眾|大眾|客戶|用戶)?)(?:再|去|隨便|任意|向?(?:任何人|他人|別人))?" +
            ACTIONS + "(?:(?:或|和|、|及)" + ACTIONS + ")*"
    )
    // Reported speech describes someone else's request ("如有人要求您匯款至安全帳戶，請勿照做"); it is
    // removed before detection like a negated action. Keep in sync with phone_sim.py.
    private val reportedRequest = Regex(
        "(?:如有人|若有人|如果有人|若接到|如接到|假冒|冒充|自稱|詐騙集團|詐團|歹徒|騙徒|詐騙者).{0,16}" +
            "要求(?:你|您|民眾|被害人|受害者|長輩)?(?:立即|立刻|馬上)?" + ACTIONS
    )
    // A fact check quotes the scam it debunks; only the quoted text is excused, not the whole message.
    private val quotedSpan = Regex("[「『“\"][^」』”\"]{1,120}[」』”\"]")
    private val secretWords = Regex("(?i)(驗證碼|認證碼|OTP|密碼|控制碼|提款卡|信用卡號|卡號|身分證)")
    private val requestVerbs = Regex("(提供|告訴|寄給|給我|傳給|回傳|輸入|回覆|念給|唸給)")
    // Explicit [^\n] matches Python's dot behaviour and avoids another platform-specific flag.
    private val credentialPhishing = Regex("(帳戶|帳號|會員)[^\n]{0,12}(驗證|登入|解鎖)[^\n]{0,20}(輸入|填寫)[^\n]{0,6}(資料|個資)")
    private val asksTransfer = Regex("(轉帳|匯款|先匯|匯到|付款|支付|繳費|繳納|ATM|轉入|儲值)")
    private val urgency = Regex("(立即|立刻|馬上|盡快|趕快|緊急|否則|限時|今天內|停權|凍結|處分|逾期|最後機會)")
    private val offersReward = Regex("(中獎|獎金|保證獲利|高報酬|穩賺|免費領|紅利|抽中)")
    private val remoteControl = Regex("(遠端控制|遠端軟體|遠端協助|螢幕分享|控制碼)")
    private val financialPromise = Regex("(保證獲利|高報酬|穩賺|零風險|老師帶單|投資群組|內線消息|翻倍獲利)")
    private val accountThreat = Regex("((帳戶|帳號|會員).{0,12}(異常|凍結|停用|停權|鎖定|被盜)|扣款失敗|交易失敗|驗證失敗)")
    private val contactChange = Regex("(換(了)?(新)?(手機)?號碼|這是我的新號碼|加(我|新的?)(LINE|line)|改用.{0,8}(聯絡|帳號))")
    private val antiScamContext = Regex("(警方|警政署|165|反詐騙|反詐).{0,12}(提醒|呼籲|宣導|表示)")
    private val factCheckContext = Regex("(事實查核|查核結果|謠言|假消息|不實訊息|網傳|新聞報導|媒體報導|轉傳內容|有人收到)")
    private val directRequest = Regex(
        "((請|麻煩|務必|記得|幫我|需要你|立刻|立即|趕快).{0,32}" +
            "(轉帳|匯款|付款|支付|繳費|繳納|提供|告訴|輸入|回覆|點擊|登入|安裝|下載|聯絡|回電|加入|加我)|" +
            "(轉帳|匯款|付款|支付|繳費|繳納).{0,8}(給我|給我們|到|至|帳戶|帳號)|" +
            "(加入.{0,8}(投資|群組)|老師帶單|私訊.{0,8}(領取|獲利)))"
    )
    private val clickAction = Regex("(點擊|點選|點|開啟|登入|進入|查看|上).{0,12}(\\[LINK\\]|\\[OFFICIAL_URL\\]|\\[SHORT_URL\\]|\\[SUSPICIOUS_URL\\])")
    private val contactAction = Regex("(回電|聯絡|回覆|加我|加入.{0,6}(LINE|line|群組)|私訊)")
    private val noticeContext = Regex("(通知|公告|提醒|帳單|繳費單|包裹|訂單|交易紀錄)")
    private val impersonation = listOf(
        "family" to Regex("(我是(你的?)?(家人|兒子|女兒|孫子|孫女|媳婦|女婿)|換(了)?(新)?(手機)?號碼)"),
        "official" to Regex("(我是(警察|檢察官|法官|法院|機構人員|公務員|政府)|警察局|檢察署|地檢署)"),
        "support" to Regex("(我是(客服|銀行|專員|電信)|客服人員)")
    )
    private val vague = Regex("(細節還沒|還沒有說明|稍後說明|之後再說|晚點再說)")
    private val filler = Regex("""^(這是虛構訊息。|虛構測試內容：|虛構[\p{L}\p{N}_]*通知：)""")
    private val random = SecureRandom()
    // Python's \s also covers U+001C..U+001F, Java's does not; both implementations map them to spaces.
    private val separatorControls = Regex("[\u001c-\u001f]")
    // Formatting controls can be inserted inside identifiers or scam keywords to evade matching.
    // They carry no meaning for CHECK_MESSAGE; keep this range in sync with receiver/phone_sim.py.
    private val invisibleFormatControls = Regex("[\u200b-\u200f\u202a-\u202e\u2060-\u206f\uFEFF]")
    private val detectionWhitespace = Regex("\\s+")
    private val hanGap = Regex(
        "(?<=[\\x{3400}-\\x{9fff}])(?:[^\\w\\x{3400}-\\x{9fff}\\[\\]，。！？,!?;；:：]|_){1,3}" +
            "(?=[\\x{3400}-\\x{9fff}])"
    )

    private fun normalize(text: String): String {
        // Unpaired surrogates cannot be encoded as UTF-8; replace them before anything else.
        val clean = buildString(text.length) {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                    append(c).append(text[i + 1]); i += 2; continue
                }
                append(if (c.isSurrogate()) '\uFFFD' else c); i++
            }
        }
        val normalized = separatorControls.replace(Normalizer.normalize(clean, Normalizer.Form.NFKC), " ")
        return invisibleFormatControls.replace(normalized, "")
    }

    private fun codePoints(text: String) = text.codePointCount(0, text.length)

    private fun takeCodePoints(text: String, limit: Int): String =
        if (codePoints(text) <= limit) text else text.substring(0, text.offsetByCodePoints(0, limit))

    /** Redacts like [redact] and also returns what the two link patterns matched, so link
     *  classification sees exactly the links that became [LINK] (never an email already redacted). */
    private fun redactCollectingLinks(text: String, knownNames: Collection<String> = emptyList()): Pair<String, List<String>> {
        var result = normalize(text)
        for (name in knownNames.filter { it.isNotBlank() }.sortedByDescending { it.length }) {
            result = result.replace(normalize(name), "[NAME]")
        }
        val links = mutableListOf<String>()
        for ((pattern, replacement) in redactions) {
            result = if (pattern === urlPattern || pattern === bareDomainPattern) {
                pattern.replace(result) { m ->
                    val (link, rest) = splitLinkText(m.value)
                    links += link
                    // The text after the link stays visible to the later patterns and to detection.
                    when (classifyLink(link)) {
                        "known_official" -> "[OFFICIAL_URL]"
                        "shortener" -> "[SHORT_URL]"
                        "suspicious" -> "[SUSPICIOUS_URL]"
                        else -> "[LINK]"
                    } + rest
                }
            } else {
                pattern.replace(result, replacement)
            }
        }
        return salutation.replace(result, "[NAME]") to links
    }

    /** Splits "https://host.tw查詢進度" into the link and the text glued after it. */
    internal fun splitLinkText(match: String): Pair<String, String> {
        val cjk = cjkText.find(match) ?: return match to ""
        // Sentence punctuation belongs to the text.
        val head = match.substring(0, cjk.range.first).trimEnd(',', '.', ';', ':', '!', '?', ')', '\'', '"')
        val host = head.replaceFirst(Regex("(?i)^https?://"), "").split('/', '?', '#').first()
        return if (asciiHost.matches(host)) head to match.substring(head.length) else match to ""
    }

    fun redact(text: String, knownNames: Collection<String> = emptyList()): String =
        redactCollectingLinks(text, knownNames).first

    /** Grades one matched link; only the category ever leaves the phone, never the URL. */
    internal fun classifyLink(link: String): String {
        val stripped = link.trim().lowercase().replaceFirst(Regex("^https?://"), "")
        var host = stripped.split('/', '?', '#').first()
        // "https://www.gov.tw@evil.example/": the real host is after the @ (seen as "[email]" after redaction).
        if ('@' in host || host.startsWith("[email]")) return "suspicious"
        if (host.startsWith("[")) return "suspicious" // Raw IPv6 literal.
        host = host.substringBefore(':').trim { it.code < 128 && !it.isLetterOrDigit() }
        if (host.isEmpty()) return "unknown"
        if (host.any { it.code > 127 }) return "suspicious" // Non-ASCII host: IDN/homoglyph risk.
        val labels = host.split('.').filter { it.isNotEmpty() }
        if (labels.isNotEmpty() && labels.all { label -> label.all { it.isDigit() } }) return "suspicious" // IPv4.
        if (officialDomains.any { host == it || host.endsWith(".$it") }) return "known_official"
        if (labels.any { it.startsWith("xn--") }) return "suspicious" // Punycode outside the whitelist.
        val compact = host.replace(".", "").replace("-", "")
        val segments = host.split('.', '-')
        if (lookalikeTokens.any { compact.contains(it) } || segments.any { it in lookalikeSegments }) return "suspicious" // Lookalike of an official domain.
        if (labels.isNotEmpty() && labels.last() in highRiskTlds) return "suspicious"
        if (shortenerDomains.any { host == it || host.endsWith(".$it") }) return "shortener"
        return "unknown"
    }

    /** The packet carries one category: the most severe among all links in the message. */
    internal fun classifyLinks(links: List<String>): String =
        links.map(::classifyLink).maxByOrNull { linkKinds.indexOf(it) } ?: "none"

    fun extractFacts(original: String, sourceKind: String): Facts {
        val (redacted, links) = redactCollectingLinks(original)
        // Feature matching uses a compact view so spacing cannot hide "匯 款" or "驗 證 碼".
        // The redacted excerpt keeps its original readable spacing.
        var detectionText = detectionWhitespace.replace(hanGap.replace(redacted, ""), "")
        val factCheckFraming = factCheckContext.containsMatchIn(detectionText)
        // Quote marks sit between Han characters, so drop the quoted scam before hanGap eats the marks.
        if (factCheckFraming) detectionText = detectionWhitespace.replace(hanGap.replace(quotedSpan.replace(redacted, ""), ""), "")
        val actionWasNegated = negatedAction.containsMatchIn(detectionText)
        val withoutNegation = reportedRequest.replace(negatedAction.replace(detectionText, ""), "")
        val core = filler.replace(redacted, "").trim()
        val asksForTransfer = asksTransfer.containsMatchIn(withoutNegation)
        val asksForSecret = (secretWords.containsMatchIn(withoutNegation) && requestVerbs.containsMatchIn(withoutNegation)) ||
            credentialPhishing.containsMatchIn(detectionText)
        val promise = financialPromise.containsMatchIn(withoutNegation)
        val organizationWarning = antiScamContext.containsMatchIn(detectionText)
        val isDirect = directRequest.containsMatchIn(withoutNegation) || clickAction.containsMatchIn(withoutNegation) ||
            credentialPhishing.containsMatchIn(withoutNegation) || asksForSecret || (promise && !factCheckFraming)
        // A "165 提醒" or "網傳／事實查核" header is only a warning or report when nothing still asks the
        // reader to act (negated, reported and quoted phrases were removed above); otherwise
        // "【165反詐騙提醒】…請立即點擊…" and "【網傳】請立即匯款到…" passed as low_signal.
        val antiScam = (organizationWarning || actionWasNegated) && !isDirect
        val factCheck = factCheckFraming && !isDirect
        val stance = when {
            antiScam -> "warning"
            factCheck -> "report"
            isDirect -> "direct_request"
            noticeContext.containsMatchIn(detectionText) -> "notice"
            else -> "general"
        }
        val requested = mutableListOf<String>()
        if (asksForTransfer && isDirect) requested += "transfer"
        if (asksForSecret && isDirect) requested += "share_secret"
        if (links.isNotEmpty() && clickAction.containsMatchIn(detectionText) && isDirect) requested += "open_link"
        if (remoteControl.containsMatchIn(withoutNegation) && isDirect) requested += "install_remote"
        if (contactAction.containsMatchIn(withoutNegation) && isDirect) requested += "contact"
        val requestedAction = requested.distinct().let { if (it.isEmpty()) "none" else if (it.size == 1) it.first() else "multiple" }
        val credentials = mutableListOf<String>()
        if (Regex("(?i)(\\[OTP_CODE\\]|驗證碼|認證碼|OTP|控制碼)").containsMatchIn(detectionText)) credentials += "otp"
        if (Regex("(密碼|\\[SECRET\\])").containsMatchIn(detectionText)) credentials += "password"
        if (Regex("(信用卡|卡號|提款卡)").containsMatchIn(detectionText)) credentials += "card"
        if (Regex("(身分證|\\[NATIONAL_ID\\])").containsMatchIn(detectionText)) credentials += "identity"
        if (Regex("(帳號|帳戶|\\[BANK_ACCOUNT\\])").containsMatchIn(detectionText)) credentials += "account"
        val credentialTarget = credentials.distinct().let { if (it.isEmpty()) "none" else if (it.size == 1) it.first() else "multiple" }
        return Facts(
            asksTransfer = asksForTransfer,
            requestsSecret = asksForSecret,
            urgency = urgency.containsMatchIn(withoutNegation),
            // Both come from the same matches, so they can never disagree (a literal
            // "[LINK]" typed by the sender is not a link).
            hasLink = links.isNotEmpty(),
            linkKind = classifyLinks(links),
            offersReward = offersReward.containsMatchIn(withoutNegation),
            requestsRemoteControl = remoteControl.containsMatchIn(withoutNegation),
            actionNegated = actionWasNegated,
            financialPromise = promise,
            accountThreat = accountThreat.containsMatchIn(withoutNegation),
            contactChange = contactChange.containsMatchIn(withoutNegation),
            antiScamContext = antiScam,
            factCheckContext = factCheck,
            directRequest = isDirect,
            contextSufficient = !vague.containsMatchIn(core) && codePoints(core) >= 8,
            impersonation = impersonation.firstOrNull { it.second.containsMatchIn(detectionText) }?.first ?: "none",
            messageStance = stance,
            requestedAction = requestedAction,
            credentialTarget = credentialTarget,
            sourceKind = sourceKind
        )
    }

    /** Same rules as receiver/assistant.py rule_assessment; used offline and as the local floor. */
    fun assess(f: Facts): Assessment {
        val codes = mutableListOf<String>()
        if (f.asksTransfer) codes += "ASKS_TRANSFER"
        if (f.requestsSecret) codes += "REQUESTS_SECRET"
        if (f.urgency) codes += "URGENCY"
        if (f.hasLink) codes += "HAS_LINK"
        if (f.linkKind == "suspicious") codes += "LINK_SUSPICIOUS"
        if (f.linkKind == "shortener") codes += "LINK_SHORTENER"
        if (f.offersReward) codes += "OFFERS_REWARD"
        if (f.requestsRemoteControl) codes += "REMOTE_CONTROL"
        if (f.actionNegated) codes += "ACTION_NEGATED"
        if (f.financialPromise) codes += "FINANCIAL_PROMISE"
        if (f.accountThreat) codes += "ACCOUNT_THREAT"
        if (f.contactChange) codes += "CONTACT_CHANGE"
        if (f.antiScamContext) codes += "ANTI_SCAM_CONTEXT"
        if (f.factCheckContext) codes += "FACT_CHECK_CONTEXT"
        val impersonating = f.impersonation != "none"
        if (impersonating) codes += "IMPERSONATION_" + f.impersonation.uppercase()
        // A link to a whitelisted official domain no longer escalates on its own; a suspicious domain
        // (lookalike, IP literal, punycode or high-risk TLD) is decisive even in a calm message, and no
        // warning or news framing cancels it: a live phishing link is dangerous to tap whatever surrounds it.
        val riskyLink = f.hasLink && f.linkKind != "known_official"
        val safeContext = f.antiScamContext || f.factCheckContext || f.messageStance in setOf("warning", "report")
        val direct = f.directRequest && !safeContext
        if ((direct && (f.asksTransfer || f.requestsSecret || f.requestsRemoteControl)) ||
            f.linkKind == "suspicious" ||
            (direct && riskyLink && (f.urgency || f.offersReward || impersonating)) ||
            (direct && impersonating && (f.urgency || f.contactChange)) ||
            (direct && f.financialPromise)) return Assessment(Level.HIGH, codes)
        if (!f.contextSufficient) return Assessment(Level.INSUFFICIENT, codes + "CONTEXT_INSUFFICIENT")
        val riskSignal = f.asksTransfer || f.requestsSecret || f.urgency || f.hasLink || f.offersReward ||
            f.requestsRemoteControl || impersonating || f.financialPromise || f.accountThreat || f.contactChange
        if (riskSignal && !safeContext) return Assessment(Level.CAUTION, codes)
        val safeCodes = if (safeContext) codes.filter { it in setOf("ACTION_NEGATED", "ANTI_SCAM_CONTEXT", "FACT_CHECK_CONTEXT") } else codes
        return Assessment(Level.LOW_SIGNAL, safeCodes.ifEmpty { listOf("NO_STRONG_SIGNAL") })
    }

    fun buildPacket(
        original: String,
        sourceKind: String,
        allowExcerpt: Boolean,
        knownNames: Collection<String> = emptyList()
    ): Packet {
        val facts = extractFacts(original, sourceKind)
        // Blueprint ch.9: semantics only when local features are not decisive, and only if the user opted in.
        val excerpt = if (allowExcerpt && assess(facts).level != Level.HIGH)
            takeCodePoints(redact(original, knownNames), MAX_EXCERPT_CHARS).ifEmpty { null } else null
        val alias = "TASK_" + String(CharArray(8) { ('A' + random.nextInt(26)) })
        val nonce = "REQ_" + String(CharArray(20) { ('A' + random.nextInt(26)) })
        val issuedAtMs = System.currentTimeMillis()
        val json = buildString {
            append("{\"schema\":").append(quote(PACKET_SCHEMA))
            append(",\"task_id_alias\":").append(quote(alias))
            append(",\"request_nonce\":").append(quote(nonce))
            append(",\"issued_at_ms\":").append(issuedAtMs)
            append(",\"intent\":\"CHECK_MESSAGE\",\"locale\":\"zh-TW\",\"cloud_consent\":true")
            append(",\"facts\":{")
            append("\"asks_transfer\":").append(facts.asksTransfer)
            append(",\"requests_secret\":").append(facts.requestsSecret)
            append(",\"urgency\":").append(facts.urgency)
            append(",\"has_link\":").append(facts.hasLink)
            append(",\"link_kind\":").append(quote(facts.linkKind))
            append(",\"offers_reward\":").append(facts.offersReward)
            append(",\"requests_remote_control\":").append(facts.requestsRemoteControl)
            append(",\"action_negated\":").append(facts.actionNegated)
            append(",\"financial_promise\":").append(facts.financialPromise)
            append(",\"account_threat\":").append(facts.accountThreat)
            append(",\"contact_change\":").append(facts.contactChange)
            append(",\"anti_scam_context\":").append(facts.antiScamContext)
            append(",\"fact_check_context\":").append(facts.factCheckContext)
            append(",\"direct_request\":").append(facts.directRequest)
            append(",\"context_sufficient\":").append(facts.contextSufficient)
            append(",\"impersonation\":").append(quote(facts.impersonation))
            append(",\"message_stance\":").append(quote(facts.messageStance))
            append(",\"requested_action\":").append(quote(facts.requestedAction))
            append(",\"credential_target\":").append(quote(facts.credentialTarget))
            append(",\"source_kind\":").append(quote(facts.sourceKind))
            append("},\"redacted_excerpt\":").append(excerpt?.let(::quote) ?: "null")
            append(",\"allowed_sops\":[").append(ALLOWED_SOPS.joinToString(",", transform = ::quote)).append("]")
            append(",\"client_policy_version\":").append(quote(CLIENT_POLICY_VERSION)).append("}")
        }
        return Packet(alias, nonce, issuedAtMs, facts, excerpt, json)
    }

    internal fun quote(value: String): String = buildString {
        append('"')
        for (c in value) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' || c.isSurrogate() -> append(String.format("\\u%04x", c.code))
            else -> append(c)
        }
        append('"')
    }

    val advice = mapOf(
        Level.HIGH to "高風險：這則訊息有常見的詐騙特徵。不要匯款、不要提供驗證碼或密碼、不要點連結。" +
            "請用你原本就知道的電話，直接問家人或官方客服。",
        Level.CAUTION to "需要核實：發現可疑訊號，但證據不足以直接判定高風險。" +
            "如果跟錢或個人資料有關，請先用原本的管道確認。",
        Level.LOW_SIGNAL to "未發現明顯風險訊號，但這不代表一定安全。遇到金錢、密碼或陌生連結仍請先確認。",
        Level.INSUFFICIENT to "資訊不足：這則訊息的內容不夠判斷。請先不要回應，可以問家人，或把完整訊息給我再看一次。"
    )
    val reasonText = mapOf(
        "ASKS_TRANSFER" to "要求轉帳或付款", "REQUESTS_SECRET" to "索取驗證碼、密碼或個人資料",
        "URGENCY" to "催促你馬上處理", "HAS_LINK" to "含有連結",
        "LINK_SUSPICIOUS" to "連結疑似假冒官方、IP 直連或高風險網域",
        "LINK_SHORTENER" to "使用短網址，看不出真正的網站",
        "IMPERSONATION_FAMILY" to "自稱家人或換了號碼",
        "IMPERSONATION_OFFICIAL" to "自稱警察或政府機關", "IMPERSONATION_SUPPORT" to "自稱客服或銀行",
        "OFFERS_REWARD" to "提到中獎或保證獲利", "REMOTE_CONTROL" to "要求遠端控制手機",
        "ACTION_NEGATED" to "內容是在提醒不要執行高風險行動", "FINANCIAL_PROMISE" to "出現保證獲利或投資帶單話術",
        "ACCOUNT_THREAT" to "以帳戶異常或停權施壓", "CONTACT_CHANGE" to "要求改用新號碼或其他帳號聯絡",
        "ANTI_SCAM_CONTEXT" to "內容屬於反詐提醒", "FACT_CHECK_CONTEXT" to "內容屬於新聞、引用或事實查核",
        "CONTEXT_INSUFFICIENT" to "內容太少，無法判斷", "NO_STRONG_SIGNAL" to "沒有發現明顯詐騙特徵",
        "AI_SCAM_PATTERN" to "AI 覆核後仍發現詐騙語意", "AI_BENIGN_CONTEXT" to "AI 辨識為一般通知、反詐或查核內容"
    )
    private val factText = listOf<Pair<String, (Facts) -> Boolean>>(
        "要求轉帳或付款" to { it.asksTransfer }, "索取驗證碼或密碼" to { it.requestsSecret },
        "催促馬上處理" to { it.urgency }, "含有連結" to { it.hasLink }, "提到中獎或獲利" to { it.offersReward },
        "要求遠端控制" to { it.requestsRemoteControl }
    )

    /** Plain-language list of exactly what the packet discloses, shown before sending. */
    fun describe(packet: Packet): String = buildString {
        val f = packet.facts
        val found = factText.filter { it.second(f) }.map { it.first }
        append("偵測到的特徵：").append(if (found.isEmpty()) "無" else found.joinToString("、"))
        append("\n冒充身分：").append(mapOf("none" to "無", "family" to "家人", "official" to "政府或警察",
            "support" to "客服或銀行")[f.impersonation])
        append("\n連結網域分級：").append(mapOf("none" to "無連結", "known_official" to "政府或銀行官方網域",
            "unknown" to "無法辨識的網域", "shortener" to "短網址", "suspicious" to "可疑網域")[f.linkKind])
        append("\n內容是否足夠判斷：").append(if (f.contextSufficient) "是" else "否")
        append("\n訊息立場：").append(mapOf("direct_request" to "直接要求行動", "warning" to "警告或反詐提醒",
            "report" to "新聞、引用或查核", "notice" to "一般通知", "general" to "一般內容", "unknown" to "未知")[f.messageStance])
        append("\n要求的動作：").append(mapOf("none" to "無", "transfer" to "轉帳", "share_secret" to "提供憑證",
            "open_link" to "開啟連結", "install_remote" to "安裝遠端工具", "contact" to "改用其他管道聯絡",
            "multiple" to "多項動作")[f.requestedAction])
        append("\n遮蔽後摘錄：").append(packet.excerpt ?: "（不傳送）")
    }
}
