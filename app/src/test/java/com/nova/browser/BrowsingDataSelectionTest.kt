package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.geckoview.StorageController

class BrowsingDataSelectionTest {
    @Test fun noSelectionDoesNotRequestAnyGeckoDataDeletion() {
        val selection = BrowsingDataSelection()
        assertFalse(selection.hasSelection())
        assertEquals(0L, selection.geckoFlags())
    }
    @Test fun siteDataSelectionUsesOnlyCookieStorageAndAuthFlags() {
        val flags = BrowsingDataSelection(siteData = true).geckoFlags()
        val expected = StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.DOM_STORAGES or StorageController.ClearFlags.AUTH_SESSIONS
        assertEquals(expected, flags)
    }
    @Test fun cacheAndSiteSettingsAreIndependentlySelectable() {
        assertEquals(StorageController.ClearFlags.ALL_CACHES, BrowsingDataSelection(cache = true).geckoFlags())
        assertEquals(StorageController.ClearFlags.SITE_SETTINGS, BrowsingDataSelection(siteSettings = true).geckoFlags())
    }
    @Test fun historyPasswordAndCefriumSelectionsDoNotClearGeckoWebsiteStorage() {
        val selection = BrowsingDataSelection(history = true, cefriumProfileData = true, savedPasswords = true)
        assertTrue(selection.hasSelection())
        assertEquals(0L, selection.geckoFlags())
    }
    @Test fun fireDefaultsClearHistorySiteDataCacheAndCefriumButNotPasswords() {
        val fire = BrowsingDataSelection.fireDefaults()
        assertTrue(fire.history && fire.siteData && fire.cache && fire.cefriumProfileData && fire.closeNormalTabs)
        assertFalse(fire.savedPasswords)
        assertFalse(fire.siteSettings)
        val expected = StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.DOM_STORAGES or StorageController.ClearFlags.AUTH_SESSIONS or StorageController.ClearFlags.ALL_CACHES
        assertEquals(expected, fire.geckoFlags())
    }
}
