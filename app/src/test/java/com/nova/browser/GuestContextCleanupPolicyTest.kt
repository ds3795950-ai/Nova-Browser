package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestContextCleanupPolicyTest {
    @Test fun queueDeduplicatesAndIgnoresBlankContextIds() {
        val pending = GuestContextCleanupPolicy.queue(setOf("old-a"), "old-a")
        assertEquals(setOf("old-a"), pending)
        assertEquals(pending, GuestContextCleanupPolicy.queue(pending, "  "))
    }

    @Test fun allUnconfirmedContextIdsRemainQueuedForRetry() {
        val pending = GuestContextCleanupPolicy.queue(
            GuestContextCleanupPolicy.queue(emptySet(), "old-a"), "old-b"
        )
        // The Gecko API provides no completion callback; callers must not drop IDs on request.
        assertTrue("old-a" in pending)
        assertTrue("old-b" in pending)
    }
}
