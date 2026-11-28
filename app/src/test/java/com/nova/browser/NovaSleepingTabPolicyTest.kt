package com.nova.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaSleepingTabPolicyTest {
    @Test fun hintsWhenSleepingOrBudgetRestored() {
        assertTrue(NovaSleepingTabPolicy.shouldShowRestoreHint(true, false))
        assertTrue(NovaSleepingTabPolicy.shouldShowRestoreHint(false, true))
        assertFalse(NovaSleepingTabPolicy.shouldShowRestoreHint(false, false))
    }
}
