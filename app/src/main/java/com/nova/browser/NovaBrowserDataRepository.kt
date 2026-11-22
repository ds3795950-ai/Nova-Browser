package com.nova.browser

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class BookmarkEntry(
    val url: String,
    val title: String,
    val folder: String,
    val addedAt: Long
)

internal data class HistoryEntry(
    val url: String,
    val title: String,
    val visitedAt: Long,
    val id: String = UUID.randomUUID().toString()
)

internal object NovaBrowserDataCodec {
    const val MAX_BOOKMARK_COUNT = 10_000
    const val MAX_URL_LENGTH = 2_048
    const val MAX_TITLE_LENGTH = 512
    const val MAX_FOLDER_LENGTH = 128

    fun decodeBookmarks(raw: String?): List<BookmarkEntry> {
        if (raw == null) return emptyList()
        val array = JSONArray(raw)
        return buildList {
            for (index in 0 until array.length()) {
                val item = runCatching { array.getJSONObject(index) }.getOrNull() ?: continue
                val url = HistoryPolicy.sanitizeForStorage(item.optString("url")) ?: continue
                if (url.length <= MAX_URL_LENGTH) {
                    add(BookmarkEntry(
                        url,
                        item.optString("title").take(MAX_TITLE_LENGTH),
                        item.optString("folder", "General").take(MAX_FOLDER_LENGTH),
                        item.optLong("addedAt", 0L)
                    ))
                }
            }
        }
    }

    fun sanitizeBookmarks(items: List<BookmarkEntry>): List<BookmarkEntry> =
        items.mapNotNull { item ->
            val url = HistoryPolicy.sanitizeForStorage(item.url) ?: return@mapNotNull null
            if (url.length > MAX_URL_LENGTH) return@mapNotNull null
            item.copy(
                url = url,
                title = item.title.take(MAX_TITLE_LENGTH),
                folder = item.folder.take(MAX_FOLDER_LENGTH)
            )
        }.take(MAX_BOOKMARK_COUNT)

    fun encodeBookmarks(items: List<BookmarkEntry>): String {
        val array = JSONArray()
        sanitizeBookmarks(items).forEach { item ->
            array.put(JSONObject()
                .put("url", item.url)
                .put("title", item.title)
                .put("folder", item.folder)
                .put("addedAt", item.addedAt))
        }
        return array.toString()
    }

    fun decodeHistory(raw: String?): List<HistoryEntry> {
        if (raw == null) return emptyList()
        val array = JSONArray(raw)
        return buildList {
            for (index in 0 until array.length()) {
                val entry = runCatching {
                    val item = array.getJSONObject(index)
                    val safeUrl = HistoryPolicy.sanitizeForStorage(item.optString("url"))
                        ?: return@runCatching null
                    val visitedAt = item.optLong("visitedAt")
                    HistoryEntry(
                        safeUrl,
                        item.optString("title"),
                        visitedAt,
                        item.optString("id").ifBlank {
                            UUID.nameUUIDFromBytes("$safeUrl|$visitedAt|$index".toByteArray()).toString()
                        }
                    )
                }.getOrNull()
                if (entry != null) add(entry)
            }
        }
    }
}

/** Owns durable browser-library data; UI and tab privacy policy stay in MainActivity. */
internal class NovaBrowserDataRepository(context: Context) {
    private val appContext = context.applicationContext

    fun readBookmarks(): Result<List<BookmarkEntry>> = runCatching {
        NovaBrowserDataCodec.decodeBookmarks(NovaLocalDataStore.readBookmarksJson(appContext))
    }

    fun saveBookmarks(items: List<BookmarkEntry>, trackDeletes: Boolean = true): Result<Unit> = runCatching {
        val safeItems = NovaBrowserDataCodec.sanitizeBookmarks(items)

        if (trackDeletes) {
            val previous = readBookmarks().getOrThrow()
            val retained = safeItems.map { it.url }.toSet()
            val removed = previous.filterNot { it.url in retained }
            if (removed.isNotEmpty()) {
                val tombstones = try {
                    JSONObject(NovaLocalDataStore.readBookmarkTombstonesJson(appContext) ?: "{}")
                } catch (error: Exception) {
                    throw IllegalStateException("Bookmark deletion metadata could not be unlocked", error)
                }
                val deletedAt = System.currentTimeMillis()
                removed.forEach { tombstones.put(it.url, maxOf(deletedAt, it.addedAt + 1L)) }
                NovaLocalDataStore.writeBookmarkTombstonesJson(appContext, tombstones.toString())
            }
        }

        NovaLocalDataStore.writeBookmarksJson(appContext, NovaBrowserDataCodec.encodeBookmarks(safeItems))
    }

    fun readHistory(): Result<List<HistoryEntry>> = runCatching {
        NovaBrowserDataCodec.decodeHistory(NovaLocalDataStore.readHistoryJson(appContext))
    }

    fun appendHistory(url: String, onFailure: (Exception) -> Unit) {
        val safeUrl = HistoryPolicy.sanitizeForStorage(url) ?: return
        NovaLocalDataStore.appendHistoryAsync(
            appContext,
            safeUrl,
            Uri.parse(safeUrl).host ?: safeUrl,
            onFailure
        )
    }

    fun clearHistory(
        now: Long = System.currentTimeMillis(),
        onComplete: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        NovaLocalDataStore.clearHistoryAsync(appContext, now, onComplete, onError)
    }

}
