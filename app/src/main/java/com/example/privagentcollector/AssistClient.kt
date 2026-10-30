package com.example.privagentcollector

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class AssistOutcome(
    val level: MessageGuard.Level,
    val reasonCodes: List<String>,
    /** Where the final answer came from, in plain language for the result screen. */
    val source: String
)

object AssistConfig {
    private const val PREFS = "assist_options"
    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    /** True when this APK was built with a public assist endpoint (`make public` + `make apk`). */
    val builtIn: Boolean get() = BuildConfig.ASSIST_BASE_URL.isNotBlank()
    /** Assist calls go to the built-in endpoint when there is one; otherwise to the Receiver URL on the main page. */
    fun baseUrl(context: Context): String = if (builtIn) BuildConfig.ASSIST_BASE_URL else TransferConfig.baseUrl(context)
    /** A token typed on the phone wins; otherwise the one baked into the APK. */
    fun token(context: Context): String =
        prefs(context).getString("token", "").orEmpty().ifBlank { BuildConfig.ASSIST_TOKEN }
    fun cloudConsent(context: Context) = prefs(context).getBoolean("cloud_consent", false)
    fun allowExcerpt(context: Context) = prefs(context).getBoolean("allow_excerpt", false)
    fun autoCheck(context: Context) = prefs(context).getBoolean("auto_check", false)
    fun setAutoCheck(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("auto_check", enabled).apply() }
    fun save(context: Context, token: String, cloudConsent: Boolean, allowExcerpt: Boolean) {
        prefs(context).edit().putString("token", token.trim()).putBoolean("cloud_consent", cloudConsent)
            .putBoolean("allow_excerpt", allowExcerpt).apply()
    }
}

object AssistClient {
    private const val PATH = "/api/v1/assist/check-message"
    private const val PLAN_PATH = "/api/v1/assist/plan"
    private const val CONNECT_TIMEOUT_MS = 5_000
    // The Gemini proxy takes 5–15 s; the Receiver itself gives up on it at 45 s.
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_RESPONSE_BYTES = 8 * 1024
    // Read timeouts are per read; this bounds the whole request even if the peer trickles bytes.
    private const val TOTAL_DEADLINE_MS = 75_000L
    private val fallbackText = mapOf(
        "PLANNER_DISABLED" to "電腦端未啟用 AI", "PLANNER_TIMEOUT" to "AI 回應逾時",
        "PLANNER_UNREACHABLE" to "電腦連不到 AI", "PLANNER_INVALID_RESPONSE" to "AI 回應不符規定",
        "PLANNER_BAD_ENVELOPE" to "AI 回應格式錯誤", "PLANNER_TRANSPORT_ERROR" to "AI 連線中斷"
    )

    /** Runs on a worker thread. Never throws: every failure ends in the on-device rule result. */
    fun check(baseUrl: String, token: String, packet: MessageGuard.Packet, cloudConsent: Boolean): AssistOutcome {
        val local = MessageGuard.assess(packet.facts)
        if (!cloudConsent) return AssistOutcome(local.level, local.reasonCodes, "手機本機規則（未同意使用雲端 AI，沒有送出任何資料）")
        return try {
            val server = post(baseUrl, token, packet)
            // Defensive floor on the phone too: the cloud answer may only raise caution.
            val level = maxOf(server.level, local.level)
            val codes = (if (level == server.level) server.reasonCodes else local.reasonCodes + server.reasonCodes)
                .distinct().filter { level == MessageGuard.Level.VERIFY || it != "NO_STRONG_SIGNAL" }
            AssistOutcome(level, codes, server.source)
        } catch (e: AssistRejected) {
            AssistOutcome(local.level, local.reasonCodes, "手機本機規則（${e.message}）")
        } catch (_: IOException) {
            AssistOutcome(local.level, local.reasonCodes, "手機本機規則（連不到電腦接收端）")
        } catch (_: Exception) {
            AssistOutcome(local.level, local.reasonCodes, "手機本機規則（傳送失敗）")
        }
    }

    data class PlanOutcome(val sopId: String, val reasonCodes: List<String>, val source: String)

