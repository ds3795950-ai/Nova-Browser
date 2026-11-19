package com.nova.browser

import com.nova.browser.engine.*
import org.junit.Assert.*
import org.junit.Test

class EngineSelectionPolicyTest {
    @Test fun automaticPrefersCefriumForNormalTabs() {
        val result = EngineSelectionPolicy.resolve(EngineSelectionRequest(cefriumAvailable = true))
        assertEquals(BrowserEngineType.CEFRIUM, result.engine)
        assertFalse(result.usedFallback)
    }

    @Test fun automaticFallsBackWhenCefriumUnavailableOrIncompatible() {
        assertTrue(EngineSelectionPolicy.resolve(EngineSelectionRequest(cefriumAvailable = false)).usedFallback)
        assertEquals(BrowserEngineType.GECKOVIEW, EngineSelectionPolicy.resolve(EngineSelectionRequest(cefriumAvailable = false)).engine)
        assertEquals(BrowserEngineType.GECKOVIEW, EngineSelectionPolicy.resolve(EngineSelectionRequest(cefriumAvailable = true, cefriumCompatible = false)).engine)
    }

    @Test fun explicitNormalModeIsRespectedWhenAvailable() {
        val result = EngineSelectionPolicy.resolve(EngineSelectionRequest(mode = BrowserEngineMode.GECKOVIEW, cefriumAvailable = true))
        assertEquals(BrowserEngineType.GECKOVIEW, result.engine)
    }

    @Test fun guestDefaultsToGeckoAndOnlyExplicitOverrideSelectsCefrium() {
        val default = EngineSelectionPolicy.resolve(EngineSelectionRequest(profile = BrowserProfileKind.GUEST, cefriumAvailable = true))
        val chosen = EngineSelectionPolicy.resolve(EngineSelectionRequest(profile = BrowserProfileKind.GUEST, guestEngineOverride = BrowserEngineType.CEFRIUM, cefriumAvailable = true))
        assertEquals(BrowserEngineType.GECKOVIEW, default.engine)
        assertEquals(BrowserEngineType.CEFRIUM, chosen.engine)
    }

    @Test fun siteAffinityAppliesOnlyInAutomaticMode() {
        val automatic = EngineSelectionPolicy.resolve(EngineSelectionRequest(siteAffinity = BrowserEngineType.GECKOVIEW, cefriumAvailable = true))
        val forced = EngineSelectionPolicy.resolve(EngineSelectionRequest(mode = BrowserEngineMode.CEFRIUM, siteAffinity = BrowserEngineType.GECKOVIEW, cefriumAvailable = true))
        assertEquals(BrowserEngineType.GECKOVIEW, automatic.engine)
        assertEquals(BrowserEngineType.CEFRIUM, forced.engine)
    }

    @Test fun privateAndGuestProfilesNeverUsePersistentCefriumProfile() {
        assertEquals(BrowserEngineProfilePolicy.NORMAL_CEFRIUM_PROFILE, BrowserEngineProfilePolicy.cefriumPersistentProfile(BrowserProfileKind.NORMAL))
        assertNull(BrowserEngineProfilePolicy.cefriumPersistentProfile(BrowserProfileKind.PRIVATE))
        assertNull(BrowserEngineProfilePolicy.cefriumPersistentProfile(BrowserProfileKind.GUEST))
        assertTrue(BrowserEngineProfilePolicy.isEphemeral(BrowserProfileKind.GUEST))
    }
}
