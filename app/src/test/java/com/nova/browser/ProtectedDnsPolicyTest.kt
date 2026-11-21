package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectedDnsPolicyTest {
    @Test fun enabledPrefersEncryptedDnsWithoutFailingClosed() {
        assertEquals(ProtectedDnsMode.PREFER_HTTPS, ProtectedDnsPolicy.mode(true))
    }
    @Test fun disabledTurnsOffProtectedDns() {
        assertEquals(ProtectedDnsMode.OFF, ProtectedDnsPolicy.mode(false))
    }
}
