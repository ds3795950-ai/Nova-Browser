package com.nova.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPolicyTest {
    @Test fun storesOnlyHttpAndHttpsPages() {
        assertTrue(HistoryPolicy.shouldPersist("https://example.com/path"))
        assertTrue(HistoryPolicy.shouldPersist("http://localhost:8080/"))
        assertTrue(HistoryPolicy.shouldPersist("https://example.com:8443/a?b=1"))
    }
    @Test fun excludesInternalAndNonWebSchemes() {
        listOf("", "about:blank", "about:reader?url=https://example.com", "file:///storage/a.txt",
            "data:text/plain,secret", "javascript:alert(1)", "blob:https://example.com/id", "ftp://example.com/file").forEach {
            assertFalse("Should not persist $it", HistoryPolicy.shouldPersist(it))
        }
    }
    @Test fun rejectsMalformedAndCredentialBearingUrls() {
        assertFalse(HistoryPolicy.shouldPersist("https://"))
        assertFalse(HistoryPolicy.shouldPersist("https://user:password@example.com/"))
        assertFalse(HistoryPolicy.shouldPersist("not a url"))
    }
}
