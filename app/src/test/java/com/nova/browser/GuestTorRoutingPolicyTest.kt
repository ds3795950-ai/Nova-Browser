package com.nova.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestTorRoutingPolicyTest {
    @Test fun guestCannotStartBeforeTorCircuitIsBuilt() {
        assertFalse(GuestTorRoutingPolicy.canEnterGuest(false, 9050, true))
    }

    @Test fun guestCannotStartWithoutValidSocksPort() {
        assertFalse(GuestTorRoutingPolicy.canEnterGuest(true, 0, true))
        assertFalse(GuestTorRoutingPolicy.canEnterGuest(true, 65536, true))
    }

    @Test fun guestCannotStartUntilProxyBridgeConfirmsLeakProtection() {
        assertFalse(GuestTorRoutingPolicy.canEnterGuest(true, 9050, false))
        assertTrue(GuestTorRoutingPolicy.canEnterGuest(true, 9050, true))
    }

    @Test fun privateTabsBlockGuestTorStartup() {
        assertFalse(GuestTorIsolationPolicy.canStart(hasPrivateTabs = true))
        assertTrue(GuestTorIsolationPolicy.canStart(hasPrivateTabs = false))
    }

    @Test fun guestTorSuspendsOnlyPersistentGeckoSessions() {
        assertTrue(GuestTorIsolationPolicy.shouldSuspendNormalTab(false, false, com.nova.browser.engine.BrowserEngineType.GECKOVIEW))
        assertFalse(GuestTorIsolationPolicy.shouldSuspendNormalTab(false, false, com.nova.browser.engine.BrowserEngineType.CEFRIUM))
        assertFalse(GuestTorIsolationPolicy.shouldSuspendNormalTab(false, true, com.nova.browser.engine.BrowserEngineType.GECKOVIEW))
        assertFalse(GuestTorIsolationPolicy.shouldSuspendNormalTab(true, false, com.nova.browser.engine.BrowserEngineType.GECKOVIEW))
    }

    @Test fun tabRestoreWaitsUntilGuestTorStartupAndCircuitValidationFinish() {
        assertTrue(GuestTorIsolationPolicy.shouldDeferTabRestore(true, false, false))
        assertTrue(GuestTorIsolationPolicy.shouldDeferTabRestore(false, true, false))
        assertFalse(GuestTorIsolationPolicy.shouldDeferTabRestore(false, true, true))
        assertFalse(GuestTorIsolationPolicy.shouldDeferTabRestore(false, false, false))
    }
}
