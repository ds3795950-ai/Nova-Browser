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
        assertEquals(BrowserEngineType.GECKOVIEW, EngineSelectionPolicy.resolve(EngineSelectionRequest(mode = BrowserEngineMode.AUTOMATIC, cefriumAvailable = false)).engine)
        assertEquals(BrowserEngineType.GECKOVIEW, EngineSelectionPolicy.resolve(EngineSelectionRequest(mode = BrowserEngineMode.AUTOMATIC, cefriumAvailable = true, cefriumCompatible = false)).engine)
    }

    @Test fun explicitNormalModeIsRespectedWhenAvailable() {
        val result = EngineSelectionPolicy.resolve(EngineSelectionRequest(mode = BrowserEngineMode.GECKOVIEW, cefriumAvailable = true))
        assertEquals(BrowserEngineType.GECKOVIEW, result.engine)
    }

    @Test fun guestAlwaysUsesGeckoViewEvenWhenNormalModeIsCefrium() {
        val result = EngineSelectionPolicy.resolve(
            EngineSelectionRequest(
                mode = BrowserEngineMode.CEFRIUM,
                profile = BrowserProfileKind.GUEST,
                cefriumAvailable = true
            )
        )
        assertEquals(BrowserEngineType.GECKOVIEW, result.engine)
        assertEquals("Guest Mode always uses GeckoView", result.reason)
    }

    @Test fun siteAffinityAppliesOnlyInAutomaticMode() {
        val automatic = EngineSelectionPolicy.resolve(EngineSelectionRequest(mode = BrowserEngineMode.AUTOMATIC, siteAffinity = BrowserEngineType.GECKOVIEW, cefriumAvailable = true))
        val forced = EngineSelectionPolicy.resolve(EngineSelectionRequest(mode = BrowserEngineMode.CEFRIUM, siteAffinity = BrowserEngineType.GECKOVIEW, cefriumAvailable = true))
        assertEquals(BrowserEngineType.GECKOVIEW, automatic.engine)
        assertEquals(BrowserEngineType.CEFRIUM, forced.engine)
    }

    @Test fun privateModeAlsoStaysOnGeckoView() {
        val result = EngineSelectionPolicy.resolve(
            EngineSelectionRequest(mode = BrowserEngineMode.CEFRIUM, profile = BrowserProfileKind.PRIVATE, cefriumAvailable = true)
        )
        assertEquals(BrowserEngineType.GECKOVIEW, result.engine)
    }

    @Test fun privateAndGuestProfilesNeverUsePersistentCefriumProfile() {
        assertEquals(BrowserEngineProfilePolicy.NORMAL_CEFRIUM_PROFILE, BrowserEngineProfilePolicy.cefriumPersistentProfile(BrowserProfileKind.NORMAL))
        assertNull(BrowserEngineProfilePolicy.cefriumPersistentProfile(BrowserProfileKind.PRIVATE))
        assertNull(BrowserEngineProfilePolicy.cefriumPersistentProfile(BrowserProfileKind.GUEST))
        assertTrue(BrowserEngineProfilePolicy.isEphemeral(BrowserProfileKind.GUEST))
    }
}
