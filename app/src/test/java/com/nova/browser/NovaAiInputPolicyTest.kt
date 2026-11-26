package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaAiInputPolicyTest {
    @Test fun providerModelIdentifiersAreBoundedAndSafe() {
        val accepted = listOf("gpt-4o-mini", "claude-3-5-haiku-latest", "gemini-2.5-pro", "kimi-k2-thinking", "deepseek-reasoner")
        accepted.forEach { assertTrue(NovaAiInputPolicy.isSafeModelIdentifier(it)) }
        assertFalse(NovaAiInputPolicy.isSafeModelIdentifier("../../other"))
        assertFalse(NovaAiInputPolicy.isSafeModelIdentifier("model with spaces"))
        assertFalse(NovaAiInputPolicy.isSafeModelIdentifier(""))
    }

    @Test fun pageContextIsCappedBeforeProviderSubmission() {
        val page = "x".repeat(30_000)
        assertEquals(24_000, NovaAiInputPolicy.limitPageContext(page).length)
    }

    @Test fun requestHistoryDropsOldTurnsUsingUtf8ByteBudget() {
        val history = listOf(
            NovaAiMessage("user", "界".repeat(70_000)),
            NovaAiMessage("assistant", "界".repeat(70_000)),
            NovaAiMessage("user", "latest prompt")
        )
        val bounded = NovaAiInputPolicy.boundedHistory(history, maxRequestBytes = 300_000, reserveBytes = 16_000)
        assertTrue(NovaAiInputPolicy.serializedHistoryBytes(bounded) <= 284_000)
        assertEquals("latest prompt", bounded.last().text)
    }

    @Test fun countLimitNeverLeavesAnAssistantAnswerWithoutItsUserTurn() {
        val history = buildList {
            repeat(10) { index ->
                add(NovaAiMessage("user", "question $index"))
                add(NovaAiMessage("assistant", "answer $index"))
            }
            add(NovaAiMessage("user", "question 10"))
        }
        val bounded = NovaAiInputPolicy.boundedHistory(history)
        assertEquals("user", bounded.first().role)
        assertEquals("question 1", bounded.first().text)
        assertEquals("user", bounded.last().role)
        assertEquals("question 10", bounded.last().text)
        assertEquals(19, bounded.size)
    }
}
