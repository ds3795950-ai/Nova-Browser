package com.nova.browser.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserEngineCapabilityContractTest {
    @Test fun geckoAdvertisesItsNativeFeatureSet() {
        val expected = setOf(BrowserEngineCapability.DOWNLOADS, BrowserEngineCapability.SITE_PERMISSIONS,
            BrowserEngineCapability.READER_MODE, BrowserEngineCapability.DESKTOP_MODE,
            BrowserEngineCapability.TRANSLATION, BrowserEngineCapability.PASSWORD_AUTOFILL,
            BrowserEngineCapability.NOVA_SHIELD, BrowserEngineCapability.EXTENSIONS)
        assertTrue(BrowserEngineCapabilitySets.GECKOVIEW.containsAll(expected))
    }
    @Test fun cefriumDoesNotClaimGeckoOnlyFeatures() {
        val unsupported = setOf(BrowserEngineCapability.TRANSLATION, BrowserEngineCapability.PASSWORD_AUTOFILL,
            BrowserEngineCapability.NOVA_SHIELD, BrowserEngineCapability.EXTENSIONS)
        unsupported.forEach { assertFalse("Cefrium must not advertise $it", it in BrowserEngineCapabilitySets.CEFRIUM) }
    }
    @Test fun cefriumAdvertisesImplementedCommonFeaturesAndProxy() {
        val expected = setOf(BrowserEngineCapability.DOWNLOADS, BrowserEngineCapability.SITE_PERMISSIONS,
            BrowserEngineCapability.READER_MODE, BrowserEngineCapability.DESKTOP_MODE, BrowserEngineCapability.VPN_PROXY,
            BrowserEngineCapability.PASSWORD_FILL, BrowserEngineCapability.IMAGE_BLOCKING,
            BrowserEngineCapability.SITE_COOKIE_CONTROLS, BrowserEngineCapability.TRACKER_BLOCKING,
            BrowserEngineCapability.SCRIPT_ADDONS)
        assertTrue(BrowserEngineCapabilitySets.CEFRIUM.containsAll(expected))
    }
    @Test fun cefriumAdvertisesOnlyExplicitUserInitiatedPasswordFill() {
        assertTrue(BrowserEngineCapability.PASSWORD_FILL in BrowserEngineCapabilitySets.CEFRIUM)
        assertFalse(BrowserEngineCapability.PASSWORD_AUTOFILL in BrowserEngineCapabilitySets.CEFRIUM)
    }

    @Test fun cefriumAdvertisesRequestLevelImageCookieAndTrackerControls() {
        assertTrue(BrowserEngineCapability.IMAGE_BLOCKING in BrowserEngineCapabilitySets.CEFRIUM)
        assertTrue(BrowserEngineCapability.SITE_COOKIE_CONTROLS in BrowserEngineCapabilitySets.CEFRIUM)
        assertTrue(BrowserEngineCapability.TRACKER_BLOCKING in BrowserEngineCapabilitySets.CEFRIUM)
    }

    @Test fun privateAndGuestRemainOnGeckoForSavedLoginAutofill() {
        listOf(BrowserProfileKind.PRIVATE, BrowserProfileKind.GUEST).forEach { profile ->
            val route = BrowserEngineCapabilityPolicy.resolve(BrowserEngineType.GECKOVIEW, profile,
                BrowserEngineCapability.PASSWORD_AUTOFILL, BrowserEngineCapabilitySets.GECKOVIEW)
            assertEquals(BrowserEngineType.GECKOVIEW, route.engine)
            assertFalse(route.requiresSwitch)
        }
    }
}
