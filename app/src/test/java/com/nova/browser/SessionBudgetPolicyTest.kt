package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionBudgetPolicyTest {
    @Test
    fun normalSessionsAreEvictedBeforePrivateAndGuestSessions() {
        val candidates = listOf(
            SessionBudgetPolicy.Candidate("normal-old", 1L, false, false),
            SessionBudgetPolicy.Candidate("guest-old", 2L, false, true),
            SessionBudgetPolicy.Candidate("private-old", 3L, true, false),
            SessionBudgetPolicy.Candidate("normal-new", 4L, false, false)
        )
        assertEquals(listOf("normal-old", "normal-new"),
            SessionBudgetPolicy.evictionCandidates(candidates, 2, emptySet()))
    }

    @Test
    fun privateAndGuestSessionsAreEvictedOnlyWhenNeededToMeetTheCap() {
        val candidates = listOf(
            SessionBudgetPolicy.Candidate("normal", 1L, false, false),
            SessionBudgetPolicy.Candidate("guest", 2L, false, true),
            SessionBudgetPolicy.Candidate("private", 3L, true, false)
        )
        assertEquals(listOf("normal", "guest"),
            SessionBudgetPolicy.evictionCandidates(candidates, 1, emptySet()))
    }

    @Test
    fun visibleCurrentAndSplitSessionsAreNeverEvicted() {
        val candidates = listOf(
            SessionBudgetPolicy.Candidate("current", 1L, false, false),
            SessionBudgetPolicy.Candidate("split", 2L, false, false),
            SessionBudgetPolicy.Candidate("background", 3L, false, true)
        )
        val evicted = SessionBudgetPolicy.evictionCandidates(candidates, 2, setOf("current", "split"))
        assertEquals(listOf("background"), evicted)
        assertFalse(evicted.contains("current"))
        assertFalse(evicted.contains("split"))
    }

    @Test
    fun sessionBudgetScalesWithMemoryClass() {
        assertTrue(SessionBudgetPolicy.maxRetainedSessions(96) < SessionBudgetPolicy.maxRetainedSessions(256))
    }
}
