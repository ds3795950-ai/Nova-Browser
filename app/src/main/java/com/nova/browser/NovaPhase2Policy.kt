package com.nova.browser

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class NovaTabSearchItem(val id: String, val title: String, val url: String, val group: String)

internal enum class NovaDiscoverTopic(val label: String, val query: String) {
    INDIA("India", "India latest news"),
    WORLD("World", "world latest news"),
    FOOTBALL("Football", "football latest news"),
    SPORTS("Sports", "sports latest news")
}

internal object NovaPhase2Policy {
    fun safeHttpUrl(raw: String): String? = runCatching {
        val uri = URI(raw.trim())
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) null
        else uri.toASCIIString().takeIf { it.length <= 4096 }
    }.getOrNull()

    fun discoverFeedUrl(topic: NovaDiscoverTopic): String =
        "https://news.google.com/rss/search?q=" +
            URLEncoder.encode(topic.query, StandardCharsets.UTF_8.name()) + "&hl=en-IN&gl=IN&ceid=IN:en"

    fun filterTabs(query: String, tabs: List<NovaTabSearchItem>, limit: Int = 100): List<NovaTabSearchItem> {
        val needle = query.trim()
        if (needle.isEmpty() || limit <= 0) return emptyList()
        return tabs.asSequence().filter { item ->
            item.title.contains(needle, true) || item.url.contains(needle, true) || item.group.contains(needle, true)
        }.distinctBy { it.id }.take(limit).toList()
    }

    fun imagePermissionDefault(dataSaverEnabled: Boolean, userChoice: Int): Int =
        if (dataSaverEnabled) 2 else if (userChoice == 2) 2 else 1

    fun canSaveReadingLink(url: String, isPrivate: Boolean, isGuest: Boolean): Boolean =
        !isPrivate && !isGuest && safeHttpUrl(url) != null
}
