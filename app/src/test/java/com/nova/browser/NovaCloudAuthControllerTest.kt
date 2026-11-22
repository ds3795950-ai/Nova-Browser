package com.nova.browser

import org.junit.Assert.*
import org.junit.Test

class NovaCloudAuthControllerTest {
    @Test fun emailNormalizationIsStable() {
        assertEquals("user@example.com", NovaCloudAuthController.normalizeEmail(" User@Example.COM "))
    }
    @Test fun otpRequiresSixDigits() {
        assertTrue(NovaCloudAuthController.isValidOtp("012345"))
        assertFalse(NovaCloudAuthController.isValidOtp("12345"))
        assertFalse(NovaCloudAuthController.isValidOtp("1234567"))
        assertFalse(NovaCloudAuthController.isValidOtp("12a456"))
    }
    @Test fun passphraseRequiresTenCharacters() {
        assertFalse(NovaCloudAuthController.isValidPassphrase("short".toCharArray()))
        assertTrue(NovaCloudAuthController.isValidPassphrase("long-enough".toCharArray()))
    }
}
