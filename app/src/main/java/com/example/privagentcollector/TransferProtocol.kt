package com.example.privagentcollector

import java.net.URI
import java.util.UUID

data class TransferAcknowledgement(
    val accepted: Int?,
    val duplicates: Int?,
    val duplicate: Boolean?,
    val receivedRecords: Int?,
    val sha256: String?,
    val serverTransferId: String?,
    val transmissionMode: String?,
    val collectionSessionId: String?
)

/** Pure protocol helpers kept independent from Android so the wire contract is JVM-testable. */
object TransferProtocol {
    const val HEALTH_PATH = "/health"
    const val TRANSMISSIONS_PATH = "/api/v1/transmissions"
    const val CONTENT_TYPE = "application/x-ndjson; charset=utf-8"
    const val TRANSFER_ID_HEADER = "X-Transfer-Id"
    const val RECORD_COUNT_HEADER = "X-Record-Count"
    const val PAYLOAD_SHA256_HEADER = "X-Payload-Sha256"
    const val CLIENT_VERSION_HEADER = "X-Client-Version"
    const val TRANSMISSION_MODE_HEADER = "X-Transmission-Mode"
    const val CLIENT_SENT_AT_HEADER = "X-Client-Sent-At"
    const val COLLECTION_SESSION_HEADER = "X-Collection-Session-Id"
    val LIVE_MODES = setOf("baseline", "periodic", "immediate")

    fun normalizeBaseUrl(input: String): String {
        val candidate = input.trim()
        require(candidate.isNotEmpty()) { "接收端網址不可空白" }
        val uri = try {
            URI(candidate)
        } catch (_: Exception) {
            throw IllegalArgumentException("接收端網址格式錯誤")
        }
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            "接收端網址只支援 http 或 https"
        }
        require(!uri.host.isNullOrBlank()) { "接收端網址缺少主機名稱" }
        require(uri.userInfo == null) { "接收端網址不可包含帳號或密碼" }
        require(uri.query == null && uri.fragment == null) { "接收端網址不可包含查詢或片段" }
        require(uri.port == -1 || uri.port in 1..65535) { "接收端連接埠超出範圍" }

        val scheme = uri.scheme.lowercase()
        val rawHost = requireNotNull(uri.host)
        val host = when {
            rawHost.startsWith('[') && rawHost.endsWith(']') -> rawHost.lowercase()
            rawHost.contains(':') -> "[$rawHost]"
            else -> rawHost.lowercase()
        }
        val authority = if (uri.port == -1) host else "$host:${uri.port}"
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return "$scheme://$authority$path"
    }

    fun healthUrl(baseUrl: String) = normalizeBaseUrl(baseUrl) + HEALTH_PATH

    fun transmissionsUrl(baseUrl: String) = normalizeBaseUrl(baseUrl) + TRANSMISSIONS_PATH

    fun newTransferId(): String = UUID.randomUUID().toString()

    fun isValidTransferId(value: String): Boolean = runCatching {
        UUID.fromString(value).toString() == value.lowercase()
    }.getOrDefault(false)

    fun transferIdForPayload(
        payloadSha256: String,
        pendingPayloadSha256: String?,
        pendingTransferId: String?,
        createId: () -> String = ::newTransferId
    ): String {
        if (payloadSha256 == pendingPayloadSha256 && pendingTransferId != null &&
            isValidTransferId(pendingTransferId)) return pendingTransferId
        return createId()
    }

    fun parseAcknowledgement(body: String): TransferAcknowledgement = TransferAcknowledgement(
        accepted = integerField(body, "accepted"),
        duplicates = integerField(body, "duplicates"),
        duplicate = booleanField(body, "duplicate"),
        receivedRecords = integerField(body, "receivedRecords"),
        sha256 = stringField(body, "sha256"),
        serverTransferId = stringField(body, "transferId") ?: stringField(body, "batchId"),
        transmissionMode = stringField(body, "transmissionMode"),
        collectionSessionId = stringField(body, "collectionSessionId")
    )

    private fun integerField(body: String, name: String): Int? {
        val match = Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*(\\d+)").find(body) ?: return null
        return match.groupValues[1].toLongOrNull()?.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    private fun stringField(body: String, name: String): String? {
        val match = Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*\\\"([^\\\"]{1,128})\\\"").find(body)
            ?: return null
        return match.groupValues[1]
    }

    private fun booleanField(body: String, name: String): Boolean? {
        val match = Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*(true|false)").find(body)
            ?: return null
        return match.groupValues[1] == "true"
    }
}
