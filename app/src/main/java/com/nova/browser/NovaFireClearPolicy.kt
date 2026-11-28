package com.nova.browser

/** Fire is refused before any mutation when protected session contexts are still open. */
internal object NovaFireClearPolicy {
    fun canRun(hasPrivateOrGuestTabs: Boolean): Boolean = !hasPrivateOrGuestTabs
}
