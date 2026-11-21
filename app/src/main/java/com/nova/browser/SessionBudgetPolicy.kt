package com.nova.browser

/** Keeps visible GeckoSessions active while retaining private and Guest state. */
internal object SessionBudgetPolicy {
    data class Candidate(
        val id: String,
        val lastAccess: Long,
        val isPrivate: Boolean,
        val isGuest: Boolean
    )

    fun shouldRemainActive(tabId: String, currentTabId: String?, splitTabId: String?): Boolean =
        tabId == currentTabId || (splitTabId != null && tabId == splitTabId)

    fun maxRetainedSessions(memoryClassMb: Int): Int = when {
        memoryClassMb < 128 -> 3
        memoryClassMb < 192 -> 4
        memoryClassMb < 256 -> 5
        memoryClassMb < 384 -> 6
        memoryClassMb < 512 -> 8
        else -> 10
    }

    /** Selects only least-recent normal tabs; private and Guest sessions are never evicted. */
    fun evictionCandidates(
        candidates: List<Candidate>,
        limit: Int,
        visibleIds: Set<String>
    ): List<String> {
        val excess = (candidates.size - limit).coerceAtLeast(0)
        if (excess == 0) return emptyList()
        return candidates.asSequence()
            .filter { it.id !in visibleIds && !it.isPrivate && !it.isGuest }
            .sortedBy { it.lastAccess }
            .take(excess)
            .map { it.id }
            .toList()
    }
}
