package com.nova.browser

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object AiAssistantRouter {
    enum class Provider(val label: String, val baseUrl: String) {
        CHATGPT("ChatGPT", "https://chatgpt.com/?q="),
        CLAUDE("Claude", "https://claude.ai/new?q="),
        PERPLEXITY("Perplexity", "https://www.perplexity.ai/search?q=")
    }

    fun buildUrl(provider: Provider, prompt: String, pageUrl: String? = null): String {
        val context = pageUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        val text = buildString {
            append(prompt.trim())
            if (context != null) append("\n\nPage URL: ").append(context)
        }
        return provider.baseUrl + URLEncoder.encode(text, StandardCharsets.UTF_8.name())
    }
}
