package com.nova.browser

import com.nova.browser.engine.BrowserEngineType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaSyncMergeTest {
    @Test fun newCloudBundleKeepsBrowserAndVaultInOneEnvelope() {
        val browser = JSONObject().put("bookmarks", JSONArray().put(JSONObject().put("url", "https://nova.example")))
        val vault = JSONObject().put("credentials", JSONArray().put(JSONObject().put("origin", "https://nova.example")))
        val packed = NovaCloudBundle.pack(browser, vault)
        val (restoredBrowser, restoredVault) = NovaCloudBundle.unpack(packed, null)
        assertEquals(browser.toString(), restoredBrowser.toString())
        assertEquals(vault.toString(), restoredVault.toString())
    }

    @Test fun legacyTwoTableBackupStillLoadsAlongsideAtomicBundleFormat() {
        val browser = JSONObject().put("bookmarks", JSONArray())
        val legacyVault = JSONObject().put("credentials", JSONArray())
        val (restoredBrowser, restoredVault) = NovaCloudBundle.unpack(browser, legacyVault)
        assertEquals(browser.toString(), restoredBrowser.toString())
        assertEquals(legacyVault.toString(), restoredVault.toString())
    }

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



    @Test fun cefriumAndGeckoViewProfilesNeverMergeIntoEachOther() {
        val local = JSONObject().put("profiles", JSONObject()
            .put("GECKOVIEW", JSONObject().put("bookmarks", JSONArray().put(JSONObject().put("url", "https://gecko.example").put("addedAt", 10))))
            .put("CEFRIUM", JSONObject().put("bookmarks", JSONArray().put(JSONObject().put("url", "https://cefrium.example").put("addedAt", 20)))))
        val remote = JSONObject().put("profiles", JSONObject()
            .put("GECKOVIEW", JSONObject().put("bookmarks", JSONArray().put(JSONObject().put("url", "https://gecko-remote.example").put("addedAt", 30))))
            .put("CEFRIUM", JSONObject().put("bookmarks", JSONArray().put(JSONObject().put("url", "https://cefrium-remote.example").put("addedAt", 40)))))
        val merged = NovaSyncMerge.merge(local, remote).getJSONObject("profiles")
        val geckoUrls = merged.getJSONObject("GECKOVIEW").getJSONArray("bookmarks")
        val cefriumUrls = merged.getJSONObject("CEFRIUM").getJSONArray("bookmarks")
        assertEquals(2, geckoUrls.length())
        assertEquals(2, cefriumUrls.length())
        val geckoUrlSet = (0 until geckoUrls.length()).map { geckoUrls.getJSONObject(it).getString("url") }.toSet()
        val cefriumUrlSet = (0 until cefriumUrls.length()).map { cefriumUrls.getJSONObject(it).getString("url") }.toSet()
        assertTrue(geckoUrlSet.contains("https://gecko.example"))
        assertTrue(geckoUrlSet.contains("https://gecko-remote.example"))
        assertTrue(cefriumUrlSet.contains("https://cefrium.example"))
        assertTrue(cefriumUrlSet.contains("https://cefrium-remote.example"))
        for (url in geckoUrlSet) assertTrue(!url.contains("cefrium"))
        for (url in cefriumUrlSet) assertTrue(!url.contains("gecko"))
    }

    @Test fun legacyUnscopedSnapshotCannotPopulateEitherEngineProfile() {
        val local = JSONObject().put("profiles", JSONObject().put("GECKOVIEW", JSONObject()
            .put("bookmarks", JSONArray().put(JSONObject().put("url", "https://owned-gecko.example").put("addedAt", 1)))))
        val legacyRemote = JSONObject().put("bookmarks", JSONArray().put(JSONObject().put("url", "https://unscoped.example").put("addedAt", 99)))
        val merged = NovaSyncMerge.merge(local, legacyRemote).getJSONObject("profiles")
        assertEquals(1, merged.getJSONObject("GECKOVIEW").getJSONArray("bookmarks").length())
        assertEquals("https://owned-gecko.example", merged.getJSONObject("GECKOVIEW").getJSONArray("bookmarks").getJSONObject(0).getString("url"))
        assertTrue(!merged.has("CEFRIUM"))
    }


    @Test fun engineStorageKeysAreDisjointAndStable() {
        assertEquals("bookmarks_geckoview", EngineProfileStoragePolicy.key("bookmarks", BrowserEngineType.GECKOVIEW))
        assertEquals("bookmarks_cefrium", EngineProfileStoragePolicy.key("bookmarks", BrowserEngineType.CEFRIUM))
        assertTrue(EngineProfileStoragePolicy.key("history_v2", BrowserEngineType.GECKOVIEW) != EngineProfileStoragePolicy.key("history_v2", BrowserEngineType.CEFRIUM))
    }

    @Test fun historiesAndWorkspacesRemainInTheirOwnEngineProfiles() {
        val local = JSONObject().put("profiles", JSONObject()
            .put("GECKOVIEW", JSONObject()
                .put("history", JSONArray().put(JSONObject().put("url", "https://gecko-history.example").put("visitedAt", 10)))
                .put("workspaces", JSONArray().put(JSONObject().put("name", "Gecko workspace").put("createdAt", 10).put("engine", "GECKOVIEW"))))
            .put("CEFRIUM", JSONObject()
                .put("history", JSONArray().put(JSONObject().put("url", "https://cefrium-history.example").put("visitedAt", 20)))
                .put("workspaces", JSONArray().put(JSONObject().put("name", "Cefrium workspace").put("createdAt", 20).put("engine", "CEFRIUM")))))
        val merged = NovaSyncMerge.merge(local, JSONObject()).getJSONObject("profiles")
        val gecko = merged.getJSONObject("GECKOVIEW")
        val cefrium = merged.getJSONObject("CEFRIUM")
        assertEquals("https://gecko-history.example", gecko.getJSONArray("history").getJSONObject(0).getString("url"))
        assertEquals("https://cefrium-history.example", cefrium.getJSONArray("history").getJSONObject(0).getString("url"))
        assertEquals("GECKOVIEW", gecko.getJSONArray("workspaces").getJSONObject(0).getString("engine"))
        assertEquals("CEFRIUM", cefrium.getJSONArray("workspaces").getJSONObject(0).getString("engine"))
    }
    @Test fun malformedNewerCloudCredentialDoesNotReplaceValidLocalCredential() {
        val local = JSONObject().put("credentials", JSONArray().put(JSONObject()
            .put("origin", "https://example.com").put("username", "alice")
            .put("password", "valid-local-secret").put("updatedAt", 10)))
        val remote = JSONObject().put("credentials", JSONArray().put(JSONObject()
            .put("origin", "https://example.com").put("username", "alice")
            .put("updatedAt", 20)))
        val merged = PasswordVault.mergeSyncBundles(local, remote).getJSONArray("credentials")
        assertEquals(1, merged.length())
        assertEquals("valid-local-secret", merged.getJSONObject(0).getString("password"))
    }

}
