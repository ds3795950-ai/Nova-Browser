package com.nova.browser.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CefriumVpnControllerTest {
    @After fun reset() {
        CefriumVpnController.setEnabled(false)
        CefriumVpnController.updateCredentials(null)
    }

    @Test fun matchingProxyChallengeReceivesExtensionCredentials() {
        CefriumVpnController.setEnabled(true)
        CefriumVpnController.updateCredentials(
            CefriumVpnCredentials("proxy.1vpn.example", 443, "free-user", "session-token", "sgp")
        )
        var received: Pair<String, String>? = null
        var cancelled = false
        CefriumVpnController.provideProxyCredentials(
            "proxy.1vpn.example", 443, true,
            callback = { user, pass -> received = user to pass },
            cancel = { cancelled = true }
        )
        assertEquals("free-user" to "session-token", received)
        assertFalse(cancelled)
    }

    @Test fun nonProxyAndMismatchedHostChallengesAreRejected() {
        CefriumVpnController.setEnabled(true)
        CefriumVpnController.updateCredentials(
            CefriumVpnCredentials("proxy.1vpn.example", 443, "u", "p", "sgp")
        )
        var received = false
        var cancelled = false
        CefriumVpnController.provideProxyCredentials(
            "example.com", 443, false,
            callback = { _, _ -> received = true },
            cancel = { cancelled = true }
        )
        assertFalse(received)
        assertTrue(cancelled)
    }
}
