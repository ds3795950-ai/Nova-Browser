package com.nova.browser.engine

import org.junit.Assert.*
import org.junit.Test

class BrowserRendererRecoveryPolicyTest {
    @Test fun allowsOneAutomaticRecoveryAfterRendererTermination() {
        assertTrue(BrowserRendererRecoveryPolicy.shouldAutomaticallyRecover(0))
        assertEquals(1, BrowserRendererRecoveryPolicy.nextAttempt(0))
        assertFalse(BrowserRendererRecoveryPolicy.shouldAutomaticallyRecover(1))
    }

    @Test fun successfulPaintResetsTheRecoveryBudget() {
        assertEquals(0, BrowserRendererRecoveryPolicy.resetAfterSuccessfulPaint())
        assertTrue(BrowserRendererRecoveryPolicy.shouldAutomaticallyRecover(
            BrowserRendererRecoveryPolicy.resetAfterSuccessfulPaint()
        ))
    }
}
