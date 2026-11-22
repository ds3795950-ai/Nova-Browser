package com.nova.browser

/**
 * Selects which tab engine sessions should remain live under Nova's memory budget.
 * The active tab is always prioritized; a visible split-screen tab is retained
 * in addition to the normal live-tab budget.
 */
internal object TabSessionRetentionPolicy {
    fun retainedTabIds(
        tabLastAccess: List<Pair<String, Long>>,
        activeTabId: String?,
        splitTabId: String?,
        maxLiveTabs: Int = 4
    ): Set<String> {
        require(maxLiveTabs > 0) { "maxLiveTabs must be positive" }

        val knownIds = tabLastAccess.mapTo(mutableSetOf()) { it.first }
        val retained = tabLastAccess
            .sortedByDescending { (id, lastAccess) ->
                if (id == activeTabId) Long.MAX_VALUE else lastAccess
            }
            .take(maxLiveTabs)
            .mapTo(linkedSetOf()) { it.first }

        splitTabId?.takeIf { it in knownIds }?.let(retained::add)
        activeTabId?.takeIf { it in knownIds }?.let(retained::add)
        return retained
    }
}
