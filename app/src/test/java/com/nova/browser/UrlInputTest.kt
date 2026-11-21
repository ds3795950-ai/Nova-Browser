package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlInputTest {
    @Test fun blankInputDoesNotNavigate() { assertNull(BrowserUrlResolver.resolve("   ")) }
    @Test fun preservesHttpUrls() { assertEquals("https://example.com/path", BrowserUrlResolver.resolve("https://example.com/path")) }
    @Test fun addsHttpsToBareDomain() { assertEquals("https://example.com", BrowserUrlResolver.resolve("example.com")) }
    @Test fun searchesPlainTextWithGoogleByDefault() { assertEquals("https://www.google.com/search?q=hello%20world", BrowserUrlResolver.resolve("hello world")) }
    @Test fun routesToBing() { assertEquals("https://www.bing.com/search?q=nova", BrowserUrlResolver.resolve("nova", "Bing")) }
    @Test fun routesToDuckDuckGo() { assertEquals("https://duckduckgo.com/?q=nova", BrowserUrlResolver.resolve("nova", "DuckDuckGo")) }
    @Test fun routesToBraveSearch() { assertEquals("https://search.brave.com/search?q=nova", BrowserUrlResolver.resolve("nova", "Brave Search")) }
    @Test fun preservesAboutBlank() { assertEquals("about:blank", BrowserUrlResolver.resolve("about:blank")) }
    @Test fun encodesReservedCharactersInSearch() { assertEquals("https://www.google.com/search?q=hello%20%26%20nova%3F", BrowserUrlResolver.resolve("hello & nova?")) }
    @Test fun unknownEngineFallsBackToGoogle() { assertEquals("https://www.google.com/search?q=nova", BrowserUrlResolver.resolve("nova", "Unknown")) }
    @Test fun trimsInput() { assertEquals("https://example.com", BrowserUrlResolver.resolve("  example.com  ")) }
    @Test fun preservesSupportedNonHttpSchemes() {
        listOf("mailto:user@example.com", "tel:+15551234567", "ftp://ftp.example.com/file.zip", "data:text/plain,hello",
            "javascript:alert(1)", "file:///storage/emulated/0/a.txt", "blob:https://example.com/123",
            "intent://scan/#Intent;scheme=zxing;end", "geo:0,0?q=coffee", "sms:+15551234567",
            "ws://example.com/socket", "wss://example.com/socket").forEach { assertEquals(it, BrowserUrlResolver.resolve(it)) }
    }
    @Test fun recognizesDomainsWithPathsAndPorts() {
        assertEquals("https://example.com:8443/path?q=1", BrowserUrlResolver.resolve("example.com:8443/path?q=1"))
        assertEquals("https://localhost:8080", BrowserUrlResolver.resolve("localhost:8080"))
        assertEquals("https://127.0.0.1:3000", BrowserUrlResolver.resolve("127.0.0.1:3000"))
        assertEquals("https://192.168.1.1", BrowserUrlResolver.resolve("192.168.1.1"))
        assertEquals("https://[::1]:8080", BrowserUrlResolver.resolve("[::1]:8080"))
    }
    @Test fun recognizesInternationalizedDomainNames() { assertEquals("https://例え.テスト", BrowserUrlResolver.resolve("例え.テスト")) }
    @Test fun malformedHostAndUnknownSchemeBecomeSearches() {
        assertEquals("https://www.google.com/search?q=999.999.999.999", BrowserUrlResolver.resolve("999.999.999.999"))
        assertEquals("https://www.google.com/search?q=custom%3Avalue", BrowserUrlResolver.resolve("custom:value"))
    }
}
