package com.nova.browser

import com.nova.browser.engine.*
import org.junit.Assert.*
import org.junit.Test

class EngineSelectionPolicyTest {
    @Test fun normalModeDefaultsToCefrium() {
        val result = EngineSelectionPolicy.resolve(EngineSelectionRequest(cefriumAvailable = true))
        assertEquals(BrowserEngineType.CEFRIUM, result.engine)
        assertFalse(result.usedFallback)
    }

    @Test fun automaticFallsBackWhenCefriumUnavailableOrIncompatible() {
        assertTrue(EngineSelectionPolicy.resolve(EngineSelectionRequest(mode = BrowserEngineMode.AUTOMATIC, cefriumAvailable = false)).usedFallback)
        assertEquals(BrowserEngineType.GECKOVIEW, EngineSelectionPolicy.resolve(EngineSelectionRequest(mode = BrowserEngineMode.AUTOMATIC, cefriumAvailable = true, cefriumCompatible = false)).engine)
    }

    @Test fun explicitNormalModeIsRespected() {
        assertEquals(BrowserEngineType.GECKOVIEW, EngineSelectionPolicy.resolve(EngineSelectionRequest(mode = BrowserEngineMode.GECKOVIEW, cefriumAvailable = true)).engine)
        assertEquals(BrowserEngineType.CEFRIUM, EngineSelectionPolicy.resolve(EngineSelectionRequest(mode = BrowserEngineMode.CEFRIUM, cefriumAvailable = true)).engine)
    }

    @Test fun guestAlwaysUsesGeckoViewEvenWhenNormalModeIsCefrium() {
        val result = EngineSelectionPolicy.resolve(EngineSelectionRequest(mode = BrowserEngineMode.CEFRIUM, profile = BrowserProfileKind.GUEST, cefriumAvailable = true))
        assertEquals(BrowserEngineType.GECKOVIEW, result.engine)
        assertEquals("Guest Mode always uses GeckoView", result.reason)
    }

    @Test fun privateUsesCefriumWhenItsRequiredFeaturesAreAvailable() {
        val result = EngineSelectionPolicy.resolve(EngineSelectionRequest(mode = BrowserEngineMode.GECKOVIEW, profile = BrowserProfileKind.PRIVATE, cefriumAvailable = true, cefriumCompatible = true))
        assertEquals(BrowserEngineType.CEFRIUM, result.engine)
        assertFalse(result.usedFallback)
    }

    @Test fun privateFallsBackToGeckoViewWhenCefriumCannotProvideARequiredFeature() {
        val result = EngineSelectionPolicy.resolve(EngineSelectionRequest(profile = BrowserProfileKind.PRIVATE, cefriumAvailable = true, cefriumCompatible = false))
        assertEquals(BrowserEngineType.GECKOVIEW, result.engine)
        assertTrue(result.usedFallback)
    }

    @Test fun siteAffinityAppliesOnlyInAutomaticNormalMode() {
        val automatic = EngineSelectionPolicy.resolve(EngineSelectionRequest(mode = BrowserEngineMode.AUTOMATIC, siteAffinity = BrowserEngineType.GECKOVIEW, cefriumAvailable = true))
        val forced = EngineSelectionPolicy.resolve(EngineSelectionRequest(mode = BrowserEngineMode.CEFRIUM, siteAffinity = BrowserEngineType.GECKOVIEW, cefriumAvailable = true))
        assertEquals(BrowserEngineType.GECKOVIEW, automatic.engine)
        assertEquals(BrowserEngineType.CEFRIUM, forced.engine)
    }

    @Test fun privateAndGuestNeverUsePersistentCefriumProfile() {
        assertEquals(BrowserEngineProfilePolicy.NORMAL_CEFRIUM_PROFILE, BrowserEngineProfilePolicy.cefriumPersistentProfile(BrowserProfileKind.NORMAL))
        assertNull(BrowserEngineProfilePolicy.cefriumPersistentProfile(BrowserProfileKind.PRIVATE))
        assertNull(BrowserEngineProfilePolicy.cefriumPersistentProfile(BrowserProfileKind.GUEST))
        assertTrue(BrowserEngineProfilePolicy.cefriumUsesIncognitoProfile(BrowserProfileKind.PRIVATE))
        assertFalse(BrowserEngineProfilePolicy.cefriumUsesIncognitoProfile(BrowserProfileKind.GUEST))
        assertTrue(BrowserEngineProfilePolicy.isEphemeral(BrowserProfileKind.PRIVATE))
        assertTrue(BrowserEngineProfilePolicy.isEphemeral(BrowserProfileKind.GUEST))
    }
}
