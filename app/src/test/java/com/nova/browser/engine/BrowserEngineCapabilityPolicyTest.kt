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

    @Test fun extensionsFallBackToGeckoViewFromCefrium() {
        val route = BrowserEngineCapabilityPolicy.resolve(
            BrowserEngineType.CEFRIUM, BrowserProfileKind.NORMAL,
            BrowserEngineCapability.EXTENSIONS, BrowserEngineCapabilitySets.CEFRIUM
        )
        assertEquals(BrowserEngineType.GECKOVIEW, route.engine)
        assertTrue(route.requiresSwitch)
    }

    @Test fun unsupportedFeatureReturnsUnavailableInsteadOfGuessing() {
        val route = BrowserEngineCapabilityPolicy.resolve(
            BrowserEngineType.GECKOVIEW, BrowserProfileKind.NORMAL,
            BrowserEngineCapability.VPN_PROXY, BrowserEngineCapabilitySets.GECKOVIEW
        )
        assertNull(route.engine)
        assertFalse(route.available)
    }

    @Test fun readerAndDownloadsStayOnEitherEngineWhenDeclared() {
        listOf(BrowserEngineType.GECKOVIEW to BrowserEngineCapabilitySets.GECKOVIEW,
            BrowserEngineType.CEFRIUM to BrowserEngineCapabilitySets.CEFRIUM).forEach { (engine, capabilities) ->
            listOf(BrowserEngineCapability.READER_MODE, BrowserEngineCapability.DOWNLOADS,
                BrowserEngineCapability.SITE_PERMISSIONS, BrowserEngineCapability.DESKTOP_MODE).forEach { feature ->
                val route = BrowserEngineCapabilityPolicy.resolve(
                    engine, BrowserProfileKind.NORMAL, feature, capabilities
                )
                assertEquals("$feature should remain on $engine", engine, route.engine)
                assertFalse(route.requiresSwitch)
            }
        }
    }

    @Test fun geckoOnlyFeaturesNeverBecomeAvailableOnCefrium() {
        listOf(BrowserEngineCapability.TRANSLATION, BrowserEngineCapability.PASSWORD_AUTOFILL,
            BrowserEngineCapability.NOVA_SHIELD, BrowserEngineCapability.EXTENSIONS).forEach { feature ->
            val route = BrowserEngineCapabilityPolicy.resolve(
                BrowserEngineType.CEFRIUM, BrowserProfileKind.NORMAL, feature, BrowserEngineCapabilitySets.CEFRIUM
            )
            assertEquals("$feature must fall back to GeckoView", BrowserEngineType.GECKOVIEW, route.engine)
            assertTrue(route.requiresSwitch)
        }
    }

    @Test fun privateAndGuestProfilesNeverUseCefriumEvenWhenNormalModePrefersIt() {
        listOf(BrowserProfileKind.PRIVATE, BrowserProfileKind.GUEST).forEach { profile ->
            BrowserEngineCapability.values().forEach { feature ->
                val route = BrowserEngineCapabilityPolicy.resolve(
                    BrowserEngineType.GECKOVIEW, profile, feature, BrowserEngineCapabilitySets.GECKOVIEW
                )
                if (feature in BrowserEngineCapabilitySets.GECKOVIEW) {
                    assertEquals("$profile must stay on GeckoView for $feature", BrowserEngineType.GECKOVIEW, route.engine)
                } else {
                    assertNull("$profile must not invent support for $feature", route.engine)
                }
            }
        }
    }

}
