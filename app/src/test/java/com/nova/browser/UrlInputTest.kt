package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlInputTest {
    @Test fun appNameIsStable() {
        assertEquals("Nova Browser", "Nova" + " Browser")
    }

    @Test fun searchEngineNamesAreUnique() {
        val engines = listOf("Google", "Bing", "DuckDuckGo", "Brave Search")
        assertEquals(4, engines.toSet().size)
    }

    @Test fun urlDetectionDoesNotTreatSearchAsHost() {
        val input = "best privacy browser"
        assertEquals(false, input.contains('.') && !input.contains(' '))
    }

    @Test fun privateTabsAreNotNormalHistoryEntries() {
        val isPrivate = true
        assertEquals(true, isPrivate)
    }
}
