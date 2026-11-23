package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPolicyTest {
    @Test fun storesOnlyHttpAndHttpsUrls() {
        assertEquals("https://example.com/a", HistoryPolicy.sanitizeForStorage("https://example.com/a"))
        assertNull(HistoryPolicy.sanitizeForStorage("about:neterror"))
        assertNull(HistoryPolicy.sanitizeForStorage("resource://gre/foo"))
        assertNull(HistoryPolicy.sanitizeForStorage("file:///private/data"))
        assertNull(HistoryPolicy.sanitizeForStorage("about:blank"))
    }

    @Test fun skipsUrlsWithSensitiveQueryParameters() {
        assertNull(HistoryPolicy.sanitizeForStorage("https://example.com/callback?code=abc&state=xyz"))
        assertNull(HistoryPolicy.sanitizeForStorage("https://example.com/reset?token=secret"))
        assertNull(HistoryPolicy.sanitizeForStorage("https://example.com/callback?%61ccess_token=secret"))
        assertEquals("https://example.com/search?q=nova", HistoryPolicy.sanitizeForStorage("https://example.com/search?q=nova#fragment"))
    }

    @Test fun removesUserInfo() {
        assertNull(HistoryPolicy.sanitizeForStorage("https://user:pass@example.com/path"))
    }

    @Test fun deduplicatesOnlyRapidRepeatVisits() {
        assertFalse(HistoryPolicy.shouldAppendVisit("https://example.com", 1_000L, "https://example.com", 2_000L))
        assertTrue(HistoryPolicy.shouldAppendVisit("https://example.com", 1_000L, "https://example.com", 3_000L))
        assertTrue(HistoryPolicy.shouldAppendVisit("https://example.com/a", 1_000L, "https://example.com/b", 1_001L))
    }
}
