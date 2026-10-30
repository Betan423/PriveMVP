package com.example.privagentcollector

import java.security.SecureRandom

/**
 * Kotlin port of receiver/core_tasks.py: utterance interpretation, the RESCUE_PHONE rule and the
 * SHARE_PHOTO / RESCUE_PHONE planner packets. Golden cases generated from Python keep them equal.
 * Real names, photos and system values stay in the flows; packets only carry tokens and bands.
 */
object CoreTasks {
    data class Interpretation(
        val intent: String,
        val relation: String? = null,
        val selection: String? = null,
        val symptomCategory: String? = null,
        val symptomDetail: String? = null,
        val requestedScope: String? = null
    )

    val relations = listOf(
        "孫女" to "granddaughter", "孫子" to "grandson", "女兒" to "daughter", "兒子" to "son",
        "朋友" to "friend", "鄰居" to "neighbor", "照顧者" to "caregiver", "醫師" to "doctor", "醫生" to "doctor"
    )
    val relationLabel = linkedMapOf(
        "daughter" to "女兒", "son" to "兒子", "granddaughter" to "孫女", "grandson" to "孫子",
        "friend" to "朋友", "neighbor" to "鄰居", "caregiver" to "照顧者", "doctor" to "醫師"
    )
    private val cancel = Regex("取消|不用了|算了")
    // Other assistant tasks are not core scenarios yet and must not fall into one by a shared word.
    private val unsupported = Regex("打開|開給我|啟動|我要看|傳訊息|跟\\S+說|念|讀出|讀這|打給|打電話|撥打|導航|帶我去|提醒我")
    private val share = Regex("照片|相片|相簿|這張|這一張")
    private val message = Regex("訊息|這則|這一則|連結|網址|驗證碼|可信|相信|可靠|窗口|通知有問題")
    private val rescue = Regex("聲|鈴|勿擾|飛航|網路|Wi-?Fi|wifi|無線|連不上|連線|離線|上網|壞|怪怪|對勁|怎麼了|設定|手機狀態|檢查手機|不響")
    private val inspectOnly = Regex("不要改|不要直接改|不要動|變更之前先問|只檢查")

    fun interpret(text: String): Interpretation {
        if (cancel.containsMatchIn(text)) return Interpretation("CANCEL")
        if (unsupported.containsMatchIn(text)) return Interpretation("UNSUPPORTED")
        if (share.containsMatchIn(text)) {
            val relation = relations.firstOrNull { text.contains(it.first) }?.second
            val selection = when {
                Regex("剛拍|最新|最近拍|最後拍").containsMatchIn(text) -> "latest"
                Regex("家庭相簿|家人相簿").containsMatchIn(text) -> "family_album"
                text.contains("出遊") -> "outing_album"
                else -> "selected"
            }
            return Interpretation("SHARE_PHOTO", relation = relation, selection = selection)
        }
        if (message.containsMatchIn(text)) return Interpretation("CHECK_MESSAGE")
        if (rescue.containsMatchIn(text)) {
            val scope = if (inspectOnly.containsMatchIn(text)) "inspect_only" else "guided_help"
            val (category, detail) = when {
                scope == "inspect_only" -> "general" to "general"
                text.contains("飛航") -> "connectivity" to "airplane"
                Regex("(?i)wi-?fi|無線").containsMatchIn(text) -> "connectivity" to "wifi"
                Regex("網路|連不上|連線|離線|上網").containsMatchIn(text) -> "connectivity" to "offline"
                text.contains("勿擾") -> "sound" to "dnd"
                text.contains("小") && Regex("聲|鈴").containsMatchIn(text) -> "sound" to "low_volume"
                Regex("沒聲|沒有聲|不響|聽不到").containsMatchIn(text) -> "sound" to "no_sound"
                else -> "general" to "general"
            }
            return Interpretation("RESCUE_PHONE", symptomCategory = category, symptomDetail = detail, requestedScope = scope)
        }
        return Interpretation("UNKNOWN")
    }

    // ------------------------------------------------------------ RESCUE_PHONE

    /** Coarse device state; every value is a band or enum, never a raw reading. */
    data class RescueState(
        val airplaneMode: String, val network: String, val internetValidated: String, val transport: String,
        val dndMode: String, val ringerMode: String, val ringVolumeBand: String
    ) {
        fun value(key: String) = when (key) {
            "airplane_mode" -> airplaneMode; "network" -> network; "internet_validated" -> internetValidated
            "transport" -> transport; "dnd_mode" -> dndMode; "ringer_mode" -> ringerMode
            "ring_volume_band" -> ringVolumeBand; else -> error(key)
        }
    }

