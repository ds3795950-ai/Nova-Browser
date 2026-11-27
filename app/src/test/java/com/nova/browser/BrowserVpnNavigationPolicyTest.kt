package com.nova.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserVpnNavigationPolicyTest {
    @Test fun disabledVpnDoesNotBlockNormalNavigation() {
        assertFalse(BrowserVpnNavigationPolicy.shouldBlockNavigation(false, false))
        assertFalse(BrowserVpnNavigationPolicy.shouldBlockNavigation(false, true))
    }

    @Test fun enabledVpnBlocksUntilProxyHandshakeIsVerified() {
        assertTrue(BrowserVpnNavigationPolicy.shouldBlockNavigation(true, false))
        assertFalse(BrowserVpnNavigationPolicy.shouldBlockNavigation(true, true))
    }
}
