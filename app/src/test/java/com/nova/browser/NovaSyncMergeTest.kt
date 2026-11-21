package com.nova.browser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaSyncMergeTest {
    @Test fun bookmarkDeletionOnOneDeviceWinsOverOlderCopy() {
        val remote = JSONObject().put("bookmarks", JSONArray().put(JSONObject().put("url", "https://example.com").put("addedAt", 100)))
        val local = JSONObject().put("bookmarks", JSONArray()).put("bookmark_tombstones", JSONObject().put("https://example.com", 200))
        val merged = NovaSyncMerge.merge(local, remote)
        assertEquals(0, merged.getJSONArray("bookmarks").length())
        assertEquals(200L, merged.getJSONObject("bookmark_tombstones").getLong("https://example.com"))
    }

    @Test fun bookmarkReaddedAfterDeletionSurvivesMerge() {
        val remote = JSONObject().put("bookmarks", JSONArray().put(JSONObject().put("url", "https://example.com").put("addedAt", 100)))
            .put("bookmark_tombstones", JSONObject().put("https://example.com", 200))
        val local = JSONObject().put("bookmarks", JSONArray().put(JSONObject().put("url", "https://example.com").put("addedAt", 201)))
        val merged = NovaSyncMerge.merge(local, remote)
        assertEquals(1, merged.getJSONArray("bookmarks").length())
        assertEquals(201L, merged.getJSONArray("bookmarks").getJSONObject(0).getLong("addedAt"))
    }

    @Test fun historyClearPropagatesButNewerVisitsRemain() {
        val remote = JSONObject().put("history", JSONArray()
            .put(JSONObject().put("url", "https://old.example").put("visitedAt", 100))
            .put(JSONObject().put("url", "https://new.example").put("visitedAt", 301)))
        val local = JSONObject().put("history", JSONArray()).put("history_cleared_at", 300)
        val merged = NovaSyncMerge.merge(local, remote)
        val history = merged.getJSONArray("history")
        assertEquals(1, history.length())
        assertEquals("https://new.example", history.getJSONObject(0).getString("url"))
        assertEquals(300L, merged.getLong("history_cleared_at"))
    }

    @Test fun legacySnapshotsWithoutTombstonesStillMerge() {
        val local = JSONObject().put("bookmarks", JSONArray().put(JSONObject().put("url", "https://local.example").put("addedAt", 2)))
        val remote = JSONObject().put("bookmarks", JSONArray().put(JSONObject().put("url", "https://remote.example").put("addedAt", 1)))
        val merged = NovaSyncMerge.merge(local, remote)
        assertEquals(2, merged.getJSONArray("bookmarks").length())
        assertTrue(merged.has("bookmark_tombstones"))
    }
    @Test fun workspaceDeletionPropagatesAcrossDevices() {
        val remote = JSONObject().put("workspaces", JSONArray().put(JSONObject().put("name", "Work").put("createdAt", 100)))
        val local = JSONObject().put("workspaces", JSONArray()).put("workspace_tombstones", JSONObject().put("Work", 200))
        val merged = NovaSyncMerge.merge(local, remote)
        assertEquals(0, merged.getJSONArray("workspaces").length())
        assertEquals(200L, merged.getJSONObject("workspace_tombstones").getLong("Work"))
    }

    @Test fun recreatedWorkspaceSurvivesOlderDeletion() {
        val remote = JSONObject().put("workspaces", JSONArray().put(JSONObject().put("name", "Work").put("createdAt", 100)))
            .put("workspace_tombstones", JSONObject().put("Work", 200))
        val local = JSONObject().put("workspaces", JSONArray().put(JSONObject().put("name", "Work").put("createdAt", 201)))
        assertEquals(1, NovaSyncMerge.merge(local, remote).getJSONArray("workspaces").length())
    }

    @Test fun passwordDeletionPropagatesAcrossDevices() {
        val local = JSONObject().put("credentials", JSONArray()).put("deleted", JSONObject().put("https://example.com|alice", 200))
        val remote = JSONObject().put("credentials", JSONArray().put(JSONObject().put("origin", "https://example.com")
            .put("username", "alice").put("password", "secret").put("updatedAt", 100)))
        assertEquals(0, PasswordVault.mergeSyncBundles(local, remote).getJSONArray("credentials").length())
    }

    @Test fun passwordReaddedAfterDeletionSurvives() {
        val local = JSONObject().put("credentials", JSONArray().put(JSONObject().put("origin", "https://example.com")
            .put("username", "alice").put("password", "new-secret").put("updatedAt", 201)))
        val remote = JSONObject().put("credentials", JSONArray()).put("deleted", JSONObject().put("https://example.com|alice", 200))
        val merged = PasswordVault.mergeSyncBundles(local, remote)
        assertEquals(1, merged.getJSONArray("credentials").length())
        assertEquals("new-secret", merged.getJSONArray("credentials").getJSONObject(0).getString("password"))
    }

    @Test fun zeroTimestampBookmarkAndHistorySurviveMergeWithoutTombstones() {
        val local = JSONObject().put("bookmarks", JSONArray().put(JSONObject().put("url", "https://legacy.example").put("addedAt", 0)))
            .put("history", JSONArray().put(JSONObject().put("url", "https://legacy.example/h").put("visitedAt", 0)))
        val merged = NovaSyncMerge.merge(local, JSONObject())
        assertEquals(1, merged.getJSONArray("bookmarks").length())
        assertEquals(1, merged.getJSONArray("history").length())
    }

    @Test fun malformedRemoteArrayElementDoesNotBreakMerge() {
        val remote = JSONObject().put("bookmarks", JSONArray().put("not-an-object").put(JSONObject().put("url", "https://ok.example").put("addedAt", 5)))
        val merged = NovaSyncMerge.merge(JSONObject(), remote)
        assertEquals(1, merged.getJSONArray("bookmarks").length())
    }

    @Test fun legacyPasswordArrayCanBeReadAsBundle() {
        val legacy = JSONArray().put(JSONObject().put("origin", "https://example.com").put("username", "alice").put("password", "secret"))
        assertEquals(1, PasswordVault.decodeSyncBundle(legacy.toString()).getJSONArray("credentials").length())
    }

}
