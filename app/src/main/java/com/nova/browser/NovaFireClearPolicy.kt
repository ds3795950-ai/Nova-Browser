package com.nova.browser

/** Fire is refused before any mutation when protected session contexts are still open. */
internal object NovaFireClearPolicy {
    fun canRun(hasPrivateOrGuestTabs: Boolean): Boolean = !hasPrivateOrGuestTabs

    /** Do not reactivate tabs or pending external links until the complete Fire sequence ends. */
    fun canResumeAfterGeckoClear(geckoClearActive: Boolean, fireClearActive: Boolean): Boolean =
        !geckoClearActive && !fireClearActive
}
