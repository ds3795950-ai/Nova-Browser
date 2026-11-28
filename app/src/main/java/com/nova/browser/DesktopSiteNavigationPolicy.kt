package com.nova.browser

/** Resolves destination desktop mode without leaking normal-site preferences into Private or Guest tabs. */
internal object DesktopSiteNavigationPolicy {
    fun resolve(
        isPrivate: Boolean,
        isGuest: Boolean,
        currentTabPreference: Boolean,
        destinationHostSavedForDesktop: Boolean
    ): Boolean = if (isPrivate || isGuest) currentTabPreference else destinationHostSavedForDesktop
}