    /**
     * SHARE_PHOTO / RESCUE_PHONE: [local] is the phone's own plan and is returned on any failure.
     * A share may only become more cautious; rescue only asks the cloud when the phone found no cause.
     */
    fun plan(baseUrl: String, token: String, packet: CoreTasks.PlanPacket, local: Pair<String, List<String>>): PlanOutcome {
        return try {
            val json = parseObject(post(baseUrl, PLAN_PATH, token, packet.json))
            val sop = json.optString("sop_id")
            if (json.optString("task_id_alias") != packet.alias || sop !in packet.allowedSops)
                throw AssistRejected("電腦接收端回應不一致")
            val codesJson = json.optJSONArray("reason_codes") ?: throw AssistRejected("電腦接收端回應格式錯誤")
            val codes = (0 until codesJson.length()).map { codesJson.optString(it) }.filter { it in CoreTasks.reasonText }
            val caution = listOf("SHARE_PHOTO_GUIDED", "ASK_USER", "STOP")
            val finalSop = if (packet.intent == "SHARE_PHOTO" && caution.indexOf(sop) < caution.indexOf(local.first)) local.first
                else if (sop == "ASK_USER" && packet.intent == "RESCUE_PHONE") "RESCUE_ASK_DESCRIBE" else sop
            val planner = json.optJSONObject("planner")
            val source = if (planner?.optString("kind") == "GEMINI_PROXY") "雲端 AI（Gemini），只收到代號與狀態分級"
                else "電腦端規則（${fallbackText[planner?.optString("fallback_reason")] ?: "AI 暫時無法使用"}）"
            PlanOutcome(finalSop, (local.second + codes).distinct(), source)
        } catch (e: AssistRejected) {
            PlanOutcome(local.first, local.second, "手機本機規則（${e.message}）")
        } catch (_: IOException) {
            PlanOutcome(local.first, local.second, "手機本機規則（連不到電腦接收端）")
        } catch (_: Exception) {
            PlanOutcome(local.first, local.second, "手機本機規則（傳送失敗）")
        }
    }

    private class AssistRejected(message: String) : Exception(message)

    private fun post(baseUrl: String, token: String, packet: MessageGuard.Packet): AssistOutcome =
        parse(post(baseUrl, PATH, token, packet.json), packet.alias)

    /** Posts one packet and returns the 200 body; anything else becomes [AssistRejected] or IOException. */
    private fun post(baseUrl: String, path: String, token: String, json: String): String {
        val body = json.toByteArray(Charsets.UTF_8)
        val connection = (URL(TransferProtocol.normalizeBaseUrl(baseUrl) + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            doOutput = true
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (token.isNotEmpty()) setRequestProperty("X-Api-Token", token)
            setFixedLengthStreamingMode(body.size)
        }
        val watchdog = Thread {
            try { Thread.sleep(TOTAL_DEADLINE_MS); connection.disconnect() } catch (_: InterruptedException) {}
        }.apply { isDaemon = true; start() }
        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val bytes = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readNBytesCompat(MAX_RESPONSE_BYTES + 1) } ?: ByteArray(0)
            if (bytes.size > MAX_RESPONSE_BYTES) throw AssistRejected("電腦接收端回應過大")
            val text = bytes.toString(Charsets.UTF_8)
            if (code != 200) throw AssistRejected(rejection(code, text))
            return text
        } finally {
            watchdog.interrupt()
            connection.disconnect()
        }
    }

    private fun rejection(code: Int, body: String): String {
        val errorCode = try { JSONObject(body).getJSONObject("error").getString("code") } catch (_: Exception) { "" }
        return when {
            code == 401 -> "電腦接收端的存取碼不正確"
            errorCode == "FORBIDDEN_VALUE_IN_PACKET" -> "電腦端偵測到疑似個資而拒收，沒有交給 AI"
            else -> "電腦接收端拒絕（HTTP $code）"
        }
    }

    private fun parseObject(text: String): JSONObject = try {
        org.json.JSONTokener(text).let { tokener ->
            (tokener.nextValue() as JSONObject).also { if (tokener.nextClean() != 0.toChar()) error("trailing data") }
        }
    } catch (_: Exception) { throw AssistRejected("電腦接收端回應格式錯誤") }

    private fun parse(text: String, alias: String): AssistOutcome {
        val json = parseObject(text)
        val level = MessageGuard.Level.fromWire(json.optString("risk_level"))
        if (level == null || json.optString("task_id_alias") != alias) throw AssistRejected("電腦接收端回應不一致")
        val codesJson = json.optJSONArray("reason_codes") ?: throw AssistRejected("電腦接收端回應格式錯誤")
        // Only codes the phone has fixed wording for are shown; the model never writes UI text.
        val codes = (0 until codesJson.length()).map { codesJson.optString(it) }.filter { it in MessageGuard.reasonText }
        val planner = json.optJSONObject("planner")
        val source = if (planner?.optString("kind") == "GEMINI_PROXY") "雲端 AI（Gemini），只收到遮蔽後的特徵"
            else "電腦端規則（${fallbackText[planner?.optString("fallback_reason")] ?: "AI 暫時無法使用"}）"
        return AssistOutcome(level, codes, source)
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (output.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (read <= 0) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
