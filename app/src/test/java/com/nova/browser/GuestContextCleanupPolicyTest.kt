package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestContextCleanupPolicyTest {
    @Test fun queueDeduplicatesAndIgnoresBlankContextIds() {
        val pending = GuestContextCleanupPolicy.queue(setOf("old-a"), "old-a")
        assertEquals(setOf("old-a"), pending)
        assertEquals(pending, GuestContextCleanupPolicy.queue(pending, "  "))
    }

    @Test fun completionRemovesOnlyTheConfirmedContext() {
        val pending = setOf("old-a", "old-b")
        assertEquals(setOf("old-b"), GuestContextCleanupPolicy.complete(pending, "old-a"))
    }

    @Test fun failedCleanupRemainsQueuedUntilSuccessIsConfirmed() {
        val pending = GuestContextCleanupPolicy.queue(emptySet(), "guest-context")
        assertTrue("guest-context" in pending)
        // Failure path deliberately does not call complete().
        assertTrue("guest-context" in pending)
        assertFalse("guest-context" in GuestContextCleanupPolicy.complete(pending, "guest-context"))
    }
}
