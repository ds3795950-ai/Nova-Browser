package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaPrintPolicyTest {
    @Test fun allowsHttpPages() {
        assertTrue(NovaPrintPolicy.canRequestPrint("https://example.com"))
        assertTrue(NovaPrintPolicy.canRequestPrint("http://example.com"))
    }

    @Test fun rejectsInternalSchemes() {
        assertFalse(NovaPrintPolicy.canRequestPrint("about:blank"))
        assertFalse(NovaPrintPolicy.canRequestPrint("data:text/html,hi"))
        assertFalse(NovaPrintPolicy.canRequestPrint(null))
    }

    @Test fun jobNamePrefersTitle() {
        assertEquals("Hello", NovaPrintPolicy.jobName("Hello", "https://x"))
        assertEquals("https://x", NovaPrintPolicy.jobName(" ", "https://x"))
    }
}
