package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryPolicyTest {
    @Test fun storesOnlyHttpAndHttpsUrls() {
        assertEquals("https://example.com/a", HistoryPolicy.sanitizeForStorage("https://example.com/a"))
        assertNull(HistoryPolicy.sanitizeForStorage("about:neterror"))
        assertNull(HistoryPolicy.sanitizeForStorage("resource://gre/foo"))
        assertNull(HistoryPolicy.sanitizeForStorage("file:///private/data"))
    }

    @Test fun skipsUrlsWithSensitiveQueryParameters() {
        assertNull(HistoryPolicy.sanitizeForStorage("https://example.com/callback?code=abc&state=xyz"))
        assertNull(HistoryPolicy.sanitizeForStorage("https://example.com/reset?token=secret"))
        assertEquals("https://example.com/search?q=nova", HistoryPolicy.sanitizeForStorage("https://example.com/search?q=nova#fragment"))
    }

    @Test fun removesUserInfo() {
        assertNull(HistoryPolicy.sanitizeForStorage("https://user:pass@example.com/path"))
    }
}
