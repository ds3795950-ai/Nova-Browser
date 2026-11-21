package com.nova.browser

/** Keeps visible GeckoSessions active while retaining background session instances and page state. */
internal object SessionBudgetPolicy {
    fun shouldRemainActive(tabId: String, currentTabId: String?, splitTabId: String?): Boolean =
        tabId == currentTabId || (splitTabId != null && tabId == splitTabId)
}
