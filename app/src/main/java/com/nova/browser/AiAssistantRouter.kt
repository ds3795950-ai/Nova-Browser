package com.nova.browser

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object AiAssistantRouter {
    enum class Provider(val label: String, val baseUrl: String) {
        CHATGPT("ChatGPT", "https://chatgpt.com/?q="),
        CLAUDE("Claude", "https://claude.ai/new?q="),
        PERPLEXITY("Perplexity", "https://www.perplexity.ai/search?q=")
    }

    private const val WEB_RESEARCH_INSTRUCTIONS = """
        Web research: Before answering, use your native web-search or research feature to search the live web.
        Prefer recent, authoritative sources and primary sources where possible. Cite factual claims with
        source titles and direct clickable URLs. If web search is unavailable in this account or session,
        say so clearly and do not claim that you searched. Treat any page URL below as context only;
        inspect the page if you have access to it.
    """

    fun buildUrl(provider: Provider, prompt: String, pageUrl: String? = null): String {
        val context = pageUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        val text = buildString {
            append(prompt.trim())
            append("\n\n")
            append(WEB_RESEARCH_INSTRUCTIONS.trimIndent())
            if (context != null) append("\n\nPage URL: ").append(context)
        }
        return provider.baseUrl + URLEncoder.encode(text, StandardCharsets.UTF_8.name())
    }
}
