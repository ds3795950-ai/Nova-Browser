package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserProxyStatusPolicyTest {
    @Test fun disabledStateDoesNotDependOnProviderText() {
        assertEquals("Browser proxy: Off", BrowserProxyStatusPolicy.describe(false, false, "Singapore", "error"))
    }
    @Test fun unsupportedStateShowsProviderDetailWithoutStringParsing() {
        assertEquals("Browser proxy not ready: Bridge disconnected", BrowserProxyStatusPolicy.describe(true, false, "Singapore", "Bridge disconnected"))
    }
    @Test fun supportedStateNamesBrowserScopeAndVerificationLimit() {
        assertEquals("Browser proxy: On · Singapore · browser requests configured. Verify the exit IP independently.",
            BrowserProxyStatusPolicy.describe(true, true, "Singapore", "error-like informational text"))
    }
}
