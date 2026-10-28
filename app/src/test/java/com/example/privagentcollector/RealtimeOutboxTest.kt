package com.example.privagentcollector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeOutboxTest {
    @Test fun freezesFailedBatchAndKeepsNewEventsBehindIt() {
        var ids = 0
        val outbox = RealtimeOutbox(
            maxQueueRecords = 10,
            maxQueueBytes = 1_000,
            maxBatchRecords = 2,
            maxBatchBytes = 100
        ) { "batch-${++ids}" }
        outbox.activate("session-1")
        assertEquals(OutboxOfferResult.ACCEPTED, outbox.offer("session-1", "device", "one\n".toByteArray()))
        assertEquals(OutboxOfferResult.ACCEPTED, outbox.offer("session-1", "battery", "two\n".toByteArray()))
        val first = requireNotNull(outbox.beginBatch())
        assertEquals(2, first.recordCount)
        assertEquals(OutboxOfferResult.ACCEPTED, outbox.offer("session-1", "network", "three\n".toByteArray()))
        assertSame(first, outbox.beginBatch())
        assertEquals(3, outbox.queuedCount())
        assertTrue(outbox.acknowledge(first.transferId))
        assertEquals(1, outbox.queuedCount())
        val second = requireNotNull(outbox.beginBatch())
        assertEquals("batch-2", second.transferId)
        assertEquals("three\n", second.payload().toString(Charsets.UTF_8))
    }

    @Test fun enforcesSessionCapacityAndBatchLimits() {
        val outbox = RealtimeOutbox(
            maxQueueRecords = 3,
            maxQueueBytes = 30,
            maxBatchRecords = 2,
            maxBatchBytes = 20
        ) { "fixed" }
        assertEquals(OutboxOfferResult.IGNORED_INACTIVE, outbox.offer("s", "x", byteArrayOf(1)))
        outbox.activate("s")
        assertEquals(OutboxOfferResult.IGNORED_SESSION, outbox.offer("other", "x", byteArrayOf(1)))
        repeat(3) {
            assertEquals(OutboxOfferResult.ACCEPTED, outbox.offer("s", "x", ByteArray(8) { 1 }))
        }
        assertEquals(OutboxOfferResult.DROPPED_CAPACITY, outbox.offer("s", "x", byteArrayOf(1)))
        assertEquals(1, outbox.dropped)
        val batch = requireNotNull(outbox.beginBatch())
        assertEquals(2, batch.recordCount)
        assertEquals(16, batch.byteCount)
        assertFalse(outbox.acknowledge("wrong"))
        assertTrue(outbox.acknowledge("fixed"))
        assertEquals(1, outbox.queuedCount())
        outbox.deactivate()
        assertNull(outbox.beginBatch())
    }

    @Test fun rejectsSingleRecordLargerThanBatchLimit() {
        val outbox = RealtimeOutbox(
            maxQueueRecords = 2,
            maxQueueBytes = 20,
            maxBatchRecords = 2,
            maxBatchBytes = 10
        )
        outbox.activate("s")
        assertEquals(OutboxOfferResult.DROPPED_OVERSIZE, outbox.offer("s", "x", ByteArray(11)))
        assertEquals(0, outbox.queuedCount())
        assertEquals(1, outbox.dropped)
    }

    @Test fun freezesBytesAgainstCallerMutationAndAcrossRetries() {
        val outbox = RealtimeOutbox(
            maxQueueRecords = 4,
            maxQueueBytes = 100,
            maxBatchRecords = 2,
            maxBatchBytes = 50
        ) { "stable-id" }
        outbox.activate("s")
        val offered = "first\n".toByteArray()
        assertEquals(OutboxOfferResult.ACCEPTED, outbox.offer("s", "device", offered))
        offered[0] = 'X'.code.toByte()

        val batch = requireNotNull(outbox.beginBatch())
        val firstPayload = batch.payload()
        firstPayload[0] = 'Y'.code.toByte()
        assertEquals(OutboxOfferResult.ACCEPTED, outbox.offer("s", "battery", "second\n".toByteArray()))

        val retry = requireNotNull(outbox.beginBatch())
        assertSame(batch, retry)
        assertEquals("stable-id", retry.transferId)
        assertEquals("first\n", retry.payload().toString(Charsets.UTF_8))
    }

    @Test fun activationAndDeactivationClearAllSessionState() {
        val outbox = RealtimeOutbox(
            maxQueueRecords = 2,
            maxQueueBytes = 20,
            maxBatchRecords = 1,
            maxBatchBytes = 10
        )
        outbox.activate("old")
        outbox.offer("old", "device", "record".toByteArray())
        outbox.beginBatch()
        outbox.offer("old", "device", ByteArray(11))
        assertEquals(1, outbox.dropped)

        outbox.activate("new")
        assertEquals("new", outbox.activeSessionId())
        assertEquals(0, outbox.queuedCount())
        assertEquals(0, outbox.queuedByteCount())
        assertEquals(0, outbox.dropped)
        assertNull(outbox.currentBatch())

        outbox.deactivate()
        assertNull(outbox.activeSessionId())
        assertEquals(OutboxOfferResult.IGNORED_INACTIVE, outbox.offer("new", "x", byteArrayOf(1)))
    }
}
