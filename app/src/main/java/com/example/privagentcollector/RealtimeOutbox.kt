package com.example.privagentcollector

import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.UUID

enum class OutboxOfferResult {
    ACCEPTED,
    IGNORED_INACTIVE,
    IGNORED_SESSION,
    DROPPED_OVERSIZE,
    DROPPED_CAPACITY
}

data class RealtimeQueuedRecord(
    val id: Long,
    val sessionId: String,
    val source: String,
    val bytes: ByteArray
)

class RealtimeBatch internal constructor(
    val transferId: String,
    records: List<RealtimeQueuedRecord>
) {
    internal val recordIds = records.map { it.id }
    val recordCount: Int = records.size
    private val frozenPayload: ByteArray = ByteArrayOutputStream(records.sumOf { it.bytes.size }).use { output ->
        records.forEach { output.write(it.bytes) }
        output.toByteArray()
    }
    val byteCount: Int get() = frozenPayload.size

    /** Callers receive a copy, so retries cannot mutate the frozen wire bytes. */
    fun payload(): ByteArray = frozenPayload.copyOf()
}

/**
 * Process-local live outbox. One batch remains frozen until acknowledged so new
 * events never change the bytes or Transfer ID of a retry.
 */
class RealtimeOutbox(
    private val maxQueueRecords: Int = 5_000,
    private val maxQueueBytes: Int = 20 * 1024 * 1024,
    private val maxBatchRecords: Int = 500,
    private val maxBatchBytes: Int = 5 * 1024 * 1024,
    private val transferIdFactory: () -> String = { UUID.randomUUID().toString() }
) {
    private val queue = ArrayDeque<RealtimeQueuedRecord>()
    private var sessionId: String? = null
    private var nextId = 1L
    private var queuedBytes = 0
    private var inFlight: RealtimeBatch? = null
    var dropped: Int = 0
        private set

    init {
        require(maxQueueRecords > 0 && maxQueueBytes > 0)
        require(maxBatchRecords > 0 && maxBatchBytes > 0)
        require(maxBatchRecords <= maxQueueRecords && maxBatchBytes <= maxQueueBytes)
    }

    @Synchronized fun activate(collectionSessionId: String) {
        require(collectionSessionId.isNotBlank())
        clearInternal()
        sessionId = collectionSessionId
    }

    @Synchronized fun deactivate() {
        clearInternal()
        sessionId = null
    }

    @Synchronized fun offer(
        collectionSessionId: String,
        source: String,
        recordBytes: ByteArray
    ): OutboxOfferResult {
        val activeSession = sessionId ?: return OutboxOfferResult.IGNORED_INACTIVE
        if (collectionSessionId != activeSession) return OutboxOfferResult.IGNORED_SESSION
        if (recordBytes.isEmpty() || recordBytes.size > maxBatchBytes) {
            dropped++
            return OutboxOfferResult.DROPPED_OVERSIZE
        }
        if (queue.size >= maxQueueRecords || queuedBytes + recordBytes.size > maxQueueBytes) {
            dropped++
            return OutboxOfferResult.DROPPED_CAPACITY
        }
        queue.addLast(
            RealtimeQueuedRecord(nextId++, collectionSessionId, source, recordBytes.copyOf())
        )
        queuedBytes += recordBytes.size
        return OutboxOfferResult.ACCEPTED
    }

    @Synchronized fun beginBatch(): RealtimeBatch? {
        inFlight?.let { return it }
        if (queue.isEmpty()) return null
        val records = mutableListOf<RealtimeQueuedRecord>()
        var bytes = 0
        for (record in queue) {
            if (records.size >= maxBatchRecords || bytes + record.bytes.size > maxBatchBytes) break
            records += record
            bytes += record.bytes.size
        }
        check(records.isNotEmpty())
        return RealtimeBatch(transferIdFactory(), records).also { inFlight = it }
    }

    @Synchronized fun acknowledge(transferId: String): Boolean {
        val batch = inFlight ?: return false
        if (batch.transferId != transferId) return false
        for (expectedId in batch.recordIds) {
            val current = queue.peekFirst() ?: return false
            if (current.id != expectedId) return false
            queue.removeFirst()
            queuedBytes -= current.bytes.size
        }
        inFlight = null
        return true
    }

    @Synchronized fun currentBatch(): RealtimeBatch? = inFlight
    @Synchronized fun queuedCount(): Int = queue.size
    @Synchronized fun queuedByteCount(): Int = queuedBytes
    @Synchronized fun activeSessionId(): String? = sessionId

    @Synchronized fun clear() {
        clearInternal()
    }

    private fun clearInternal() {
        queue.clear()
        queuedBytes = 0
        inFlight = null
        dropped = 0
    }
}
