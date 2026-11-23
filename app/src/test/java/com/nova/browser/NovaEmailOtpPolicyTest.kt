package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NovaEmailOtpPolicyTest {
    @Test fun normalizesOnlySixDigitCodes() {
        assertEquals("123456", NovaEmailOtpPolicy.normalizeCode(" 123456 "))
        assertThrows(IllegalArgumentException::class.java) { NovaEmailOtpPolicy.normalizeCode("12345") }
        assertThrows(IllegalArgumentException::class.java) { NovaEmailOtpPolicy.normalizeCode("12a456") }
    }

    @Test fun enforcesOneMinuteResendCooldown() {
        assertEquals(60L, NovaEmailOtpPolicy.remainingCooldownSeconds(1_000L, 1_000L))
        assertEquals(30L, NovaEmailOtpPolicy.remainingCooldownSeconds(1_000L, 31_000L))
        assertEquals(0L, NovaEmailOtpPolicy.remainingCooldownSeconds(1_000L, 61_000L))
    }

    @Test fun clockMovingBackwardsKeepsCooldown() {
        assertEquals(60L, NovaEmailOtpPolicy.remainingCooldownSeconds(2_000L, 1_000L))
    }

    @Test fun extractsAndBoundsCloudErrorMessages() {
        assertEquals("Email not confirmed", NovaEmailOtpPolicy.errorMessage("{\"message\":\"Email not confirmed\"}", 400))
        assertEquals("Cloud request failed (503)", NovaEmailOtpPolicy.errorMessage("not json", 503))
        assertEquals(180, NovaEmailOtpPolicy.errorMessage("{\"msg\":\"${"x".repeat(220)}\"}", 400).length)
    }
}
