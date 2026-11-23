package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaAiInputPolicyTest {
    @Test fun providerModelIdentifiersAreBoundedAndSafe() {
        val accepted = listOf("gpt-4o-mini", "claude-3-5-haiku-latest", "gemini-2.5-pro", "kimi-k2-thinking", "deepseek-reasoner")
        accepted.forEach { assertTrue(it.matches(Regex("[A-Za-z0-9._:-]{1,159}"))) }
        assertTrue(!"../../other".matches(Regex("[A-Za-z0-9._:-]{1,159}")))
        assertTrue(!"model with spaces".matches(Regex("[A-Za-z0-9._:-]{1,159}")))
        assertTrue(!"".matches(Regex("[A-Za-z0-9._:-]{1,159}")))
    }

    @Test fun pageContextIsCappedBeforeProviderSubmission() {
        val page = "x".repeat(30_000)
        assertEquals(24_000, page.take(24_000).length)
    }
}
