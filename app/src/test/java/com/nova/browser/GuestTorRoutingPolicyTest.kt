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
}
