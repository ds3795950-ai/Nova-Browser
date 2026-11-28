package com.nova.browser

import org.junit.Assert.assertTrue
import org.junit.Test

class NovaVpnHonestyPolicyTest {
    @Test fun vpnLabelsNeverClaimDeviceWideCoverage() {
        assertTrue(NovaVpnHonestyPolicy.vpnStatusLabel(true, true).contains("not an Android-wide VPN"))
        assertTrue(NovaVpnHonestyPolicy.vpnStatusLabel(true, false).contains("not a device-wide VPN"))
    }

    @Test fun guestLabelDistinguishesEmbeddedTorFromTorBrowser() {
        assertTrue(NovaVpnHonestyPolicy.guestTorStatusLabel(true, true).contains("not Tor Browser"))
    }
}
