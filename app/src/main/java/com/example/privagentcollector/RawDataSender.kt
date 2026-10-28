package com.example.privagentcollector

import android.content.Context
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class HealthCheckResult(val statusCode: Int, val response: String)

data class RawTransferResult(
    val transferId: String,
    val acknowledgedTransferId: String,
    val sentRecords: Int,
    val payloadSha256: String,
    val receivedRecords: Int,
    val acknowledgedSha256: String,
    val acceptedRecords: Int?,
    val duplicateRecords: Int?,
    val duplicateTransmission: Boolean,
    val statusCode: Int,
    val response: String
)

class TransferFailedException(
    message: String,
    val statusCode: Int? = null,
    val retryable: Boolean = statusCode == null || statusCode == 408 || statusCode == 429 ||
        (statusCode?.let { it >= 500 } == true)
) : Exception(message)

object RawDataSender {
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_RESPONSE_BYTES = 8 * 1024

    fun checkHealth(baseUrl: String): HealthCheckResult {
        val connection = open(TransferProtocol.healthUrl(baseUrl)).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json, text/plain")
        }
        return try {
            val code = connection.responseCode
            val response = readResponse(connection, code)
            if (code !in 200..299) throw httpFailure("健康檢查失敗", code)
            HealthCheckResult(code, response)
        } finally {
            connection.disconnect()
        }
    }

    fun sendAll(context: Context, baseUrl: String): RawTransferResult {
        check(!CollectionControl.active()) { "請先停止蒐集再傳送" }
        check(ResearchLog.hasData(context)) { "目前沒有可傳送的資料" }

        val digestOutput = DigestSink()
        val expectedRecords = ResearchLog.export(context, digestOutput)
        val expectedSha256 = digestOutput.sha256()
        val expectedBytes = digestOutput.byteCount
        // Keep this ID until a complete matching acknowledgement arrives. A lost response can
        // therefore be retried idempotently without creating another server-side transmission.
        val transferId = TransferConfig.transferIdForPayload(context, expectedSha256)
        val connection = open(TransferProtocol.transmissionsUrl(baseUrl)).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            setFixedLengthStreamingMode(expectedBytes)
            setRequestProperty("Content-Type", TransferProtocol.CONTENT_TYPE)
            setRequestProperty("Accept", "application/json")
            setRequestProperty(TransferProtocol.TRANSFER_ID_HEADER, transferId)
            setRequestProperty(TransferProtocol.RECORD_COUNT_HEADER, expectedRecords.toString())
            setRequestProperty(TransferProtocol.PAYLOAD_SHA256_HEADER, expectedSha256)
            setRequestProperty(TransferProtocol.CLIENT_VERSION_HEADER, clientVersion(context))
            setRequestProperty(TransferProtocol.TRANSMISSION_MODE_HEADER, "manual")
            setRequestProperty(TransferProtocol.CLIENT_SENT_AT_HEADER, System.currentTimeMillis().toString())
        }

        return try {
            val sent = connection.outputStream.buffered().use { output ->
                ResearchLog.export(context, output)
            }
            val code = connection.responseCode
            val response = readResponse(connection, code)
            if (code !in 200..299) throw httpFailure("傳送失敗", code)
            val acknowledgement = TransferProtocol.parseAcknowledgement(response)
            val received = acknowledgement.receivedRecords
                ?: throw TransferFailedException("接收端回執缺少 receivedRecords")
            val acknowledgedSha = acknowledgement.sha256
                ?: throw TransferFailedException("接收端回執缺少 sha256")
            val acknowledgedTransferId = acknowledgement.serverTransferId
                ?: throw TransferFailedException("接收端回執缺少 transferId")
            if (acknowledgedTransferId != transferId) {
                throw TransferFailedException("接收端 Transfer ID 回執不一致")
            }
            if (sent != expectedRecords || received != expectedRecords) {
                throw TransferFailedException("接收端筆數回執不一致（送出 $sent、接收 $received）")
            }
            if (!acknowledgedSha.equals(expectedSha256, ignoreCase = true)) {
                throw TransferFailedException("接收端 SHA-256 回執不一致")
            }
            val result = RawTransferResult(
                transferId = transferId,
                acknowledgedTransferId = acknowledgedTransferId,
                sentRecords = sent,
                payloadSha256 = expectedSha256,
                receivedRecords = received,
                acknowledgedSha256 = acknowledgedSha,
                acceptedRecords = acknowledgement.accepted,
                duplicateRecords = acknowledgement.duplicates,
                duplicateTransmission = acknowledgement.duplicate == true,
                statusCode = code,
                response = response
            )
            if (!TransferConfig.clearPending(context, expectedSha256, transferId)) {
                throw TransferFailedException("資料已接收，但無法清除重試狀態；可安全重試")
            }
            result
        } finally {
            connection.disconnect()
        }
    }

    fun sendLiveBatch(
        context: Context,
        baseUrl: String,
        batch: RealtimeBatch,
        mode: String,
        collectionSessionId: String
    ): RawTransferResult {
        require(mode in TransferProtocol.LIVE_MODES) { "不支援的即時傳送模式" }
        require(collectionSessionId.isNotBlank()) { "即時傳送缺少 collection session" }
        require(batch.recordCount > 0) { "即時批次不可為空" }
        val payload = batch.payload()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        val connection = open(TransferProtocol.transmissionsUrl(baseUrl)).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            setFixedLengthStreamingMode(payload.size.toLong())
            setRequestProperty("Content-Type", TransferProtocol.CONTENT_TYPE)
            setRequestProperty("Accept", "application/json")
            setRequestProperty(TransferProtocol.TRANSFER_ID_HEADER, batch.transferId)
            setRequestProperty(TransferProtocol.RECORD_COUNT_HEADER, batch.recordCount.toString())
            setRequestProperty(TransferProtocol.PAYLOAD_SHA256_HEADER, sha256)
            setRequestProperty(TransferProtocol.CLIENT_VERSION_HEADER, clientVersion(context))
            setRequestProperty(TransferProtocol.TRANSMISSION_MODE_HEADER, mode)
            setRequestProperty(TransferProtocol.CLIENT_SENT_AT_HEADER, System.currentTimeMillis().toString())
            setRequestProperty(TransferProtocol.COLLECTION_SESSION_HEADER, collectionSessionId)
        }
        return try {
            connection.outputStream.buffered().use { it.write(payload) }
            val code = connection.responseCode
            val response = readResponse(connection, code)
            if (code !in 200..299) throw httpFailure("即時傳送失敗", code)
            val acknowledgement = TransferProtocol.parseAcknowledgement(response)
            val received = acknowledgement.receivedRecords
                ?: throw TransferFailedException("接收端回執缺少 receivedRecords")
            val acknowledgedSha = acknowledgement.sha256
                ?: throw TransferFailedException("接收端回執缺少 sha256")
            val acknowledgedTransferId = acknowledgement.serverTransferId
                ?: throw TransferFailedException("接收端回執缺少 transferId")
            if (acknowledgedTransferId != batch.transferId) {
                throw TransferFailedException("接收端 Transfer ID 回執不一致", retryable = false)
            }
            if (received != batch.recordCount) {
                throw TransferFailedException(
                    "接收端筆數回執不一致（送出 ${batch.recordCount}、接收 $received）",
                    retryable = false
                )
            }
            if (!acknowledgedSha.equals(sha256, ignoreCase = true)) {
                throw TransferFailedException("接收端 SHA-256 回執不一致", retryable = false)
            }
            if (acknowledgement.transmissionMode != mode) {
                throw TransferFailedException("接收端傳送模式回執不一致", retryable = false)
            }
            if (acknowledgement.collectionSessionId != collectionSessionId) {
                throw TransferFailedException("接收端 collection session 回執不一致", retryable = false)
            }
            RawTransferResult(
                transferId = batch.transferId,
                acknowledgedTransferId = acknowledgedTransferId,
                sentRecords = batch.recordCount,
                payloadSha256 = sha256,
                receivedRecords = received,
                acknowledgedSha256 = acknowledgedSha,
                acceptedRecords = acknowledgement.accepted,
                duplicateRecords = acknowledgement.duplicates,
                duplicateTransmission = acknowledgement.duplicate == true,
                statusCode = code,
                response = response
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        useCaches = false
    }

    private fun httpFailure(prefix: String, statusCode: Int) =
        TransferFailedException("$prefix（HTTP $statusCode）", statusCode)

    @Suppress("DEPRECATION")
    private fun clientVersion(context: Context): String = context.packageManager
        .getPackageInfo(context.packageName, 0).versionName ?: "unknown"

    private fun readResponse(connection: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..399) connection.inputStream else connection.errorStream
        return stream?.use(::readLimited).orEmpty()
    }

    private fun readLimited(input: InputStream): String {
        val buffer = ByteArray(1024)
        val output = java.io.ByteArrayOutputStream()
        while (output.size() < MAX_RESPONSE_BYTES) {
            val remaining = minOf(buffer.size, MAX_RESPONSE_BYTES - output.size())
            val read = input.read(buffer, 0, remaining)
            if (read <= 0) break
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private class DigestSink : OutputStream() {
        private val digest = MessageDigest.getInstance("SHA-256")
        var byteCount = 0L
            private set
        override fun write(value: Int) {
            digest.update(value.toByte())
            byteCount++
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            digest.update(bytes, offset, length)
            byteCount += length
        }
        fun sha256(): String = digest.digest().joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }
}
