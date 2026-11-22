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

    @Test fun localhostUsesHttp() {
        assertEquals("http://localhost:3000", BrowserUrlResolver.resolve("localhost:3000"))
    }

    @Test fun lanIpUsesHttp() {
        assertEquals("http://192.168.1.1:8080/admin", BrowserUrlResolver.resolve("192.168.1.1:8080/admin"))
    }

    @Test fun decimalNumberIsSearchedNotNavigated() {
        assertEquals("https://www.google.com/search?q=3.14", BrowserUrlResolver.resolve("3.14"))
    }

    @Test fun emailIsSearched() {
        assertEquals("https://www.google.com/search?q=me%40example.com", BrowserUrlResolver.resolve("me@example.com"))
    }

    @Test fun fileSchemePassesThrough() {
        assertEquals("file:///sdcard/a.html", BrowserUrlResolver.resolve("file:///sdcard/a.html"))
    }

    @Test fun viewSourceSchemePassesThrough() {
        assertEquals("view-source:https://example.com", BrowserUrlResolver.resolve("view-source:https://example.com"))
    }

    @Test fun domainWithPathAndQuery() {
        assertEquals("https://example.com/a?b=1", BrowserUrlResolver.resolve("example.com/a?b=1"))
    }

    @Test fun domainWithPort() {
        assertEquals("https://example.com:8080", BrowserUrlResolver.resolve("example.com:8080"))
    }

    @Test fun unicodeDomainIsNavigated() {
        assertEquals("https://münchen.de", BrowserUrlResolver.resolve("münchen.de"))
    }
}
