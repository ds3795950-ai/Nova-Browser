package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadProtocolTest {
    @Test fun parsesValidContentRange() {
        val range = DownloadProtocol.parseContentRange("bytes 1024-2047/4096")
        assertEquals(1024L, range?.start)
        assertEquals(2047L, range?.end)
        assertEquals(4096L, range?.total)
    }

    @Test fun parsesUnknownTotalContentRange() {
        val range = DownloadProtocol.parseContentRange("bytes 0-99/*")
        assertEquals(0L, range?.start)
        assertNull(range?.total)
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

    @Test fun limitsFilenameLength() {
        assertEquals(120, DownloadProtocol.safeFileName("a".repeat(200)).length)
    }

    @Test fun keepsNonAsciiLetters() {
        assertEquals("отчёт 2026.pdf", DownloadProtocol.safeFileName("отчёт 2026.pdf"))
    }

    @Test fun longNamesKeepTheirExtension() {
        val name = DownloadProtocol.safeFileName("a".repeat(200) + ".pdf")
        assertEquals(120, name.length)
        assertEquals(true, name.endsWith(".pdf"))
    }
}
