package com.nova.browser

import org.json.JSONArray
import org.json.JSONObject

/** Deterministic, timestamp-aware merge for encrypted multi-device browser snapshots. */
internal object NovaSyncMerge {
    fun merge(local: JSONObject, remote: JSONObject): JSONObject {
        val result = JSONObject(remote.toString())
        fun mergeArray(key: String, idField: String, maxField: String? = null) {
            val merged = linkedMapOf<String, JSONObject>()
            val old = remote.optJSONArray(key) ?: JSONArray()
            val current = local.optJSONArray(key) ?: JSONArray()
            for (i in 0 until old.length()) { val item = old.optJSONObject(i) ?: continue; merged[item.optString(idField)] = item }
            for (i in 0 until current.length()) {
                val item = current.optJSONObject(i) ?: continue; val id = item.optString(idField)
                val prior = merged[id]
                if (prior == null || maxField == null || item.optLong(maxField) >= prior.optLong(maxField)) merged[id] = item
            }
            result.put(key, JSONArray().also { array -> merged.values.forEach { array.put(it) } })
        }
        mergeArray("bookmarks", "url", "addedAt")
        mergeArray("history", "url", "visitedAt")
        mergeArray("workspaces", "name", "createdAt")

        // Merge deletion markers by their latest timestamp so deletes made on one device
        // are not resurrected when another device syncs an older copy.
        val tombstones = JSONObject()
        for (source in listOf(remote.optJSONObject("bookmark_tombstones") ?: JSONObject(), local.optJSONObject("bookmark_tombstones") ?: JSONObject())) {
            val keys = source.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                tombstones.put(key, maxOf(tombstones.optLong(key), source.optLong(key)))
            }
        }
        val keptBookmarks = JSONArray()
        val mergedBookmarks = result.optJSONArray("bookmarks") ?: JSONArray()
        for (i in 0 until mergedBookmarks.length()) {
            val item = mergedBookmarks.getJSONObject(i)
            val deletedAt = tombstones.optLong(item.optString("url"))
            if (deletedAt == 0L || item.optLong("addedAt") > deletedAt) keptBookmarks.put(item)
        }
        result.put("bookmarks", keptBookmarks).put("bookmark_tombstones", tombstones)

        val workspaceTombstones = JSONObject()
        for (source in listOf(remote.optJSONObject("workspace_tombstones") ?: JSONObject(), local.optJSONObject("workspace_tombstones") ?: JSONObject())) {
            val keys = source.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                workspaceTombstones.put(key, maxOf(workspaceTombstones.optLong(key), source.optLong(key)))
            }
        }
        val keptWorkspaces = JSONArray()
        val mergedWorkspaces = result.optJSONArray("workspaces") ?: JSONArray()
        for (i in 0 until mergedWorkspaces.length()) {
            val item = mergedWorkspaces.getJSONObject(i)
            val deletedAt = workspaceTombstones.optLong(item.optString("name"))
            if (deletedAt == 0L || item.optLong("createdAt") > deletedAt) keptWorkspaces.put(item)
        }
        result.put("workspaces", keptWorkspaces).put("workspace_tombstones", workspaceTombstones)

        val historyClearedAt = maxOf(local.optLong("history_cleared_at"), remote.optLong("history_cleared_at"))
        val keptHistory = JSONArray()
        val mergedHistory = result.optJSONArray("history") ?: JSONArray()
        for (i in 0 until mergedHistory.length()) {
            val item = mergedHistory.getJSONObject(i)
            if (historyClearedAt == 0L || item.optLong("visitedAt") > historyClearedAt) keptHistory.put(item)
        }
        result.put("history", keptHistory).put("history_cleared_at", historyClearedAt)
        result.put("settings", local.optJSONObject("settings") ?: remote.optJSONObject("settings") ?: JSONObject())
        result.put("schema", 3)
        return result
    }
}
