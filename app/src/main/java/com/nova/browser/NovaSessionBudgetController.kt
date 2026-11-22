package com.nova.browser

/** Owns GeckoSession retention and memory-pressure eviction, independent of Activity UI wiring. */
internal class NovaSessionBudgetController(
    private val tabs: MutableList<BrowserTab>,
    private val currentTab: () -> BrowserTab?,
    private val splitTabId: () -> String?,
    private val memoryClassMb: () -> Int,
    private val updateStatus: (String) -> Unit
) {
    fun enforce() {
        val currentId = currentTab()?.id
        val secondaryId = splitTabId()
        val visibleIds = setOfNotNull(currentId, secondaryId)
        val limit = SessionBudgetPolicy.maxRetainedSessions(memoryClassMb())
        val candidates = tabs.filter { it.session != null }.map {
            SessionBudgetPolicy.Candidate(it.id, it.lastAccess, it.isPrivate, it.isGuest)
        }
        SessionBudgetPolicy.evictionCandidates(candidates, limit, visibleIds).forEach { id ->
            tabs.firstOrNull { it.id == id }?.let { tab ->
                tab.session?.let { session ->
                    session.setActive(false)
                    session.setFocused(false)
                    session.close()
                }
                tab.session = null
                tab.needsReload = true
            }
        }
        tabs.forEach { tab ->
            val visible = SessionBudgetPolicy.shouldRemainActive(tab.id, currentId, secondaryId)
            tab.session?.let { session ->
                session.setActive(visible)
                session.setFocused(visible && tab.id == currentId)
            }
        }
        val activeTab = currentTab()
        updateStatus(when {
            activeTab?.isGuest == true -> "Guest Mode · temporary data"
            activeTab?.isPrivate == true -> "Private session"
            else -> "Memory saver · $limit retained normal sessions; background pages reload when reopened"
        })
    }

    fun evictBackgroundNormalSessions() {
        val visibleIds = setOfNotNull(currentTab()?.id, splitTabId())
        tabs.filter { it.id !in visibleIds && !it.isPrivate && !it.isGuest }.forEach { tab ->
            tab.session?.let { session ->
                session.setActive(false)
                session.setFocused(false)
                session.close()
            }
            if (tab.session != null) tab.needsReload = true
            tab.session = null
        }
        // Enforce the hard cap for inactive private/Guest sessions if normal-page eviction is not enough.
        enforce()
    }
}
