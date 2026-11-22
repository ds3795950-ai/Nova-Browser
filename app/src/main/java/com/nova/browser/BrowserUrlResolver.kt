package com.nova.browser

import java.net.URLEncoder

/** Pure URL/search routing so provider behavior can be unit tested on the JVM. */
object BrowserUrlResolver {
    private val hostLike = Regex(
        "^(localhost|(\\d{1,3}\\.){3}\\d{1,3}|([\\p{L}\\p{N}_]([\\p{L}\\p{N}_-]*[\\p{L}\\p{N}_])?\\.)+(\\p{L}{2,}|xn--[a-z0-9-]+)\\.?)" +
            "(:\\d{1,5})?([/?#]\\S*)?$",
        RegexOption.IGNORE_CASE
    )
    private val ipv4 = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")

    fun resolve(input: String, engine: String = "Google"): String? {
        val value = input.trim()
        if (value.isEmpty()) return null
        if (value.startsWith("https://", true) || value.startsWith("http://", true) ||
            value.startsWith("about:", true) || value.startsWith("file://", true) ||
            value.startsWith("view-source:", true)
        ) return value
        if (hostLike.matches(value)) {
            val host = value.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
            val local = host.equals("localhost", true) || ipv4.matches(host) || host.endsWith(".local", true)
            return (if (local) "http://" else "https://") + value
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
