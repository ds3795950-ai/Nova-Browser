package com.nova.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionBudgetPolicyTest {
    @Test fun keepsCurrentAndSplitSessionsActive() {
        assertTrue(SessionBudgetPolicy.shouldRemainActive("current", "current", "split"))
        assertTrue(SessionBudgetPolicy.shouldRemainActive("split", "current", "split"))
    }
    @Test fun suspendsBackgroundSessionsWithoutEvictingThem() {
        assertFalse(SessionBudgetPolicy.shouldRemainActive("background", "current", "split"))
    }
    @Test fun handlesNoSplitSession() {
        assertTrue(SessionBudgetPolicy.shouldRemainActive("current", "current", null))
        assertFalse(SessionBudgetPolicy.shouldRemainActive("other", "current", null))
    }
}
