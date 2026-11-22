package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NovaEmailOtpPolicyTest {
    @Test fun acceptsOnlySixDigitCodesAndPreservesLeadingZeroes() {
        assertEquals("001234", NovaEmailOtpPolicy.normalizeCode(" 001234 "))
        assertThrows(IllegalArgumentException::class.java) { NovaEmailOtpPolicy.normalizeCode("12345") }
        assertThrows(IllegalArgumentException::class.java) { NovaEmailOtpPolicy.normalizeCode("12a456") }
        assertThrows(IllegalArgumentException::class.java) { NovaEmailOtpPolicy.normalizeCode("1234567") }
    }

    @Test fun resendCooldownRoundsUpAndExpiresAtSixtySeconds() {
        val sent = 1_000_000L
        assertEquals(60L, NovaEmailOtpPolicy.remainingCooldownSeconds(sent, sent))
        assertEquals(1L, NovaEmailOtpPolicy.remainingCooldownSeconds(sent, sent + 59_001L))
        assertEquals(0L, NovaEmailOtpPolicy.remainingCooldownSeconds(sent, sent + 60_000L))
        assertEquals(0L, NovaEmailOtpPolicy.remainingCooldownSeconds(0L, sent))
        assertEquals(60L, NovaEmailOtpPolicy.remainingCooldownSeconds(sent, sent - 1L))
    }

    @Test fun parsesSupabaseErrorFieldsAndUsesSafeFallback() {
        assertEquals("Invalid email or OTP", NovaEmailOtpPolicy.errorMessage("{\"msg\":\"Invalid email or OTP\"}", 400))
        assertEquals("Email rate limit exceeded", NovaEmailOtpPolicy.errorMessage("{\"error_description\":\"Email rate limit exceeded\"}", 429))
        assertEquals("Cloud request failed (503)", NovaEmailOtpPolicy.errorMessage("not-json", 503))
    }
}
