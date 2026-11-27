package com.nova.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopSiteNavigationPolicyTest {
    @Test fun normalTabUsesDestinationHostPreference() {
        assertTrue(DesktopSiteNavigationPolicy.resolve(false, false, false, true))
        assertFalse(DesktopSiteNavigationPolicy.resolve(false, false, true, false))
    }

    @Test fun privateAndGuestTabsKeepTheirPerTabPreference() {
        assertTrue(DesktopSiteNavigationPolicy.resolve(true, false, true, false))
        assertFalse(DesktopSiteNavigationPolicy.resolve(true, false, false, true))
        assertTrue(DesktopSiteNavigationPolicy.resolve(false, true, true, false))
        assertFalse(DesktopSiteNavigationPolicy.resolve(false, true, false, true))
    }
}
