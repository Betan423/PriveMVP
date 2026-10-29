package com.example.privagentcollector

import java.security.SecureRandom
import java.text.Normalizer

/**
 * On-device redaction, risk features and planner packet for CHECK_MESSAGE.
 * Mirrors receiver/phone_sim.py rule for rule; MessageGuardTest pins both to the same golden cases.
 * The original text never leaves this object: only [Packet.json] is sent.
 */
object MessageGuard {
    const val PACKET_SCHEMA = "privagent.planner_packet.v1"
    const val CLIENT_POLICY_VERSION = "phone-msg-1"
    const val MAX_EXCERPT_CHARS = 300
    val ALLOWED_SOPS = listOf("MESSAGE_CAUTION", "MESSAGE_VERIFY", "MESSAGE_INSUFFICIENT", "ASK_USER")

    enum class Level(val wire: String) { INSUFFICIENT("insufficient"), VERIFY("verify"), HIGH("high");
        companion object { fun fromWire(value: String) = entries.firstOrNull { it.wire == value } }
    }

    data class Facts(
        val asksTransfer: Boolean,
        val requestsSecret: Boolean,
        val urgency: Boolean,
        val hasLink: Boolean,
        val offersReward: Boolean,
        val requestsRemoteControl: Boolean,
        val contextSufficient: Boolean,
        val impersonation: String,
        val sourceKind: String
    )

    data class Assessment(val level: Level, val reasonCodes: List<String>)

    data class Packet(val alias: String, val facts: Facts, val excerpt: String?, val json: String)

