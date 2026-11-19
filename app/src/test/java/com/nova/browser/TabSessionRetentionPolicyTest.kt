package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TabSessionRetentionPolicyTest {
    @Test
    fun activeTabIsAlwaysRetainedAlongsideThreeMostRecentlyUsedBackgroundTabs() {
        val retained = TabSessionRetentionPolicy.retainedTabIds(
            tabLastAccess = listOf("old" to 1L, "active" to 2L, "recent-a" to 8L, "recent-b" to 7L, "recent-c" to 6L, "recent-d" to 5L),
            activeTabId = "active",
            splitTabId = null
        )

        assertEquals(setOf("active", "recent-a", "recent-b", "recent-c"), retained)
    }

    @Test
    fun visibleSplitTabIsRetainedEvenWhenOutsideTheFourTabBudget() {
        val retained = TabSessionRetentionPolicy.retainedTabIds(
            tabLastAccess = listOf("active" to 10L, "recent-a" to 9L, "recent-b" to 8L, "recent-c" to 7L, "split" to 1L),
            activeTabId = "active",
            splitTabId = "split"
        )

        assertTrue("split" in retained)
        assertEquals(5, retained.size)
    }

    @Test
    fun missingSplitTabDoesNotAddAnUnknownId() {
        val retained = TabSessionRetentionPolicy.retainedTabIds(
            tabLastAccess = listOf("active" to 1L, "background" to 0L),
            activeTabId = "active",
            splitTabId = "closed-tab"
        )

        assertEquals(setOf("active", "background"), retained)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveLiveTabBudget() {
        TabSessionRetentionPolicy.retainedTabIds(
            tabLastAccess = listOf("active" to 1L),
            activeTabId = "active",
            splitTabId = null,
            maxLiveTabs = 0
        )
    }
}
