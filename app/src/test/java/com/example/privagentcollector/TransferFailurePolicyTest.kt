package com.example.privagentcollector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferFailurePolicyTest {
    @Test fun retriesOnlyTransportUnknownTimeoutRateLimitAndServerErrors() {
        assertTrue(TransferFailedException("transport").retryable)
        assertTrue(TransferFailedException("timeout", 408).retryable)
        assertTrue(TransferFailedException("rate limit", 429).retryable)
        assertTrue(TransferFailedException("server", 500).retryable)
        assertTrue(TransferFailedException("server", 599).retryable)
        assertFalse(TransferFailedException("bad request", 400).retryable)
        assertFalse(TransferFailedException("conflict", 409).retryable)
        assertFalse(TransferFailedException("redirect", 302).retryable)
    }
}
