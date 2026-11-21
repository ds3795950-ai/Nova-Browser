package com.nova.browser

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Reads/applies only the normal browser profile; private and Guest state is never persisted here. */
internal object NovaSyncSnapshotRepository {
    private fun prefs(context: Context) = context.getSharedPreferences("nova_local", Context.MODE_PRIVATE)

    fun build(context: Context, searchEngine: String? = null): JSONObject {
        val p = prefs(context)
        val bookmarks = readArray(p.getString("bookmarks_v2", null))
        val safeBookmarks = if (bookmarks != null) JSONArray().also { out ->
            for (i in 0 until bookmarks.length()) {
                val item = bookmarks.optJSONObject(i) ?: continue
                val url = item.optString("url")
                if (url.startsWith("http://") || url.startsWith("https://")) out.put(item)
            }
        } else JSONArray().also { out ->
            (p.getStringSet("bookmarks", emptySet()) ?: emptySet()).forEach { url ->
                if (url.startsWith("http://") || url.startsWith("https://")) out.put(JSONObject().put("url", url).put("title", Uri.parse(url).host ?: url).put("folder", "General").put("addedAt", 0L))
            }
        }

        val historySource = readArray(p.getString("history_v2", null))
        val history = if (historySource != null) JSONArray().also { out ->
            for (i in 0 until historySource.length()) {
                val item = historySource.optJSONObject(i) ?: continue
                val url = item.optString("url")
                if (url.startsWith("http://") || url.startsWith("https://")) out.put(item)
            }
        } else JSONArray().also { out ->
            (p.getStringSet("history", emptySet()) ?: emptySet()).forEach { url ->
                if (url.startsWith("http://") || url.startsWith("https://")) out.put(JSONObject().put("url", url).put("title", Uri.parse(url).host ?: url).put("visitedAt", 0L))
            }
        }
        val workspaces = JSONArray()
        p.getStringSet("saved_workspaces", emptySet())?.forEach { raw -> runCatching { workspaces.put(JSONObject(raw)) } }
        val settings = JSONObject().put("search_engine", searchEngine ?: p.getString("search_engine", "Google"))
            .put("shield_mode", p.getString("shield_mode", "STANDARD"))
            .put("show_home_shortcuts", p.getBoolean("show_home_shortcuts", true))
            .put("font_scale_index", p.getInt("font_scale_index", 1))
            .put("desktop_sites", JSONArray(p.getStringSet("desktop_sites", emptySet())?.toList() ?: emptyList<String>()))
            .put("bookmark_folders", JSONArray(p.getStringSet("bookmark_folders", emptySet())?.toList() ?: emptyList<String>()))
        val tombstones = runCatching { JSONObject(p.getString("bookmark_tombstones", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val workspaceTombstones = runCatching { JSONObject(p.getString("workspace_tombstones", "{}") ?: "{}") }.getOrDefault(JSONObject())
        return JSONObject().put("schema", 3).put("bookmarks", safeBookmarks).put("history", history)
            .put("bookmark_tombstones", tombstones).put("workspace_tombstones", workspaceTombstones)
            .put("history_cleared_at", p.getLong("history_cleared_at", 0L))
            .put("workspaces", workspaces).put("settings", settings)
    }

    fun apply(context: Context, snapshot: JSONObject) {
        val p = prefs(context)
        val bookmarks = snapshot.optJSONArray("bookmarks") ?: JSONArray()
        val safeBookmarks = JSONArray()
        val urls = linkedSetOf<String>()
        for (i in 0 until bookmarks.length()) {
            val item = bookmarks.optJSONObject(i) ?: continue
            val url = item.optString("url")
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            safeBookmarks.put(item); urls.add(url)
        }
        val history = snapshot.optJSONArray("history") ?: JSONArray()
        val safeHistory = (0 until history.length()).mapNotNull { history.optJSONObject(it) }
            .filter { (it.optString("url").startsWith("http://") || it.optString("url").startsWith("https://")) && (snapshot.optLong("history_cleared_at", 0L) == 0L || it.optLong("visitedAt") > snapshot.optLong("history_cleared_at", 0L)) }
            .sortedByDescending { it.optLong("visitedAt") }.take(500)
        val historyArray = JSONArray().also { array -> safeHistory.forEach { array.put(it) } }
        val workspaceSet = mutableSetOf<String>()
        val workspaces = snapshot.optJSONArray("workspaces") ?: JSONArray()
        for (i in 0 until workspaces.length()) workspaces.optJSONObject(i)?.let { workspaceSet.add(it.toString()) }
        val settings = snapshot.optJSONObject("settings") ?: JSONObject()
        val editor = p.edit().putString("bookmarks_v2", safeBookmarks.toString()).putStringSet("bookmarks", urls)
            .putString("bookmark_tombstones", (snapshot.optJSONObject("bookmark_tombstones") ?: JSONObject()).toString())
            .putString("workspace_tombstones", (snapshot.optJSONObject("workspace_tombstones") ?: JSONObject()).toString())
            .putLong("history_cleared_at", snapshot.optLong("history_cleared_at", 0L))
            .putString("history_v2", historyArray.toString()).putStringSet("saved_workspaces", workspaceSet)
            .putString("search_engine", settings.optString("search_engine", "Google"))
            .putString("shield_mode", settings.optString("shield_mode", "STANDARD"))
            .putBoolean("show_home_shortcuts", settings.optBoolean("show_home_shortcuts", true))
            .putInt("font_scale_index", settings.optInt("font_scale_index", 1))
            .putStringSet("desktop_sites", settings.optJSONArray("desktop_sites").toStringSet())
            .putStringSet("bookmark_folders", settings.optJSONArray("bookmark_folders").toStringSet())
        editor.apply()
    }

    private fun readArray(raw: String?): JSONArray? = raw?.let { runCatching { JSONArray(it) }.getOrNull() }
    private fun JSONArray?.toStringSet(): Set<String> = this?.let { array -> (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.toSet() } ?: emptySet()
}
