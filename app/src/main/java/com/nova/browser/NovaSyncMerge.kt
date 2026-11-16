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
            for (i in 0 until old.length()) { val item = old.getJSONObject(i); merged[item.optString(idField)] = item }
            for (i in 0 until current.length()) {
                val item = current.getJSONObject(i); val id = item.optString(idField)
                val prior = merged[id]
                if (prior == null || maxField == null || item.optLong(maxField) >= prior.optLong(maxField)) merged[id] = item
            }
            result.put(key, JSONArray().also { array -> merged.values.forEach { array.put(it) } })
        }
        mergeArray("bookmarks", "url", "addedAt")
        mergeArray("history", "url", "visitedAt")
        mergeArray("workspaces", "name")

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
            if (item.optLong("addedAt") > tombstones.optLong(item.optString("url"))) keptBookmarks.put(item)
        }
        result.put("bookmarks", keptBookmarks).put("bookmark_tombstones", tombstones)

        val historyClearedAt = maxOf(local.optLong("history_cleared_at"), remote.optLong("history_cleared_at"))
        val keptHistory = JSONArray()
        val mergedHistory = result.optJSONArray("history") ?: JSONArray()
        for (i in 0 until mergedHistory.length()) {
            val item = mergedHistory.getJSONObject(i)
            if (item.optLong("visitedAt") > historyClearedAt) keptHistory.put(item)
        }
        result.put("history", keptHistory).put("history_cleared_at", historyClearedAt)
        result.put("settings", local.optJSONObject("settings") ?: remote.optJSONObject("settings") ?: JSONObject())
        result.put("schema", 2)
        return result
    }
}
