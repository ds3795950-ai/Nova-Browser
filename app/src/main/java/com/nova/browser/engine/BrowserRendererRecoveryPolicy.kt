package com.nova.browser.engine

/** Prevents a repeatedly crashing renderer from entering an automatic reload loop. */
object BrowserRendererRecoveryPolicy {
    const val MAX_AUTOMATIC_RECOVERIES = 1

    fun shouldAutomaticallyRecover(attemptsSinceLastSuccessfulPaint: Int): Boolean =
        attemptsSinceLastSuccessfulPaint < MAX_AUTOMATIC_RECOVERIES

    fun nextAttempt(attemptsSinceLastSuccessfulPaint: Int): Int =
        (attemptsSinceLastSuccessfulPaint + 1).coerceAtMost(MAX_AUTOMATIC_RECOVERIES)

    fun resetAfterSuccessfulPaint(): Int = 0
}
