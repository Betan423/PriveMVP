package com.example.privagentcollector

import android.content.Context

object TransferConfig {
    private const val PREFS = "transfer_options"
    private const val BASE_URL = "base_url"
    private const val PENDING_PAYLOAD_SHA256 = "pending_payload_sha256"
    private const val PENDING_TRANSFER_ID = "pending_transfer_id"
    const val DEFAULT_BASE_URL = "http://10.0.2.2:8080"

    fun baseUrl(context: Context): String = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(BASE_URL, DEFAULT_BASE_URL)
        .orEmpty()

    fun setBaseUrl(context: Context, value: String): String {
        val normalized = TransferProtocol.normalizeBaseUrl(value)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(BASE_URL, normalized).apply()
        return normalized
    }

    @Synchronized fun transferIdForPayload(context: Context, payloadSha256: String): String {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pendingSha = preferences.getString(PENDING_PAYLOAD_SHA256, null)
        val pendingId = preferences.getString(PENDING_TRANSFER_ID, null)
        val transferId = TransferProtocol.transferIdForPayload(payloadSha256, pendingSha, pendingId)
        if (payloadSha256 != pendingSha || transferId != pendingId) {
            check(preferences.edit()
                .putString(PENDING_PAYLOAD_SHA256, payloadSha256)
                .putString(PENDING_TRANSFER_ID, transferId)
                .commit()) { "無法保存傳送重試狀態" }
        }
        return transferId
    }

    @Synchronized fun clearPending(context: Context, payloadSha256: String, transferId: String): Boolean {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (preferences.getString(PENDING_PAYLOAD_SHA256, null) != payloadSha256 ||
            preferences.getString(PENDING_TRANSFER_ID, null) != transferId) return false
        return preferences.edit()
            .remove(PENDING_PAYLOAD_SHA256)
            .remove(PENDING_TRANSFER_ID)
            .commit()
    }
}
