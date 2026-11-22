package com.nova.browser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaBrowserDataCodecTest {
    @Test
    fun decodesOnlySafeBookmarkUrlsAndBoundsUserText() {
        val longTitle = "t".repeat(NovaBrowserDataCodec.MAX_TITLE_LENGTH + 20)
        val longFolder = "f".repeat(NovaBrowserDataCodec.MAX_FOLDER_LENGTH + 20)
        val raw = JSONArray()
            .put(JSONObject().put("url", "https://example.com/a").put("title", longTitle).put("folder", longFolder))
            .put(JSONObject().put("url", "javascript:alert(1)"))
            .put(JSONObject().put("url", "https://example.com/" + "x".repeat(NovaBrowserDataCodec.MAX_URL_LENGTH)))
            .put("not-an-object")
            .toString()

        val entries = NovaBrowserDataCodec.decodeBookmarks(raw)

        assertEquals(1, entries.size)
        assertEquals("https://example.com/a", entries.single().url)
        assertEquals(NovaBrowserDataCodec.MAX_TITLE_LENGTH, entries.single().title.length)
        assertEquals(NovaBrowserDataCodec.MAX_FOLDER_LENGTH, entries.single().folder.length)
        assertEquals("General", NovaBrowserDataCodec.decodeBookmarks("[{\"url\":\"https://example.com\"}]").single().folder)
    }

    @Test
    fun sanitizesAndRoundTripsBookmarkEntries() {
        val input = listOf(
            BookmarkEntry("https://example.com/path", "Title", "Folder", 123L),
            BookmarkEntry("file:///private/data", "Unsafe", "Folder", 124L)
        )

        val safe = NovaBrowserDataCodec.sanitizeBookmarks(input)
        val decoded = NovaBrowserDataCodec.decodeBookmarks(NovaBrowserDataCodec.encodeBookmarks(input))

        assertEquals(listOf(input.first()), safe)
        assertEquals(safe, decoded)
    }

    @Test
    fun decodesHistoryWithStableIdsAndSkipsUnsafeRecords() {
        val raw = JSONArray()
            .put(JSONObject().put("url", "https://example.com/page").put("title", "Example").put("visitedAt", 99L))
            .put(JSONObject().put("url", "file:///private/data").put("visitedAt", 100L))
            .put("invalid-record")
            .toString()

        val first = NovaBrowserDataCodec.decodeHistory(raw)
        val second = NovaBrowserDataCodec.decodeHistory(raw)

        assertEquals(1, first.size)
        assertEquals(first.single().id, second.single().id)
        assertEquals("https://example.com/page", first.single().url)
        assertEquals(99L, first.single().visitedAt)
        assertTrue(first.single().id.isNotBlank())
        assertNotEquals("", first.single().id)
    }

    @Test
    fun nullCollectionsAreEmptyAndMalformedTopLevelJsonFailsClosed() {
        assertTrue(NovaBrowserDataCodec.decodeBookmarks(null).isEmpty())
        assertTrue(NovaBrowserDataCodec.decodeHistory(null).isEmpty())
        assertThrows(org.json.JSONException::class.java) {
            NovaBrowserDataCodec.decodeBookmarks("{not-an-array}")
        }
        assertFalse(NovaBrowserDataCodec.sanitizeBookmarks(emptyList()).isNotEmpty())
    }
}
