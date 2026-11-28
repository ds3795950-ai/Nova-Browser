package com.nova.browser

import org.mozilla.geckoview.StorageController

/** Exact data categories Nova can clear; Cefrium profile cleanup is handled separately. */
internal data class BrowsingDataSelection(
    val history: Boolean = false,
    val siteData: Boolean = false,
    val cache: Boolean = false,
    val siteSettings: Boolean = false,
    val cefriumProfileData: Boolean = false,
    val savedPasswords: Boolean = false,
    /** When true, UI should also close Normal tabs after clearing (Fire). Private/Guest excluded. */
    val closeNormalTabs: Boolean = false
) {
    fun geckoFlags(): Long {
        var flags = 0L
        if (siteData) flags = flags or StorageController.ClearFlags.COOKIES or
            StorageController.ClearFlags.DOM_STORAGES or StorageController.ClearFlags.AUTH_SESSIONS
        if (cache) flags = flags or StorageController.ClearFlags.ALL_CACHES
        if (siteSettings) flags = flags or StorageController.ClearFlags.SITE_SETTINGS
        return flags
    }

    fun hasSelection(): Boolean =
        history || siteData || cache || siteSettings || cefriumProfileData || savedPasswords

    companion object {
        /**
         * DuckDuckGo-style Fire defaults: history, cookies/site data, cache.
         * Does not clear saved passwords or site permission grants unless the user opts in elsewhere.
         */
        fun fireDefaults(): BrowsingDataSelection = BrowsingDataSelection(
            history = true,
            siteData = true,
            cache = true,
            siteSettings = false,
            cefriumProfileData = true,
            savedPasswords = false,
            closeNormalTabs = true
        )
    }
}
