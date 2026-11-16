package com.nova.browser

import org.junit.Assert.assertTrue
import org.junit.Test

class AiAssistantRouterTest {
    @Test fun routesEachProviderToItsOfficialWebApp() {
        assertTrue(AiAssistantRouter.buildUrl(AiAssistantRouter.Provider.CHATGPT, "hello").startsWith("https://chatgpt.com/?q="))
        assertTrue(AiAssistantRouter.buildUrl(AiAssistantRouter.Provider.CLAUDE, "hello").startsWith("https://claude.ai/new?q="))
        assertTrue(AiAssistantRouter.buildUrl(AiAssistantRouter.Provider.PERPLEXITY, "hello").startsWith("https://www.perplexity.ai/search?q="))
    }

    @Test fun encodesPromptAndOnlyAddsValidHttpPageContext() {
        val url = AiAssistantRouter.buildUrl(AiAssistantRouter.Provider.CHATGPT, "summarize this", "https://example.com/article?a=1&b=2")
        assertTrue(url.contains("summarize+this"))
        assertTrue(url.contains("Page+URL%3A+https%3A%2F%2Fexample.com%2Farticle"))
        val noContext = AiAssistantRouter.buildUrl(AiAssistantRouter.Provider.CLAUDE, "hello", "javascript:alert(1)")
        assertTrue(!noContext.contains("javascript"))
    }

    @Test fun shieldModeDefaultsSafely() {
        assertTrue(NovaShield.fromStored(null) == ShieldMode.STANDARD)
        assertTrue(NovaShield.fromStored("AGGRESSIVE") == ShieldMode.AGGRESSIVE)
        assertTrue(NovaShield.fromStored("unknown") == ShieldMode.STANDARD)
    }
}
