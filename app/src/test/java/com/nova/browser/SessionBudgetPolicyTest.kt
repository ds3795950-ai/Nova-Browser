package com.nova.browser

import org.junit.Assert.assertEquals
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
    @Test fun scalesRetainedSessionLimitWithMemoryClass() {
        assertEquals(3, SessionBudgetPolicy.maxRetainedSessions(96))
        assertEquals(6, SessionBudgetPolicy.maxRetainedSessions(256))
        assertEquals(10, SessionBudgetPolicy.maxRetainedSessions(512))
    }

    @Test fun countsSplitPaneAsVisibleAndNeverEvictsPrivateOrGuest() {
        val candidates = listOf(
            SessionBudgetPolicy.Candidate("current", 10, false, false),
            SessionBudgetPolicy.Candidate("split", 9, false, false),
            SessionBudgetPolicy.Candidate("private", 1, true, false),
            SessionBudgetPolicy.Candidate("guest", 2, false, true),
            SessionBudgetPolicy.Candidate("old-normal", 0, false, false),
            SessionBudgetPolicy.Candidate("new-normal", 3, false, false)
        )
        val evicted = SessionBudgetPolicy.evictionCandidates(candidates, 4, setOf("current", "split"))
        assertEquals(listOf("old-normal", "new-normal"), evicted)
        assertFalse(evicted.contains("private"))
        assertFalse(evicted.contains("guest"))
    }

    @Test fun handlesNoSplitSession() {
        assertTrue(SessionBudgetPolicy.shouldRemainActive("current", "current", null))
        assertFalse(SessionBudgetPolicy.shouldRemainActive("other", "current", null))
    }
}
