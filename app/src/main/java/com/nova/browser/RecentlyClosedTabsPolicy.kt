package com.nova.browser

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class RecentlyClosedTab(
    val id: String,
    val title: String,
    val url: String,
    val closedAt: Long
)

internal object RecentlyClosedTabsPolicy {
    const val MAX_ENTRIES = 20

    fun add(
        existing: List<RecentlyClosedTab>,
        title: String,
        rawUrl: String,
        isPrivate: Boolean,
        isGuest: Boolean,
        closedAt: Long
    ): List<RecentlyClosedTab> {
        if (isPrivate || isGuest) return existing.take(MAX_ENTRIES)
        val safeUrl = HistoryPolicy.sanitizeForStorage(rawUrl) ?: return existing.take(MAX_ENTRIES)
        val entry = RecentlyClosedTab(
            id = UUID.randomUUID().toString(),
            title = title.take(200).ifBlank { "Closed tab" },
            url = safeUrl,
            closedAt = closedAt.coerceAtLeast(0L)
        )
        return (listOf(entry) + existing.filterNot { it.url == safeUrl }).take(MAX_ENTRIES)
    }

    fun remove(entries: List<RecentlyClosedTab>, id: String): List<RecentlyClosedTab> =
        entries.filterNot { it.id == id }.take(MAX_ENTRIES)

    fun encode(entries: List<RecentlyClosedTab>): String {
        val array = JSONArray()
        entries.take(MAX_ENTRIES).forEach { entry ->
            val safeUrl = HistoryPolicy.sanitizeForStorage(entry.url) ?: return@forEach
            array.put(JSONObject()
                .put("id", entry.id.take(80))
                .put("title", entry.title.take(200))
                .put("url", safeUrl)
                .put("closedAt", entry.closedAt.coerceAtLeast(0L)))
        }
        return array.toString()
    }

    fun decode(raw: String?): List<RecentlyClosedTab> = runCatching {
        val array = JSONArray(raw ?: "[]")
        val entries = mutableListOf<RecentlyClosedTab>()
        for (index in 0 until minOf(array.length(), MAX_ENTRIES)) {
            val item = array.optJSONObject(index) ?: continue
            val url = HistoryPolicy.sanitizeForStorage(item.optString("url")) ?: continue
            val id = item.optString("id").take(80).ifBlank { UUID.randomUUID().toString() }
            entries += RecentlyClosedTab(
                id = id,
                title = item.optString("title", "Closed tab").take(200).ifBlank { "Closed tab" },
                url = url,
                closedAt = item.optLong("closedAt").coerceAtLeast(0L)
            )
        }
        entries.distinctBy { it.url }.take(MAX_ENTRIES)
    }.getOrDefault(emptyList())
}