    val factsByCategory = mapOf(
        "connectivity" to listOf("airplane_mode", "network", "internet_validated", "transport"),
        "sound" to listOf("dnd_mode", "ringer_mode", "ring_volume_band"),
        "general" to listOf("airplane_mode", "network", "internet_validated", "transport", "dnd_mode", "ringer_mode",
            "ring_volume_band")
    )
    val rescueCloudSops = listOf("RESCUE_INTERNET_SETTINGS", "RESCUE_SOUND_SETTINGS", "RESCUE_ASK_DESCRIBE", "ASK_USER")

    fun volumeBand(current: Int?, maximum: Int?): String {
        if (current == null || maximum == null || maximum <= 0 || current !in 0..maximum) return "unknown"
        if (current == 0) return "zero"
        return if (current * 10 <= maximum * 3) "low" else "normal"
    }

    private fun findings(s: RescueState, category: String, detail: String): List<Pair<String, String>> {
        val connectivity = mutableListOf<Pair<String, String>>()
        if (s.airplaneMode == "on") connectivity += "AIRPLANE_ON" to "RESCUE_AIRPLANE_SETTINGS"
        if (detail == "wifi" && s.transport != "WIFI" && s.network != "unknown") connectivity += "WIFI_NOT_CONNECTED" to "RESCUE_WIFI_SETTINGS"
        if (s.network == "offline") connectivity += "NETWORK_OFFLINE" to "RESCUE_INTERNET_SETTINGS"
        else if (s.network == "connected" && s.internetValidated == "no") connectivity += "NO_INTERNET" to "RESCUE_INTERNET_SETTINGS"
        val volume = when (s.ringVolumeBand) {
            "zero" -> listOf("VOLUME_ZERO" to "RESCUE_RAISE_VOLUME")
            "low" -> listOf("VOLUME_LOW" to "RESCUE_RAISE_VOLUME")
            else -> emptyList()
        }
        val sound = mutableListOf<Pair<String, String>>()
        if (s.dndMode == "on") sound += "DND_ON" to "RESCUE_DND_SETTINGS"
        if (s.ringerMode == "silent") sound += "RINGER_SILENT" to "RESCUE_SOUND_SETTINGS"
        else if (s.ringerMode == "vibrate") sound += "RINGER_VIBRATE" to "RESCUE_SOUND_SETTINGS"
        return when (category) {
            "connectivity" -> connectivity
            "sound" -> if (detail == "low_volume") volume + sound else sound + volume
            else -> connectivity + sound + volume
        }
    }

    /** Returns (sop_id, reason_codes). Unreadable state is never guessed at. */
    fun rescueRule(s: RescueState, category: String, detail: String, scope: String): Pair<String, List<String>> {
        val found = findings(s, category, detail)
        val unknown = factsByCategory.getValue(category).any { s.value(it) == "unknown" || s.value(it) == "UNKNOWN" }
        val codes = found.map { it.first } + if (unknown) listOf("STATE_UNKNOWN") else emptyList()
        if (scope == "inspect_only") return "RESCUE_INSPECT_ONLY" to codes.ifEmpty { listOf("NO_FAULT_FOUND") }
        if (found.isNotEmpty()) return found.first().second to codes
        if (unknown) return "RESCUE_ASK_DESCRIBE" to codes
        return "RESCUE_NO_FAULT_FOUND" to listOf("NO_FAULT_FOUND")
    }

    // ------------------------------------------------------------ packets

    data class PlanPacket(val alias: String, val intent: String, val allowedSops: List<String>, val json: String)

    private val random = SecureRandom()
    private fun token(prefix: String) = prefix + String(CharArray(8) { ('A' + random.nextInt(26)) })
    private fun q(value: String) = MessageGuard.quote(value)
    private fun list(values: List<String>) = values.joinToString(",", "[", "]") { q(it) }

    data class ShareFacts(val locationRemoved: Boolean, val privateBackgroundReview: Boolean, val selection: String)

