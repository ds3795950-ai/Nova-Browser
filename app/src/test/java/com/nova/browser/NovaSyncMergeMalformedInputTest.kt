package com.nova.browser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class NovaSyncMergeMalformedInputTest {
    @Test fun blankBookmarkIdentifiersAreIgnored() {
        val local = JSONObject().put("bookmarks", JSONArray()
            .put(JSONObject().put("title", "missing URL"))
            .put(JSONObject().put("url", "https://valid.example").put("addedAt", 1)))
        val merged = NovaSyncMerge.merge(local, JSONObject()).getJSONArray("bookmarks")
        assertEquals(1, merged.length())
        assertEquals("https://valid.example", merged.getJSONObject(0).getString("url"))
    }

    @Test fun blankWorkspaceNamesAreIgnored() {
        val local = JSONObject().put("workspaces", JSONArray()
            .put(JSONObject().put("tabs", JSONArray()))
            .put(JSONObject().put("name", "Work").put("createdAt", 1)))
        val merged = NovaSyncMerge.merge(local, JSONObject()).getJSONArray("workspaces")
        assertEquals(1, merged.length())
        assertEquals("Work", merged.getJSONObject(0).getString("name"))
    }

    @Test fun blankHistoryIdsAreIgnored() {
        val local = JSONObject().put("history", JSONArray()
            .put(JSONObject().put("url", "https://invalid.example").put("visitedAt", 2))
            .put(JSONObject().put("id", "visit-1").put("url", "https://valid.example").put("visitedAt", 1)))
        val merged = NovaSyncMerge.merge(local, JSONObject()).getJSONArray("history")
        assertEquals(1, merged.length())
        assertEquals("visit-1", merged.getJSONObject(0).getString("id"))
    }
}
