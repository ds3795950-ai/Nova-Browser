package com.nova.browser

import java.net.URLEncoder

/** Pure URL/search routing so provider behavior can be unit tested on the JVM. */
object BrowserUrlResolver {
    fun resolve(input: String, engine: String = "Google"): String? {
        val value = input.trim()
        if (value.isEmpty()) return null
        if (value.startsWith("https://", true) || value.startsWith("http://", true) || value.startsWith("about:", true)) return value
        if (value.contains('.') && !value.contains(' ') && !value.startsWith("about:", true)) {
            return "https://$value"
        }
        val query = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        return when (engine) {
            "Bing" -> "https://www.bing.com/search?q=$query"
            "DuckDuckGo" -> "https://duckduckgo.com/?q=$query"
            "Brave Search" -> "https://search.brave.com/search?q=$query"
            else -> "https://www.google.com/search?q=$query"
        }
    }
}
