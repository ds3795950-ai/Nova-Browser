package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class TabSessionRetentionPolicyTest {
    @Test fun duplicateTabIdsAreDeduplicatedBeforeApplyingTheLiveTabBudget() {
        val retained = TabSessionRetentionPolicy.retainedTabIds(
            tabLastAccess = listOf("active" to 1L, "background" to 3L, "background" to 9L, "older" to 2L),
            activeTabId = "active",
            splitTabId = null,
            maxLiveTabs = 2
        )
        assertEquals(setOf("active", "background"), retained)
    }

    @Test fun activeAndSplitTabsAreAlwaysRetained() {
        val retained = TabSessionRetentionPolicy.retainedTabIds(
            tabLastAccess = listOf("active" to 1L, "split" to 2L, "recent" to 100L),
            activeTabId = "active",
            splitTabId = "split",
            maxLiveTabs = 1
        )
        assertEquals(setOf("active", "split"), retained)
    }

    @Test fun mostRecentlyUsedBackgroundTabsWinRemainingSlots() {
        val retained = TabSessionRetentionPolicy.retainedTabIds(
            tabLastAccess = listOf("active" to 1L, "old" to 2L, "newer" to 9L, "middle" to 5L),
            activeTabId = "active",
            splitTabId = null,
            maxLiveTabs = 3
        )
        assertEquals(setOf("active", "newer", "middle"), retained)
    }
}
