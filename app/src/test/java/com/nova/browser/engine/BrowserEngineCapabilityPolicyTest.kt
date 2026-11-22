package com.nova.browser.engine

import org.junit.Assert.*
import org.junit.Test

class BrowserEngineCapabilityPolicyTest {
    @Test fun cefriumFeatureStaysOnCurrentEngineWhenSupported() {
        val route = BrowserEngineCapabilityPolicy.resolve(
            BrowserEngineType.CEFRIUM, BrowserProfileKind.NORMAL,
            BrowserEngineCapability.DOWNLOADS, BrowserEngineCapabilitySets.CEFRIUM
        )
        assertEquals(BrowserEngineType.CEFRIUM, route.engine)
        assertFalse(route.requiresSwitch)
    }

    @Test fun translationFallsBackFromCefriumToGeckoView() {
        val route = BrowserEngineCapabilityPolicy.resolve(
            BrowserEngineType.CEFRIUM, BrowserProfileKind.NORMAL,
            BrowserEngineCapability.TRANSLATION, BrowserEngineCapabilitySets.CEFRIUM
        )
        assertEquals(BrowserEngineType.GECKOVIEW, route.engine)
        assertTrue(route.requiresSwitch)
        assertTrue(route.reason.contains("GeckoView"))
    }

    @Test fun privateAndGuestProfilesNeverRouteToCefrium() {
        listOf(BrowserProfileKind.PRIVATE, BrowserProfileKind.GUEST).forEach { profile ->
            val route = BrowserEngineCapabilityPolicy.resolve(
                BrowserEngineType.CEFRIUM, profile,
                BrowserEngineCapability.TRANSLATION, BrowserEngineCapabilitySets.CEFRIUM
            )
            assertEquals(BrowserEngineType.GECKOVIEW, route.engine)
            assertTrue(route.requiresSwitch)
        }
    }

    @Test fun unsupportedFeatureReturnsUnavailableInsteadOfGuessing() {
        val route = BrowserEngineCapabilityPolicy.resolve(
            BrowserEngineType.GECKOVIEW, BrowserProfileKind.NORMAL,
            BrowserEngineCapability.VPN_PROXY, BrowserEngineCapabilitySets.GECKOVIEW
        )
        assertNull(route.engine)
        assertFalse(route.available)
    }
}
