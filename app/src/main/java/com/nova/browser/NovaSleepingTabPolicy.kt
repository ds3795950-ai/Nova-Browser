package com.nova.browser

/** Honest messaging for session-budget sleeping tabs. */
internal object NovaSleepingTabPolicy {
    fun restoreMessage(): String =
        "Tab was sleeping to save memory. The page was reloaded; form fields and exact scroll may not be restored."

    fun shouldShowRestoreHint(wasSleeping: Boolean, restoredFromBudget: Boolean): Boolean =
        wasSleeping || restoredFromBudget
}
