package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NovaHttpsOnlyPolicyTest {
    @Test fun disabledAllowsHttp() {
        assertEquals(NovaHttpsOnlyPolicy.Decision.ALLOW, NovaHttpsOnlyPolicy.decide("http://example.com", false))
    }
    @Test fun enabledUpgradesPublicHttp() {
        assertEquals(NovaHttpsOnlyPolicy.Decision.UPGRADE_TO_HTTPS, NovaHttpsOnlyPolicy.decide("http://example.com/path", true))
        assertEquals("https://example.com/path", NovaHttpsOnlyPolicy.upgradeToHttps("http://example.com/path"))
    }
    @Test fun allowsLocalHttpWhenConfigured() {
        assertEquals(NovaHttpsOnlyPolicy.Decision.ALLOW, NovaHttpsOnlyPolicy.decide("http://localhost:8080", true, true))
        assertEquals(NovaHttpsOnlyPolicy.Decision.ALLOW, NovaHttpsOnlyPolicy.decide("http://192.168.1.1/", true, true))
    }
    @Test fun blocksLocalHttpWhenDisallowed() {
        assertEquals(NovaHttpsOnlyPolicy.Decision.UPGRADE_TO_HTTPS, NovaHttpsOnlyPolicy.decide("http://192.168.1.1/", true, false))
    }
    @Test fun httpsAndNonHttpSchemesPass() {
        assertEquals(NovaHttpsOnlyPolicy.Decision.ALLOW, NovaHttpsOnlyPolicy.decide("https://secure.example", true))
        assertEquals(NovaHttpsOnlyPolicy.Decision.ALLOW, NovaHttpsOnlyPolicy.decide("about:blank", true))
    }
    @Test fun malformedHttpFailsClosed() {
        assertEquals(NovaHttpsOnlyPolicy.Decision.BLOCK, NovaHttpsOnlyPolicy.decide("http://example.com/a|b", true))
    }
    @Test fun upgradePreservesEscapedDelimitersAndFragment() {
        assertEquals(
            "https://example.com/a%2Fb?q=x%26admin%3Dtrue#part%2Fone",
            NovaHttpsOnlyPolicy.upgradeToHttps("http://example.com/a%2Fb?q=x%26admin%3Dtrue#part%2Fone")
        )
    }
    @Test fun allowsUniqueLocalIpv6ButUpgradesPublicIpv6() {
        assertEquals(NovaHttpsOnlyPolicy.Decision.ALLOW, NovaHttpsOnlyPolicy.decide("http://[fd00::1]/", true, true))
        assertEquals(NovaHttpsOnlyPolicy.Decision.UPGRADE_TO_HTTPS, NovaHttpsOnlyPolicy.decide("http://[2001:4860:4860::8888]/", true, true))
        assertEquals(NovaHttpsOnlyPolicy.Decision.UPGRADE_TO_HTTPS, NovaHttpsOnlyPolicy.decide("http://[fd00::1]/", true, false))
    }

    @Test fun upgradeIgnoresNonHttp() {
        assertNull(NovaHttpsOnlyPolicy.upgradeToHttps("https://example.com"))
        assertNull(NovaHttpsOnlyPolicy.upgradeToHttps("about:blank"))
    }
}
