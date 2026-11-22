package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortcutFaviconLoaderTest {
    @Test fun derivesOnlyTheOriginFaviconOverHttps() {
        assertEquals("https://example.com/favicon.ico", ShortcutFaviconLoader.faviconUrl("https://example.com/path?q=1"))
        assertEquals("https://example.com/favicon.ico", ShortcutFaviconLoader.faviconUrl("http://example.com/path"))
    }

    @Test fun rejectsNonWebAndHostlessUris() {
        assertNull(ShortcutFaviconLoader.faviconUrl("file:///etc/passwd"))
        assertNull(ShortcutFaviconLoader.faviconUrl("about:blank"))
    }
}
