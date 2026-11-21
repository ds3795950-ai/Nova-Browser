package com.nova.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShieldProtectionPolicyTest {
    @Test fun shieldOffDisablesPrivateFingerprintingProtection() {
        assertFalse(ShieldProtectionPolicy.fingerprintingInPrivateMode(ShieldMode.OFF))
    }
    @Test fun standardModeDoesNotForceFingerprintingProtection() {
        assertFalse(ShieldProtectionPolicy.fingerprintingInPrivateMode(ShieldMode.STANDARD))
    }
    @Test fun aggressiveModeEnablesPrivateFingerprintingProtection() {
        assertTrue(ShieldProtectionPolicy.fingerprintingInPrivateMode(ShieldMode.AGGRESSIVE))
    }
}
