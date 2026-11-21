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

    @Test fun keepsNonAsciiLettersAndExtension() {
        assertEquals("रिपोर्ट.pdf", DownloadProtocol.safeFileName("रिपोर्ट.pdf"))
        val long = DownloadProtocol.safeFileName("a".repeat(200) + ".apk")
        assertEquals(120, long.length)
        assertEquals(true, long.endsWith(".apk"))
    }

    @Test fun fileNameFromResponsePrefersContentDisposition() {
        assertEquals("report.pdf", DownloadProtocol.fileNameFromResponse("https://x.example/download?id=3", "attachment; filename=\"report.pdf\""))
        assertEquals("ফাইল.txt", DownloadProtocol.fileNameFromResponse("https://x.example/d", "attachment; filename*=UTF-8''%E0%A6%AB%E0%A6%BE%E0%A6%87%E0%A6%B2.txt"))
    }

    @Test fun fileNameFromUrlDecodesAndIgnoresQuery() {
        assertEquals("my file.zip", DownloadProtocol.fileNameFromResponse("https://x.example/a/my%20file.zip?next=/a/b#frag", null))
        assertEquals("c++.zip", DownloadProtocol.fileNameFromResponse("https://x.example/c++.zip", null))
        assertEquals("100%.zip", DownloadProtocol.fileNameFromResponse("https://x.example/100%.zip", null))
    }

    @Test fun limitsFilenameLength() {
        assertEquals(120, DownloadProtocol.safeFileName("a".repeat(200)).length)
    }
}
