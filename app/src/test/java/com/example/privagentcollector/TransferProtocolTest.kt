package com.example.privagentcollector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferProtocolTest {
    @Test fun normalizesBaseAndBuildsContractEndpoints() {
        val input = "  HTTP://Example.COM:8080/dev/  "
        assertEquals("http://example.com:8080/dev", TransferProtocol.normalizeBaseUrl(input))
        assertEquals("http://example.com:8080/dev/health", TransferProtocol.healthUrl(input))
        assertEquals(
            "http://example.com:8080/dev/api/v1/transmissions",
            TransferProtocol.transmissionsUrl(input)
        )
    }

    @Test fun acceptsHttpsIpv4AndIpv6Hosts() {
        assertEquals("https://192.168.1.10:8443", TransferProtocol.normalizeBaseUrl("https://192.168.1.10:8443/"))
        assertEquals("http://[::1]:8000", TransferProtocol.normalizeBaseUrl("http://[::1]:8000"))
    }

    @Test fun rejectsUnsupportedOrAmbiguousUrls() {
        listOf(
            "",
            "receiver.local:8000",
            "ftp://receiver.local",
            "http:///missing-host",
            "http://user:password@receiver.local",
            "http://receiver.local?mode=raw",
            "http://receiver.local/#fragment",
            "http://receiver.local:0"
        ).forEach { value ->
            assertTrue("Expected rejection for $value", runCatching {
                TransferProtocol.normalizeBaseUrl(value)
            }.isFailure)
        }
    }

    @Test fun parsesReceiverAcknowledgement() {
        val ack = TransferProtocol.parseAcknowledgement(
            """{"transferId":"abc-123","receivedRecords":12,"accepted":10,"duplicates":2,"duplicate":true,"sha256":"aabbcc","transmissionMode":"periodic","collectionSessionId":"session-1"}"""
        )
        assertEquals(12, ack.receivedRecords)
        assertEquals(10, ack.accepted)
        assertEquals(2, ack.duplicates)
        assertEquals(true, ack.duplicate)
        assertEquals("aabbcc", ack.sha256)
        assertEquals("abc-123", ack.serverTransferId)
        assertEquals("periodic", ack.transmissionMode)
        assertEquals("session-1", ack.collectionSessionId)
    }

    @Test fun missingOrOversizedAcknowledgementFieldsStayNull() {
        val ack = TransferProtocol.parseAcknowledgement("""{"receivedRecords":99999999999999999999}""")
        assertNull(ack.receivedRecords)
        assertNull(ack.accepted)
        assertNull(ack.duplicates)
        assertNull(ack.duplicate)
        assertNull(ack.sha256)
        assertNull(ack.serverTransferId)
        assertNull(ack.transmissionMode)
        assertNull(ack.collectionSessionId)
    }

    @Test fun transferIdsAndWireConstantsMatchContract() {
        val id = TransferProtocol.newTransferId()
        assertTrue(TransferProtocol.isValidTransferId(id))
        assertFalse(TransferProtocol.isValidTransferId("not-a-uuid"))
        assertEquals("/api/v1/transmissions", TransferProtocol.TRANSMISSIONS_PATH)
        assertEquals("/health", TransferProtocol.HEALTH_PATH)
        assertEquals("X-Transfer-Id", TransferProtocol.TRANSFER_ID_HEADER)
        assertEquals("X-Record-Count", TransferProtocol.RECORD_COUNT_HEADER)
        assertEquals("X-Payload-Sha256", TransferProtocol.PAYLOAD_SHA256_HEADER)
        assertEquals("X-Client-Version", TransferProtocol.CLIENT_VERSION_HEADER)
        assertEquals("X-Transmission-Mode", TransferProtocol.TRANSMISSION_MODE_HEADER)
        assertEquals("X-Client-Sent-At", TransferProtocol.CLIENT_SENT_AT_HEADER)
        assertEquals("X-Collection-Session-Id", TransferProtocol.COLLECTION_SESSION_HEADER)
        assertEquals(setOf("baseline", "periodic", "immediate"), TransferProtocol.LIVE_MODES)
    }

    @Test fun retryReusesTransferIdOnlyForTheSamePayload() {
        val pendingId = "4f6c9cc4-7497-4d59-8937-85b4f847d652"
        assertEquals(
            pendingId,
            TransferProtocol.transferIdForPayload("same-sha", "same-sha", pendingId) { "new-id" }
        )
        assertEquals(
            "new-id",
            TransferProtocol.transferIdForPayload("changed-sha", "same-sha", pendingId) { "new-id" }
        )
        assertEquals(
            "new-id",
            TransferProtocol.transferIdForPayload("same-sha", "same-sha", "invalid") { "new-id" }
        )
    }
}
