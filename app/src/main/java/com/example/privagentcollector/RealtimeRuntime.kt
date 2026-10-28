package com.example.privagentcollector

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RealtimeRuntime {
    val outbox = RealtimeOutbox()

    @Volatile var active = false
        private set
    @Volatile var stopRequested = false
        private set
    @Volatile var phase = "stopped"
        private set
    @Volatile var collectionSessionId: String? = null
        private set
    @Volatile var lastTransferId: String? = null
        private set
    @Volatile var lastSentAt: Long? = null
        private set
    @Volatile var lastError: String? = null
        private set

    private var immediateEnabled = false
    private var immediateListener: (() -> Unit)? = null

    @Synchronized fun markStarting() {
        active = true
        stopRequested = false
        phase = "starting"
        lastError = null
    }

    @Synchronized fun start(
        sessionId: String,
        sendImmediate: Boolean,
        onImmediateEvent: () -> Unit
    ) {
        outbox.activate(sessionId)
        collectionSessionId = sessionId
        immediateEnabled = sendImmediate
        immediateListener = onImmediateEvent
        active = true
        stopRequested = false
        phase = "baseline"
        lastTransferId = null
        lastSentAt = null
        lastError = null
    }

    fun publish(sessionId: String, source: String, line: ByteArray) {
        if (!active || stopRequested) return
        val result = outbox.offer(sessionId, source, line)
        if (result == OutboxOfferResult.ACCEPTED && immediateEnabled &&
            (source.startsWith("notification_") || source.startsWith("accessibility_"))) {
            synchronized(this) { immediateListener }?.invoke()
        }
        if (result == OutboxOfferResult.DROPPED_CAPACITY ||
            result == OutboxOfferResult.DROPPED_OVERSIZE) {
            lastError = "即時佇列已達上限，部分事件只保留在本機紀錄"
        }
    }

    @Synchronized fun requestStop() {
        if (!active && phase == "stopped") return
        stopRequested = true
        phase = "stopping"
        immediateListener = null
    }

    @Synchronized fun finishStopped() {
        outbox.deactivate()
        active = false
        stopRequested = false
        phase = "stopped"
        collectionSessionId = null
        immediateEnabled = false
        immediateListener = null
    }

    @Synchronized fun markPhase(value: String) {
        if (active && !stopRequested) phase = value
    }

    @Synchronized fun markRetryableError(message: String) {
        lastError = message
        if (active && !stopRequested) phase = "retry_wait"
    }

    @Synchronized fun markPermanentError(message: String) {
        lastError = message
        outbox.deactivate()
        active = false
        stopRequested = true
        phase = "error"
        collectionSessionId = null
        immediateEnabled = false
        immediateListener = null
    }

    @Synchronized fun markTransferSuccess(transferId: String, remaining: Int) {
        lastTransferId = transferId
        lastSentAt = System.currentTimeMillis()
        lastError = null
        if (active && !stopRequested) phase = if (remaining == 0) "waiting" else "sending"
    }

    fun validFor(sessionId: String): Boolean =
        active && !stopRequested && collectionSessionId == sessionId

    fun statusText(): String {
        val phaseText = when (phase) {
            "starting" -> "正在啟動"
            "baseline" -> "蒐集基礎資料"
            "sending" -> "正在傳送"
            "waiting" -> "等待下一次更新"
            "retry_wait" -> "傳送失敗，等待重試"
            "stopping" -> "正在停止"
            "error" -> "已因不可重試錯誤停止"
            else -> "已停止"
        }
        val sent = lastSentAt?.let {
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it))
        } ?: "尚未送出"
        return "$phaseText｜待送 ${outbox.queuedCount()} 筆｜已略過 ${outbox.dropped} 筆" +
            "\n上次成功：$sent" +
            (lastTransferId?.let { "｜${it.take(12)}…" } ?: "") +
            (lastError?.let { "\n$it" } ?: "")
    }
}
