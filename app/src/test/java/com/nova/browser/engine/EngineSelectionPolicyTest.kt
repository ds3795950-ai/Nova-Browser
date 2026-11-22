package com.nova.browser.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineSelectionPolicyTest {
    @Test fun explicitCefriumPreferenceSelectsCefriumForCompatibleNormalTab() {
        val result = EngineSelectionPolicy.resolve(EngineSelectionRequest(
            mode = BrowserEngineMode.CEFRIUM,
            profile = BrowserProfileKind.NORMAL,
            cefriumAvailable = true,
            cefriumCompatible = true
        ))
        assertEquals(BrowserEngineType.CEFRIUM, result.engine)
        assertFalse(result.usedFallback)
    }

    @Test fun explicitGeckoPreferenceNeverSelectsCefrium() {
        val result = EngineSelectionPolicy.resolve(EngineSelectionRequest(
            mode = BrowserEngineMode.GECKOVIEW,
            profile = BrowserProfileKind.NORMAL,
            cefriumAvailable = true
        ))
        assertEquals(BrowserEngineType.GECKOVIEW, result.engine)
        assertFalse(result.usedFallback)
    }

    @Test fun automaticModeUsesExactSiteAffinityWhenAvailable() {
        val result = EngineSelectionPolicy.resolve(EngineSelectionRequest(
            mode = BrowserEngineMode.AUTOMATIC,
            profile = BrowserProfileKind.NORMAL,
            siteAffinity = BrowserEngineType.GECKOVIEW,
            cefriumAvailable = true,
            cefriumCompatible = true
        ))
        assertEquals(BrowserEngineType.GECKOVIEW, result.engine)
    }

    @Test fun automaticModeDefaultsToCefriumWithoutSiteAffinity() {
        val result = EngineSelectionPolicy.resolve(EngineSelectionRequest(
            mode = BrowserEngineMode.AUTOMATIC,
            profile = BrowserProfileKind.NORMAL,
            cefriumAvailable = true,
            cefriumCompatible = true
        ))
        assertEquals(BrowserEngineType.CEFRIUM, result.engine)
    }

    @Test fun incompatibleCefriumRequestFallsBackToGeckoView() {
        val result = EngineSelectionPolicy.resolve(EngineSelectionRequest(
            mode = BrowserEngineMode.CEFRIUM,
            profile = BrowserProfileKind.NORMAL,
            cefriumAvailable = true,
            cefriumCompatible = false
        ))
        assertEquals(BrowserEngineType.GECKOVIEW, result.engine)
        assertTrue(result.usedFallback)
    }

    @Test fun unavailableCefriumFallsBackToGeckoView() {
        val result = EngineSelectionPolicy.resolve(EngineSelectionRequest(
            mode = BrowserEngineMode.CEFRIUM,
            profile = BrowserProfileKind.NORMAL,
            cefriumAvailable = false
        ))
        assertEquals(BrowserEngineType.GECKOVIEW, result.engine)
        assertTrue(result.usedFallback)
    }

    @Test fun privateAndGuestProfilesAlwaysUseGeckoViewRegardlessOfPreference() {
        listOf(BrowserProfileKind.PRIVATE, BrowserProfileKind.GUEST).forEach { profile ->
            listOf(BrowserEngineMode.AUTOMATIC, BrowserEngineMode.CEFRIUM, BrowserEngineMode.GECKOVIEW).forEach { mode ->
                val result = EngineSelectionPolicy.resolve(EngineSelectionRequest(
                    mode = mode,
                    profile = profile,
                    siteAffinity = BrowserEngineType.CEFRIUM,
                    cefriumAvailable = true,
                    cefriumCompatible = true
                ))
                assertEquals("$profile must never use Cefrium in $mode", BrowserEngineType.GECKOVIEW, result.engine)
            }
        }
    }
}
