package com.example.privagentcollector

import org.junit.Assert.*
import org.junit.Test

class PrivacyFilterTest {
    @Test fun blocksProtectedPackagesAndOwnUi() {
        for (name in listOf("com.example.bank", "com.bitwarden.mobile", "com.azure.authenticator",
            "com.ctbcbank.mobile", "com.example.collector")) {
            assertTrue(name, PrivacyFilter.blocked(name, "com.example.collector"))
        }
    }
    @Test fun customExclusionBlocksExactlySpecifiedPackage() {
        assertTrue(PrivacyFilter.blocked("com.example.private", "own", setOf("com.example.private")))
        assertFalse(PrivacyFilter.blocked("com.example.private.other", "own", setOf("com.example.private")))
    }
    @Test fun missingPackageIsNotCaptured() { assertTrue(PrivacyFilter.blocked(null, "own")) }
    @Test fun otpAndPasswordsAreRedacted() {
        assertEquals("[REDACTED]", PrivacyFilter.text("驗證碼為 456789"))
        assertEquals("[REDACTED]", PrivacyFilter.text("Your OTP is 123456"))
        assertEquals("[REDACTED]", PrivacyFilter.text("4567"))
    }
    @Test fun ordinaryTextSurvivesAndLongTextIsBounded() {
        assertEquals("晚上見", PrivacyFilter.text("晚上見"))
        assertEquals(512, PrivacyFilter.text("a".repeat(1000))!!.length)
        assertNull(PrivacyFilter.text(null))
    }
}
