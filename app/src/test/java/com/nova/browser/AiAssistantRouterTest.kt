package com.nova.browser

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class AiAssistantRouterTest {
    @Test fun routesEachProviderToItsOfficialWebApp() {
        assertTrue(AiAssistantRouter.buildUrl(AiAssistantRouter.Provider.CHATGPT, "hello").startsWith("https://chatgpt.com/?q="))
        assertTrue(AiAssistantRouter.buildUrl(AiAssistantRouter.Provider.CLAUDE, "hello").startsWith("https://claude.ai/new?q="))
        assertTrue(AiAssistantRouter.buildUrl(AiAssistantRouter.Provider.PERPLEXITY, "hello").startsWith("https://www.perplexity.ai/search?q="))
    }

    @Test fun asksEveryProviderToSearchTheLiveWebAndCiteSources() {
        AiAssistantRouter.Provider.values().forEach { provider ->
            val encoded = AiAssistantRouter.buildUrl(provider, "latest Android security update").substringAfter("?q=")
            val prompt = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
            assertTrue(prompt.contains("search the live web"))
            assertTrue(prompt.contains("source titles and direct clickable URLs"))
            assertTrue(prompt.contains("do not claim that you searched"))
            assertTrue(prompt.startsWith("latest Android security update"))
        }
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
