package com.nova.browser

import org.junit.Assert.*
import org.junit.Test

class NovaPhase2PolicyTest {
    @Test fun rejectsNonWebAndCredentialBearingUrls() {
        assertNull(NovaPhase2Policy.safeHttpUrl("javascript:alert(1)"))
        assertNull(NovaPhase2Policy.safeHttpUrl("file:///etc/passwd"))
        assertNull(NovaPhase2Policy.safeHttpUrl("https://user:pass@example.com"))
        assertEquals("https://example.com/path", NovaPhase2Policy.safeHttpUrl("https://example.com/path"))
    }

    @Test fun tabSearchMatchesTitleUrlAndGroupWithoutDuplicates() {
        val tabs = listOf(
            NovaTabSearchItem("1", "Docs", "https://example.com", "Work"),
            NovaTabSearchItem("2", "News", "https://news.example", "Read"),
            NovaTabSearchItem("1", "Docs", "https://example.com", "Work")
        )
        assertEquals(listOf("1", "2"), NovaPhase2Policy.filterTabs("example", tabs).map { it.id })
        assertEquals(listOf("1"), NovaPhase2Policy.filterTabs("work", tabs).map { it.id })
        assertTrue(NovaPhase2Policy.filterTabs(" ", tabs).isEmpty())
    }

    @Test fun dataSaverOnlyOverridesImageDefaultWhenEnabled() {
        assertEquals(2, NovaPhase2Policy.imagePermissionDefault(true, 1))
        assertEquals(1, NovaPhase2Policy.imagePermissionDefault(false, 1))
        assertEquals(2, NovaPhase2Policy.imagePermissionDefault(false, 2))
    }

    @Test fun discoverFeedUrlsAreHttpsAndTopicSpecific() {
        NovaDiscoverTopic.entries.forEach { topic ->
            assertTrue(NovaPhase2Policy.discoverFeedUrl(topic).startsWith("https://news.google.com/rss/search?"))
        }
        assertNotEquals(NovaPhase2Policy.discoverFeedUrl(NovaDiscoverTopic.INDIA), NovaPhase2Policy.discoverFeedUrl(NovaDiscoverTopic.FOOTBALL))
    }

    @Test fun readingListRejectsPrivateAndGuestData() {
        assertTrue(NovaPhase2Policy.canSaveReadingLink("https://example.com", false, false))
        assertFalse(NovaPhase2Policy.canSaveReadingLink("https://example.com", true, false))
        assertFalse(NovaPhase2Policy.canSaveReadingLink("https://example.com", false, true))
        assertFalse(NovaPhase2Policy.canSaveReadingLink("javascript:alert(1)", false, false))
    }
}
