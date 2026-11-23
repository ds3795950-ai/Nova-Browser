package com.nova.browser

import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSearchSuggestionsTest {
    @Test fun interruptedWorkerDoesNotStartRemoteRequest() {
        Thread.currentThread().interrupt()
        try {
            assertTrue(RemoteSearchSuggestions.fetch("private query", "Google").isEmpty())
        } finally {
            // Clear the interrupt flag so the test runner thread remains usable.
            Thread.interrupted()
        }
    }

    @Test fun unsupportedSearchEngineDoesNotIssueRemoteRequest() {
        assertTrue(RemoteSearchSuggestions.fetch("example query", "Unsupported").isEmpty())
    }
}
