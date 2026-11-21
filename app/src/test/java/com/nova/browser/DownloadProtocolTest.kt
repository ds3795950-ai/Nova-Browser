package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadProtocolTest {
    @Test fun parsesValidContentRange() {
        val range = DownloadProtocol.parseContentRange("bytes 1024-2047/4096")
        assertEquals(1024L, range?.start); assertEquals(2047L, range?.end); assertEquals(4096L, range?.total)
    }
    @Test fun parsesUnknownTotalContentRange() {
        val range = DownloadProtocol.parseContentRange("bytes 0-99/*")
        assertEquals(0L, range?.start); assertNull(range?.total)
    }
    @Test fun rejectsMalformedOrImpossibleContentRange() {
        assertNull(DownloadProtocol.parseContentRange("bytes 10-5/20"))
        assertNull(DownloadProtocol.parseContentRange("bytes 10-20/15"))
        assertNull(DownloadProtocol.parseContentRange("not a range"))
        assertNull(DownloadProtocol.parseContentRange(null))
    }
    @Test fun stripsPathTraversalAndUnsafeFilenameCharacters() {
        assertEquals("secret.txt", DownloadProtocol.safeFileName("../../secret.txt"))
        assertEquals("nova-download.bin", DownloadProtocol.safeFileName(".."))
        assertEquals("nova-download.bin", DownloadProtocol.safeFileName("   "))
        assertEquals("report_.pdf", DownloadProtocol.safeFileName("report?.pdf"))
    }
    @Test fun limitsFilenameLength() { assertEquals(120, DownloadProtocol.safeFileName("a".repeat(200)).length) }
    @Test fun parsesQuotedContentDispositionFilename() {
        assertEquals("report final.pdf", DownloadProtocol.fileNameFromContentDisposition("attachment; filename=\"report final.pdf\""))
    }
    @Test fun prefersRfc5987FilenameAndDecodesUtf8() {
        assertEquals("report final.pdf", DownloadProtocol.fileNameFromContentDisposition("attachment; filename=old.pdf; filename*=UTF-8''report%20final.pdf"))
    }
    @Test fun sanitizesContentDispositionPathTraversal() {
        assertEquals("secret.txt", DownloadProtocol.fileNameFromContentDisposition("attachment; filename*=UTF-8''..%2F..%2Fsecret.txt"))
    }
    @Test fun missingContentDispositionFilenameReturnsNull() {
        assertNull(DownloadProtocol.fileNameFromContentDisposition("attachment"))
    }
    @Test fun rejectsHttpsToHttpRedirectDowngrades() {
        assertEquals(false, DownloadProtocol.isRedirectAllowed("https://example.com/file", "http://example.com/file"))
    }
    @Test fun permitsSecureAndUpgradeRedirects() {
        assertEquals(true, DownloadProtocol.isRedirectAllowed("https://example.com/a", "https://cdn.example.com/a"))
        assertEquals(true, DownloadProtocol.isRedirectAllowed("http://example.com/a", "https://example.com/a"))
        assertEquals(true, DownloadProtocol.isRedirectAllowed("http://example.com/a", "http://example.com/b"))
    }
    @Test fun storesOnlyDownloadOriginNotPathOrCredentials() {
        assertEquals("https://example.com:8443", DownloadProtocol.hostLabel("https://user:pass@example.com:8443/private/file?token=secret"))
    }
    @Test fun rejectsNonHttpRedirectSchemes() {
        assertEquals(false, DownloadProtocol.isRedirectAllowed("https://example.com/a", "file:///tmp/a"))
        assertEquals(false, DownloadProtocol.isRedirectAllowed("ftp://example.com/a", "https://example.com/a"))
    }
}
