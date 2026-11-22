package com.nova.browser

import com.nova.browser.engine.BrowserEngineType
import org.json.JSONArray
import org.json.JSONObject

/** Timestamp-aware merge. Engine profiles are merged independently and never cross-populate. */
internal object NovaSyncMerge {
    fun merge(local: JSONObject, remote: JSONObject): JSONObject {
        val localProfiles = local.optJSONObject("profiles")
        val remoteProfiles = remote.optJSONObject("profiles")
        if (localProfiles != null || remoteProfiles != null) {
            val profiles = JSONObject()
            for (engine in BrowserEngineType.values()) {
                val localProfile = localProfiles?.optJSONObject(engine.name)
                val remoteProfile = remoteProfiles?.optJSONObject(engine.name)
                if (localProfile != null || remoteProfile != null) {
                    profiles.put(engine.name, mergeProfile(localProfile ?: JSONObject(), remoteProfile ?: JSONObject()))
                }
            }
            return JSONObject().put("schema", 4).put("profiles", profiles)
                .put("settings", local.optJSONObject("settings") ?: remote.optJSONObject("settings") ?: JSONObject())
        }
        return mergeLegacy(local, remote)
    }

    private fun mergeProfile(local: JSONObject, remote: JSONObject): JSONObject {
        val localEnvelope = JSONObject().put("bookmarks", local.optJSONArray("bookmarks") ?: JSONArray())
            .put("history", local.optJSONArray("history") ?: JSONArray())
            .put("workspaces", local.optJSONArray("workspaces") ?: JSONArray())
            .put("bookmark_tombstones", local.optJSONObject("bookmark_tombstones") ?: JSONObject())
            .put("workspace_tombstones", local.optJSONObject("workspace_tombstones") ?: JSONObject())
            .put("history_cleared_at", local.optLong("history_cleared_at", 0L))
            .put("bookmark_folders", local.optJSONArray("bookmark_folders") ?: JSONArray())
        val remoteEnvelope = JSONObject().put("bookmarks", remote.optJSONArray("bookmarks") ?: JSONArray())
            .put("history", remote.optJSONArray("history") ?: JSONArray())
            .put("workspaces", remote.optJSONArray("workspaces") ?: JSONArray())
            .put("bookmark_tombstones", remote.optJSONObject("bookmark_tombstones") ?: JSONObject())
            .put("workspace_tombstones", remote.optJSONObject("workspace_tombstones") ?: JSONObject())
            .put("history_cleared_at", remote.optLong("history_cleared_at", 0L))
            .put("bookmark_folders", remote.optJSONArray("bookmark_folders") ?: JSONArray())
        val merged = mergeLegacy(localEnvelope, remoteEnvelope)
        val folders = linkedSetOf<String>()
        for (source in listOf(localEnvelope.optJSONArray("bookmark_folders"), remoteEnvelope.optJSONArray("bookmark_folders"))) {
            source?.let { array -> for (i in 0 until array.length()) array.optString(i).takeIf(String::isNotBlank)?.let(folders::add) }
        }
        merged.put("bookmark_folders", JSONArray().also { out -> folders.forEach(out::put) })
        merged.remove("settings"); merged.remove("schema")
        return merged
    }

    private fun mergeLegacy(local: JSONObject, remote: JSONObject): JSONObject {
        val result = JSONObject(remote.toString())
        fun mergeArray(key: String, idField: String, maxField: String? = null) {
            val merged = linkedMapOf<String, JSONObject>()
            val old = remote.optJSONArray(key) ?: JSONArray()
            val current = local.optJSONArray(key) ?: JSONArray()
            for (i in 0 until old.length()) {
                val item = old.optJSONObject(i) ?: continue
                val id = item.optString(idField).takeIf { it.isNotBlank() } ?: continue
                merged[id] = item
            }
            for (i in 0 until current.length()) {
                val item = current.optJSONObject(i) ?: continue
                val id = item.optString(idField).takeIf { it.isNotBlank() } ?: continue
                val prior = merged[id]
                if (prior == null || maxField == null || item.optLong(maxField) >= prior.optLong(maxField)) merged[id] = item
            }
            result.put(key, JSONArray().also { array -> merged.values.forEach(array::put) })
        }
        mergeArray("bookmarks", "url", "addedAt")
        mergeArray("history", "url", "visitedAt")
        mergeArray("workspaces", "name", "createdAt")
        val tombstones = mergeTombstones(local.optJSONObject("bookmark_tombstones"), remote.optJSONObject("bookmark_tombstones"))
        val keptBookmarks = JSONArray()
        val mergedBookmarks = result.optJSONArray("bookmarks") ?: JSONArray()
        for (i in 0 until mergedBookmarks.length()) {
            val item = mergedBookmarks.getJSONObject(i)
            val url = item.optString("url")
            if (!tombstones.has(url) || item.optLong("addedAt") > tombstones.optLong(url)) keptBookmarks.put(item)
        }
        result.put("bookmarks", keptBookmarks).put("bookmark_tombstones", tombstones)
        val workspaceTombstones = mergeTombstones(local.optJSONObject("workspace_tombstones"), remote.optJSONObject("workspace_tombstones"))
        val keptWorkspaces = JSONArray()
        val mergedWorkspaces = result.optJSONArray("workspaces") ?: JSONArray()
        for (i in 0 until mergedWorkspaces.length()) {
            val item = mergedWorkspaces.getJSONObject(i)
            val deletedAt = workspaceTombstones.optLong(item.optString("name"))
            if (deletedAt == 0L || item.optLong("createdAt") > deletedAt) keptWorkspaces.put(item)
        }
        result.put("workspaces", keptWorkspaces).put("workspace_tombstones", workspaceTombstones)
        val clearedAt = maxOf(local.optLong("history_cleared_at"), remote.optLong("history_cleared_at"))
        val keptHistory = JSONArray()
        val mergedHistory = result.optJSONArray("history") ?: JSONArray()
        for (i in 0 until mergedHistory.length()) {
            val item = mergedHistory.getJSONObject(i)
            if (clearedAt <= 0L || item.optLong("visitedAt") > clearedAt) keptHistory.put(item)
        }
        result.put("history", keptHistory).put("history_cleared_at", clearedAt)
        result.put("settings", local.optJSONObject("settings") ?: remote.optJSONObject("settings") ?: JSONObject())
        result.put("schema", 3)
        return result
    }

    private fun mergeTombstones(local: JSONObject?, remote: JSONObject?): JSONObject {
        val merged = JSONObject()
        for (source in listOf(remote ?: JSONObject(), local ?: JSONObject())) {
            val keys = source.keys()
            while (keys.hasNext()) { val key = keys.next(); merged.put(key, maxOf(merged.optLong(key), source.optLong(key))) }
        }
        return merged
    }
}
