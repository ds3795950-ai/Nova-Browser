package com.nova.browser.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ThreatProtectionPolicyTest {
    @Test
    fun geckoViewUsesNativeSafeBrowsing() {
        assertEquals(
            ThreatProtectionPolicy.Availability.NATIVE_SAFE_BROWSING_ACTIVE,
            ThreatProtectionPolicy.availability(BrowserEngineType.GECKOVIEW)
        )
    }

    @Test
    fun cefriumNeverClaimsSafeBrowsingCoverage() {
        assertEquals(
            ThreatProtectionPolicy.Availability.UNAVAILABLE_ON_CEFRIUM,
            ThreatProtectionPolicy.availability(BrowserEngineType.CEFRIUM)
        )
    }
}
