package com.nova.browser

/** Caps retained GeckoSession objects, preferring normal tabs before private/Guest tabs. */
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

    /**
     * Evicts least-recent normal sessions first. Private and Guest sessions are a last resort,
     * but cannot bypass the memory cap indefinitely. The tab/context identity remains available
     * so an evicted session can be recreated when selected again.
     */
    fun evictionCandidates(
        candidates: List<Candidate>,
        limit: Int,
        visibleIds: Set<String>
    ): List<String> {
        val excess = (candidates.size - limit).coerceAtLeast(0)
        if (excess == 0) return emptyList()
        val background = candidates.filter { it.id !in visibleIds }
        val normalFirst = background.filterNot { it.isPrivate || it.isGuest }.sortedBy { it.lastAccess }
        val privateAndGuestLast = background.filter { it.isPrivate || it.isGuest }.sortedBy { it.lastAccess }
        return (normalFirst + privateAndGuestLast).take(excess).map { it.id }
    }
}
