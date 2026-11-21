package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectedDnsPolicyTest {
    @Test fun disabledModeDoesNotForceTrustedResolver() {
        assertEquals(ProtectedDnsMode.OFF, ProtectedDnsPolicy.mode(false))
    }

    @Test fun enabledModePrefersHttpsResolver() {
        assertEquals(ProtectedDnsMode.PREFER_HTTPS, ProtectedDnsPolicy.mode(true))
    }
}
