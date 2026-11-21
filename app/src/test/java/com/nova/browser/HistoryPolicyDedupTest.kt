package com.nova.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPolicyDedupTest {
    @Test
    fun duplicateNavigationCallbackWithinWindowIsIgnored() {
        assertFalse(HistoryPolicy.shouldAppendVisit("https://example.com/", 10_000L, "https://example.com/", 11_500L))
    }

    @Test
    fun sameUrlCanBeRecordedAgainAfterWindow() {
        assertTrue(HistoryPolicy.shouldAppendVisit("https://example.com/", 10_000L, "https://example.com/", 12_100L))
    }

    @Test
    fun differentUrlIsAlwaysASeparateVisit() {
        assertTrue(HistoryPolicy.shouldAppendVisit("https://example.com/", 10_000L, "https://example.com/about", 10_100L))
    }

    @Test
    fun commonCamelCaseAuthenticationParametersAreRejectedFromHistory() {
        assertFalse(HistoryPolicy.shouldPersist("https://example.com/callback?accessToken=secret"))
        assertFalse(HistoryPolicy.shouldPersist("https://example.com/callback?clientSecret=secret"))
    }
}
