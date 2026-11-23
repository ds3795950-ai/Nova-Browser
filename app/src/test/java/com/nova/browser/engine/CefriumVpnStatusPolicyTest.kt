package com.nova.browser.engine

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class CefriumVpnStatusPolicyTest {
    @Test fun missingCredentialsReportsFailClosedWhenProxyAndWebRtcAreProtected() {
        val status = CefriumVpnStatusPolicy.enabledStatus(false, "", 1, false, true, true)
        assertTrue(status.contains("blocked by the fail-closed proxy"))
        assertFalse(status.contains("proxy configured"))
    }
    @Test fun proxyFailureWithFallbackDoesNotReportConnected() {
        val status = CefriumVpnStatusPolicy.enabledStatus(true, "India", 1, false, true, true)
        assertTrue(status.contains("blocked by the fail-closed proxy"))
        assertFalse(status.contains("proxy configured"))
    }
    @Test fun webrtcFailureIsReportedEvenWhenProxyWasApplied() {
        val status = CefriumVpnStatusPolicy.enabledStatus(true, "India", 1, true, true, false)
        assertTrue(status.contains("WebRTC protection failed"))
        assertFalse(status.contains("WebRTC protection active"))
    }
    @Test fun successIsReportedOnlyWhenProxyAndWebRtcAreReady() {
        val status = CefriumVpnStatusPolicy.enabledStatus(true, "India", 1, true, true, true)
        assertTrue(status.contains("proxy configured"))
        assertTrue(status.contains("WebRTC protection active"))
    }
    @Test fun disablingReportsCleanupFailure() {
        assertTrue(CefriumVpnStatusPolicy.disabledStatus(false).contains("could not clear its proxy"))
    }
}
