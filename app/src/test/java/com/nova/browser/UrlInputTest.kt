package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlInputTest {
    @Test fun blankInputDoesNotNavigate() {
        assertNull(BrowserUrlResolver.resolve("   "))
    }

    @Test fun preservesHttpsUrl() {
        assertEquals("https://example.com/path", BrowserUrlResolver.resolve("https://example.com/path"))
    }

    @Test fun addsHttpsToBareDomain() {
        assertEquals("https://example.com", BrowserUrlResolver.resolve("example.com"))
    }

    @Test fun searchesPlainTextWithGoogleByDefault() {
        assertEquals("https://www.google.com/search?q=hello%20world", BrowserUrlResolver.resolve("hello world"))
    }

    @Test fun routesToBing() {
        assertEquals("https://www.bing.com/search?q=nova", BrowserUrlResolver.resolve("nova", "Bing"))
    }

    @Test fun routesToDuckDuckGo() {
        assertEquals("https://duckduckgo.com/?q=nova", BrowserUrlResolver.resolve("nova", "DuckDuckGo"))
    }

    @Test fun routesToBraveSearch() {
        assertEquals("https://search.brave.com/search?q=nova", BrowserUrlResolver.resolve("nova", "Brave Search"))
    }

    @Test fun preservesAboutBlank() {
        assertEquals("about:blank", BrowserUrlResolver.resolve("about:blank"))
    }

    @Test fun encodesReservedCharactersInSearch() {
        assertEquals("https://www.google.com/search?q=hello%20%26%20nova%3F", BrowserUrlResolver.resolve("hello & nova?"))
    }

    @Test fun unknownEngineFallsBackToGoogle() {
        assertEquals("https://www.google.com/search?q=nova", BrowserUrlResolver.resolve("nova", "Unknown"))
    }

    @Test fun trimsInput() {
        assertEquals("https://example.com", BrowserUrlResolver.resolve("  example.com  "))
    }
}
