package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNotificationPolicyTest {
    @Test fun knownSizeShowsPercentAndDeterminateProgress() {
        val display = DownloadNotificationPolicy.display(1, "archive.zip", 50, 100)
        assertEquals("Downloading archive.zip", display.title)
        assertEquals(50, display.progress)
        assertTrue(!display.indeterminate)
        assertTrue(display.text.contains("50%"))
    }
    @Test fun unknownSizeUsesIndeterminateProgress() {
        val display = DownloadNotificationPolicy.display(2, "archive.zip", 1024, 0)
        assertTrue(display.indeterminate)
        assertEquals(0, display.progress)
        assertTrue(display.text.contains("+1 more"))
    }
    @Test fun progressIsClampedToTotal() {
        assertEquals(100, DownloadNotificationPolicy.display(1, "file", 120, 100).progress)
    }
    @Test fun noActiveDownloadsDoesNotShowProgress() {
        val display = DownloadNotificationPolicy.display(0, null, 0, 0)
        assertEquals(0, display.progress)
        assertTrue(!display.indeterminate)
    }
}
