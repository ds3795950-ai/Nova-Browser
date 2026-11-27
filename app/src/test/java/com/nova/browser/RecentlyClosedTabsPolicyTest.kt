package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentlyClosedTabsPolicyTest {
    @Test fun privateAndGuestTabsAreNeverRecorded() {
        val empty = emptyList<RecentlyClosedTab>()
        assertTrue(RecentlyClosedTabsPolicy.add(empty, "Private", "https://example.com", true, false, 1).isEmpty())
        assertTrue(RecentlyClosedTabsPolicy.add(empty, "Guest", "https://example.com", false, true, 1).isEmpty())
    }

    @Test fun sensitiveAndNonWebUrlsAreNotRecorded() {
        val empty = emptyList<RecentlyClosedTab>()
        assertTrue(RecentlyClosedTabsPolicy.add(empty, "Token", "https://example.com/?access_token=secret", false, false, 1).isEmpty())
        assertTrue(RecentlyClosedTabsPolicy.add(empty, "Internal", "about:blank", false, false, 1).isEmpty())
    }

    @Test fun listIsBoundedAndDuplicateUrlsAreMovedToFront() {
        var entries = emptyList<RecentlyClosedTab>()
        repeat(RecentlyClosedTabsPolicy.MAX_ENTRIES + 5) { index ->
            entries = RecentlyClosedTabsPolicy.add(entries, "Page $index", "https://example.com/$index", false, false, index.toLong())
        }
        assertEquals(RecentlyClosedTabsPolicy.MAX_ENTRIES, entries.size)
        entries = RecentlyClosedTabsPolicy.add(entries, "Updated", "https://example.com/10", false, false, 999)
        assertEquals(RecentlyClosedTabsPolicy.MAX_ENTRIES, entries.size)
        assertEquals("https://example.com/10", entries.first().url)
        assertEquals("Updated", entries.first().title)
    }

    @Test fun encodedListRoundTripsAndCanRemoveRestoredEntry() {
        val entries = RecentlyClosedTabsPolicy.add(emptyList(), "Example", "https://example.com", false, false, 42)
        val decoded = RecentlyClosedTabsPolicy.decode(RecentlyClosedTabsPolicy.encode(entries))
        assertEquals(entries.first().id, decoded.first().id)
        assertEquals(entries.first().url, decoded.first().url)
        assertEquals(42L, decoded.first().closedAt)
        assertFalse(RecentlyClosedTabsPolicy.remove(decoded, decoded.first().id).isNotEmpty())
    }
}