    private val syntheticKinds = mapOf(
        "ACCOUNT" to "[ACCOUNT]", "OTP" to "[OTP]", "NAME" to "[NAME]", "PHONE" to "[PHONE]",
        "ADDRESS" to "[ADDRESS]", "RECORD" to "[RECORD]", "ID" to "[ID]", "PASSWORD" to "[SECRET]",
        "LOCATION" to "[ADDRESS]"
    )
    private const val SEP = """[\s\-./()]"""
    // Order matters: specific identifiers first, generic number runs last. Input is NFKC-normalised.
    private val redactions: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        Regex("""(?U)SYN_(?:V5_)?([A-Z]+)_PRIVATE_[A-Z0-9_]+""") to { m -> syntheticKinds[m.groupValues[1]] ?: "[SECRET]" },
        Regex("""(?U)SYN_[A-Z0-9_]+""") to { _ -> "[SECRET]" },
        Regex("""(?U)[\w.+-]+@[\w-]+\.[\w.]+""") to { _ -> "[EMAIL]" },
        Regex("""(?iU)(?:https?://|www\.)[^\s，。、「」！？]+""") to { _ -> "[LINK]" },
        Regex("""(?iU)\b[a-z0-9-]+(?:\.[a-z0-9-]+)*\.[a-z]{2,}\b(?:/[^\s，。、「」！？]*)?""") to { _ -> "[LINK]" },
        Regex("""(?U)\b[A-Z][12][0-9]{8}\b""") to { _ -> "[ID]" },
        Regex("""(?iU)((?:驗證碼|認證碼|OTP|密碼|控制碼|代碼)[^\[\]A-Za-z0-9]{0,6}?)(?=[A-Za-z]*[0-9])[A-Za-z0-9]{4,10}\b""") to
            { m -> m.groupValues[1] + "[OTP]" },
        Regex("""(?U)(?<![0-9])(?:\+?886$SEP*|0)9(?:$SEP*[0-9]){8}(?![0-9])""") to { _ -> "[PHONE]" },
        Regex("""(?U)(?<![0-9])(?:\+?886$SEP*|0)[2-8](?:$SEP*[0-9]){6,8}(?![0-9])""") to { _ -> "[PHONE]" },
        Regex("""(?U)(?<![0-9])[0-9](?:$SEP{0,2}[0-9]){3,}""") to { _ -> "[NUMBER]" }
    )
    // Regexes cannot recognise arbitrary names: catch salutations and the contact names the phone knows.
    private val salutation = Regex("""[\x{4e00}-\x{9fff}]{2,4}(?=(您好|你好|先生|小姐|女士|阿姨|伯伯|奶奶|爺爺|阿公|阿嬤))""")

    // Negation covers only the action right after it and never crosses 請 or punctuation.
    private const val ACTIONS = "(?:付款|轉帳|匯款|支付|繳費|告訴|提供|分享|輸入|給)"
    private val negatedAction = Regex(
        "(?:不需要|不需|不用|無需|毋需|不必|請勿|不要|切勿|勿|別)(?:再|去|隨便|任意|向?(?:任何人|他人|別人))?" +
            ACTIONS + "(?:(?:或|和|、|及)" + ACTIONS + ")*"
    )
    private val secretWords = Regex("(?i)(驗證碼|認證碼|OTP|密碼|控制碼|提款卡|信用卡號|卡號|身分證)")
    private val requestVerbs = Regex("(提供|告訴|寄給|給我|傳給|回傳|輸入|回覆|念給|唸給)")
    // (?d): only \n ends a line for `.`, matching Python, so "\r" cannot hide a phishing phrase.
    private val credentialPhishing = Regex("(?d)(帳戶|帳號|會員).{0,12}(驗證|登入|解鎖).{0,20}(輸入|填寫).{0,6}(資料|個資)")
    private val asksTransfer = Regex("(轉帳|匯款|先匯|匯到|付款|支付|繳費|繳納|ATM|轉入|儲值)")
    private val urgency = Regex("(立即|立刻|馬上|盡快|趕快|緊急|否則|限時|今天內|停權|凍結|處分|逾期|最後機會)")
    private val offersReward = Regex("(中獎|獎金|保證獲利|高報酬|穩賺|免費領|紅利|抽中)")
    private val remoteControl = Regex("(遠端控制|遠端軟體|遠端協助|螢幕分享|控制碼)")
    private val impersonation = listOf(
        "family" to Regex("(我是(你的?)?(家人|兒子|女兒|孫子|孫女|媳婦|女婿)|換(了)?(新)?(手機)?號碼)"),
        "official" to Regex("(我是(警察|檢察官|法官|法院|機構人員|公務員|政府)|警察局|檢察署|地檢署)"),
        "support" to Regex("(我是(客服|銀行|專員|電信)|客服人員)")
    )
    private val vague = Regex("(細節還沒|還沒有說明|稍後說明|之後再說|晚點再說)")
    private val filler = Regex("""(?U)^(這是虛構訊息。|虛構測試內容：|虛構\w*通知：)""")
    private val random = SecureRandom()
    // Python's \s also covers U+001C..U+001F, Java's does not; both implementations map them to spaces.
    private val separatorControls = Regex("[\u001c-\u001f]")

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
        return separatorControls.replace(Normalizer.normalize(clean, Normalizer.Form.NFKC), " ")
    }

    private fun codePoints(text: String) = text.codePointCount(0, text.length)

    private fun takeCodePoints(text: String, limit: Int): String =
        if (codePoints(text) <= limit) text else text.substring(0, text.offsetByCodePoints(0, limit))

    fun redact(text: String, knownNames: Collection<String> = emptyList()): String {
        var result = normalize(text)
        for (name in knownNames.filter { it.isNotBlank() }.sortedByDescending { it.length }) {
            result = result.replace(Normalizer.normalize(name, Normalizer.Form.NFKC), "[NAME]")
        }
        for ((pattern, replacement) in redactions) result = pattern.replace(result, replacement)
        return salutation.replace(result, "[NAME]")
    }

    fun extractFacts(original: String, sourceKind: String): Facts {
        val redacted = redact(original)
        val withoutNegation = negatedAction.replace(redacted, "")
        val core = filler.replace(redacted, "").trim()
        return Facts(
            asksTransfer = asksTransfer.containsMatchIn(withoutNegation),
            requestsSecret = (secretWords.containsMatchIn(withoutNegation) && requestVerbs.containsMatchIn(withoutNegation)) ||
                credentialPhishing.containsMatchIn(redacted),
            urgency = urgency.containsMatchIn(withoutNegation),
            hasLink = "[LINK]" in redacted,
            offersReward = offersReward.containsMatchIn(withoutNegation),
            requestsRemoteControl = remoteControl.containsMatchIn(withoutNegation),
            contextSufficient = !vague.containsMatchIn(core) && codePoints(core) >= 8,
            impersonation = impersonation.firstOrNull { it.second.containsMatchIn(redacted) }?.first ?: "none",
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
        if (f.offersReward) codes += "OFFERS_REWARD"
        if (f.requestsRemoteControl) codes += "REMOTE_CONTROL"
        val impersonating = f.impersonation != "none"
        if (impersonating) codes += "IMPERSONATION_" + f.impersonation.uppercase()
        if (f.asksTransfer || f.requestsSecret || f.requestsRemoteControl ||
            (f.hasLink && (f.urgency || f.offersReward || impersonating)) ||
            (impersonating && f.urgency) || (f.offersReward && f.urgency)) return Assessment(Level.HIGH, codes)
        if (!f.contextSufficient) return Assessment(Level.INSUFFICIENT, codes + "CONTEXT_INSUFFICIENT")
        return Assessment(Level.VERIFY, codes.ifEmpty { listOf("NO_STRONG_SIGNAL") })
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
        val json = buildString {
            append("{\"schema\":").append(quote(PACKET_SCHEMA))
            append(",\"task_id_alias\":").append(quote(alias))
            append(",\"intent\":\"CHECK_MESSAGE\",\"locale\":\"zh-TW\",\"cloud_consent\":true")
            append(",\"facts\":{")
            append("\"asks_transfer\":").append(facts.asksTransfer)
            append(",\"requests_secret\":").append(facts.requestsSecret)
            append(",\"urgency\":").append(facts.urgency)
            append(",\"has_link\":").append(facts.hasLink)
            append(",\"offers_reward\":").append(facts.offersReward)
            append(",\"requests_remote_control\":").append(facts.requestsRemoteControl)
            append(",\"context_sufficient\":").append(facts.contextSufficient)
            append(",\"impersonation\":").append(quote(facts.impersonation))
            append(",\"source_kind\":").append(quote(facts.sourceKind))
            append("},\"redacted_excerpt\":").append(excerpt?.let(::quote) ?: "null")
            append(",\"allowed_sops\":[").append(ALLOWED_SOPS.joinToString(",", transform = ::quote)).append("]")
            append(",\"client_policy_version\":").append(quote(CLIENT_POLICY_VERSION)).append("}")
        }
        return Packet(alias, facts, excerpt, json)
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
        Level.VERIFY to "需要核實：沒有找到明顯的詐騙特徵，但這不代表一定安全。" +
            "如果跟錢或個人資料有關，請先用原本的管道確認。",
        Level.INSUFFICIENT to "資訊不足：這則訊息的內容不夠判斷。請先不要回應，可以問家人，或把完整訊息給我再看一次。"
    )
    val reasonText = mapOf(
        "ASKS_TRANSFER" to "要求轉帳或付款", "REQUESTS_SECRET" to "索取驗證碼、密碼或個人資料",
        "URGENCY" to "催促你馬上處理", "HAS_LINK" to "含有連結", "IMPERSONATION_FAMILY" to "自稱家人或換了號碼",
        "IMPERSONATION_OFFICIAL" to "自稱警察或政府機關", "IMPERSONATION_SUPPORT" to "自稱客服或銀行",
        "OFFERS_REWARD" to "提到中獎或保證獲利", "REMOTE_CONTROL" to "要求遠端控制手機",
        "CONTEXT_INSUFFICIENT" to "內容太少，無法判斷", "NO_STRONG_SIGNAL" to "沒有發現明顯詐騙特徵"
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
        append("\n內容是否足夠判斷：").append(if (f.contextSufficient) "是" else "否")
        append("\n遮蔽後摘錄：").append(packet.excerpt ?: "（不傳送）")
    }
}
