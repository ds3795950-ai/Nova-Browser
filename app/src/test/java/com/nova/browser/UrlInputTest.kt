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

    @Test fun privateAddressRangesUseHttp() {
        assertEquals("http://10.0.0.4", BrowserUrlResolver.resolve("10.0.0.4"))
        assertEquals("http://172.20.0.4", BrowserUrlResolver.resolve("172.20.0.4"))
        assertEquals("http://127.0.0.1", BrowserUrlResolver.resolve("127.0.0.1"))
    }

    @Test fun publicIpv4UsesHttps() {
        assertEquals("https://8.8.8.8", BrowserUrlResolver.resolve("8.8.8.8"))
    }

    @Test fun decimalNumberIsSearchedNotNavigated() {
        assertEquals("https://www.google.com/search?q=3.14", BrowserUrlResolver.resolve("3.14"))
    }

    @Test fun invalidIpv4IsSearched() {
        assertEquals("https://www.google.com/search?q=999.999.1.1", BrowserUrlResolver.resolve("999.999.1.1"))
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

    @Test fun supportedCustomSchemesPassThrough() {
        assertEquals("mailto:hello@example.com", BrowserUrlResolver.resolve("mailto:hello@example.com"))
        assertEquals("intent://scan/#Intent;scheme=zxing;end", BrowserUrlResolver.resolve("intent://scan/#Intent;scheme=zxing;end"))
    }

    @Test fun javascriptSchemeIsTreatedAsSearchText() {
        assertEquals("https://www.google.com/search?q=javascript%3Aalert%281%29", BrowserUrlResolver.resolve("javascript:alert(1)"))
    }

    @Test fun domainWithPathAndQuery() {
        assertEquals("https://example.com/a?b=1", BrowserUrlResolver.resolve("example.com/a?b=1"))
    }

    @Test fun domainWithPort() {
        assertEquals("https://example.com:8080", BrowserUrlResolver.resolve("example.com:8080"))
    }

    @Test fun rejectsInvalidPorts() {
        assertEquals("https://www.google.com/search?q=example.com%3A70000", BrowserUrlResolver.resolve("example.com:70000"))
        assertEquals("https://www.google.com/search?q=example.com%3A0", BrowserUrlResolver.resolve("example.com:0"))
    }

    @Test fun unicodeDomainIsNavigated() {
        assertEquals("https://münchen.de", BrowserUrlResolver.resolve("münchen.de"))
    }

    @Test fun underscoreHostnameIsNavigated() {
        assertEquals("https://my_bucket.s3.amazonaws.com", BrowserUrlResolver.resolve("my_bucket.s3.amazonaws.com"))
    }

    @Test fun trailingDotHostnameIsNavigated() {
        assertEquals("https://example.com.", BrowserUrlResolver.resolve("example.com."))

    @Test fun localDomainUsesHttp() {
        assertEquals("http://router.local", BrowserUrlResolver.resolve("router.local"))
    }

    @Test fun ipv6LiteralIsAccepted() {
        assertEquals("http://[::1]:8080", BrowserUrlResolver.resolve("[::1]:8080"))
    }
}