    /** Only built after the share is fully resolved and confirmed on the phone. */
    fun sharePacket(facts: ShareFacts): PlanPacket {
        val alias = token("TASK_")
        val sops = listOf("SHARE_PHOTO_GUIDED", "ASK_USER", "STOP")
        val json = "{\"schema\":\"privagent.planner_packet.v1\",\"task_id_alias\":${q(alias)},\"intent\":\"SHARE_PHOTO\"," +
            "\"locale\":\"zh-TW\",\"cloud_consent\":true,\"facts\":{\"recipient_ready\":true,\"photo_ready\":true," +
            "\"share_app_ready\":true,\"location_removed\":${facts.locationRemoved}," +
            "\"private_background_review\":${facts.privateBackgroundReview},\"selection\":${q(facts.selection)}}," +
            "\"recipient_token\":${q(token("CONTACT_"))},\"photo_token\":${q(token("PHOTO_"))}," +
            "\"confirmation\":\"approved\",\"allowed_sops\":${list(sops)},\"client_policy_version\":\"phone-photo-1\"}"
        return PlanPacket(alias, "SHARE_PHOTO", sops, json)
    }

    /** Only the state that can explain this symptom is disclosed. */
    fun rescuePacket(s: RescueState, category: String, detail: String): PlanPacket {
        val alias = token("TASK_")
        val facts = (listOf("symptom_category" to category, "symptom_detail" to detail, "requested_scope" to "guided_help") +
            factsByCategory.getValue(category).map { it to s.value(it) })
            .joinToString(",", "{", "}") { "${q(it.first)}:${q(it.second)}" }
        val json = "{\"schema\":\"privagent.planner_packet.v1\",\"task_id_alias\":${q(alias)},\"intent\":\"RESCUE_PHONE\"," +
            "\"locale\":\"zh-TW\",\"cloud_consent\":true,\"facts\":$facts,\"allowed_sops\":${list(rescueCloudSops)}," +
            "\"client_policy_version\":\"phone-rescue-1\"}"
        return PlanPacket(alias, "RESCUE_PHONE", rescueCloudSops, json)
    }

    // ------------------------------------------------------------ fixed wording

    val reasonText = mapOf(
        "AIRPLANE_ON" to "飛航模式開著，所以不能上網也不能打電話",
        "NETWORK_OFFLINE" to "手機現在沒有連上網路",
        "NO_INTERNET" to "有連上網路，但連不到網際網路",
        "WIFI_NOT_CONNECTED" to "Wi-Fi 沒有連上",
        "DND_ON" to "勿擾模式開著，來電和通知不會響",
        "RINGER_SILENT" to "手機設成靜音",
        "RINGER_VIBRATE" to "手機設成只震動",
        "VOLUME_ZERO" to "鈴聲音量是 0",
        "VOLUME_LOW" to "鈴聲音量很小",
        "STATE_UNKNOWN" to "有些狀態讀不到，沒辦法確定",
        "NO_FAULT_FOUND" to "沒有找到明顯的問題",
        "USER_DECLINED_CHANGES" to "依你的意思，先不改設定",
        "LOCATION_REMOVED" to "照片裡的拍攝地點已經移除",
        "PRIVATE_BACKGROUND_REVIEW" to "請看一下背景有沒有不想給別人看的東西",
        "CONFIRM_RECIPIENT" to "請再確認收件人",
        "NEEDS_USER_CHECK" to "AI 建議你再確認一次"
    )
    val sopAction = mapOf(
        "RESCUE_AIRPLANE_SETTINGS" to "打開飛航模式設定",
        "RESCUE_INTERNET_SETTINGS" to "打開網路設定",
        "RESCUE_WIFI_SETTINGS" to "打開 Wi-Fi 設定",
        "RESCUE_DND_SETTINGS" to "打開勿擾設定",
        "RESCUE_SOUND_SETTINGS" to "打開音效設定",
        "RESCUE_RAISE_VOLUME" to "把鈴聲調大"
    )
    val sopTitle = mapOf(
        "RESCUE_INSPECT_ONLY" to "手機狀態（只檢查，沒有改任何設定）",
        "RESCUE_NO_FAULT_FOUND" to "沒有找到明顯的問題。可以試試重新開機，或請家人幫忙看看。",
        "RESCUE_ASK_DESCRIBE" to "我還不確定是哪裡出問題。請說說看是哪個 App 或哪個功能不正常，或請家人幫忙看看。"
    )
}
