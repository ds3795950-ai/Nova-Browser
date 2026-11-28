package com.nova.browser

/** Caps retained browser-engine sessions, preferring normal tabs before private/Guest tabs. */
internal object SessionBudgetPolicy {
    data class Candidate(
        val id: String,
        val lastAccess: Long,
        val isPrivate: Boolean,
        val isGuest: Boolean
    )

    fun maxRetainedSessions(memoryClassMb: Int, memoryPressure: Boolean = false): Int {
        val normalLimit = when {
            memoryClassMb < 128 -> 2
            memoryClassMb < 192 -> 3
            memoryClassMb < 256 -> 4
            memoryClassMb < 384 -> 5
            memoryClassMb < 512 -> 6
            else -> 8
        }
        return if (memoryPressure) (normalLimit - 2).coerceAtLeast(2) else normalLimit
    }

    /** Evicts least-recent normal sessions first; visible current and split tabs are protected. */
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
