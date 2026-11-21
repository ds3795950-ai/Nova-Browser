package com.nova.browser

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Reads/applies only the normal browser profile; private and Guest state is never persisted here. */
internal object NovaSyncSnapshotRepository {
    private fun prefs(context: Context) = context.getSharedPreferences("nova_local", Context.MODE_PRIVATE)

    fun build(context: Context, searchEngine: String? = null): JSONObject {
        val p = prefs(context)
        val bookmarks = readArray(p.getString("bookmarks_v2", null))
        val safeBookmarks = if (bookmarks != null) JSONArray().also { out ->
            for (i in 0 until bookmarks.length()) {
                val item = bookmarks.optJSONObject(i) ?: continue
                val url = HistoryPolicy.sanitizeForStorage(item.optString("url")) ?: continue
                if (url.length <= MAX_URL_LENGTH) {
                    out.put(JSONObject(item.toString()).put("url", url)
                        .put("title", item.optString("title").take(MAX_TITLE_LENGTH))
                        .put("folder", item.optString("folder", "General").take(MAX_FOLDER_LENGTH)))
                }
                if (out.length() >= MAX_BOOKMARKS) break
            }
        } else JSONArray().also { out ->
            (p.getStringSet("bookmarks", emptySet()) ?: emptySet()).forEach { url ->
                val safeUrl = HistoryPolicy.sanitizeForStorage(url) ?: return@forEach
                if (safeUrl.length <= MAX_URL_LENGTH && out.length() < MAX_BOOKMARKS) {
                    out.put(JSONObject().put("url", safeUrl).put("title", Uri.parse(safeUrl).host ?: safeUrl).put("folder", "General").put("addedAt", 0L))
                }
            }
        }

        val historySource = readArray(p.getString("history_v2", null))
        val history = if (historySource != null) JSONArray().also { out ->
            for (i in 0 until historySource.length()) {
                val item = historySource.optJSONObject(i) ?: continue
                val url = HistoryPolicy.sanitizeForStorage(item.optString("url")) ?: continue
                if (url.length > MAX_URL_LENGTH) continue
                val normalized = JSONObject(item.toString()).put("url", url)
                    .put("title", item.optString("title").take(MAX_TITLE_LENGTH))
                if (normalized.optString("id").isBlank()) {
                    normalized.put("id", UUID.nameUUIDFromBytes("$url|${item.optLong("visitedAt")}|$i".toByteArray()).toString())
                }
                out.put(normalized)
                if (out.length() >= MAX_HISTORY) break
            }
        } else JSONArray().also { out ->
            (p.getStringSet("history", emptySet()) ?: emptySet()).forEachIndexed { index, url ->
                val safeUrl = HistoryPolicy.sanitizeForStorage(url) ?: return@forEachIndexed
                if (safeUrl.length <= MAX_URL_LENGTH && out.length() < MAX_HISTORY) {
                    val id = UUID.nameUUIDFromBytes("$safeUrl|0|$index".toByteArray()).toString()
                    out.put(JSONObject().put("id", id).put("url", safeUrl).put("title", Uri.parse(safeUrl).host ?: safeUrl).put("visitedAt", 0L))
                }
            }
        }
        val workspaces = JSONArray()
        p.getStringSet("saved_workspaces", emptySet())?.take(MAX_WORKSPACES)?.forEach { raw ->
            runCatching {
                val workspace = JSONObject(raw)
                val name = workspace.optString("name").take(MAX_WORKSPACE_NAME_LENGTH)
                val tabs = workspace.optJSONArray("tabs") ?: JSONArray()
                if (name.isNotBlank() && tabs.length() <= MAX_WORKSPACE_TABS) {
                    val safeTabs = JSONArray()
                    for (i in 0 until tabs.length()) {
                        val tab = tabs.optJSONObject(i) ?: continue
                        val url = HistoryPolicy.sanitizeForStorage(tab.optString("url")) ?: continue
                        if (url.length <= MAX_URL_LENGTH) safeTabs.put(JSONObject(tab.toString()).put("url", url)
                            .put("title", tab.optString("title").take(MAX_TITLE_LENGTH)))
                    }
                    workspaces.put(JSONObject(workspace.toString()).put("name", name).put("tabs", safeTabs))
                }
            }
        }
        val settings = JSONObject().put("search_engine", searchEngine ?: p.getString("search_engine", "Google"))
            .put("shield_mode", p.getString("shield_mode", "STANDARD"))
            .put("show_home_shortcuts", p.getBoolean("show_home_shortcuts", true))
            .put("font_scale_index", p.getInt("font_scale_index", 2))
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
        require(snapshot.toString().length <= MAX_SNAPSHOT_CHARS) { "Cloud snapshot exceeds Nova's size limit" }
        val p = prefs(context)
        val bookmarks = snapshot.optJSONArray("bookmarks") ?: JSONArray()
        val history = snapshot.optJSONArray("history") ?: JSONArray()
        val workspaces = snapshot.optJSONArray("workspaces") ?: JSONArray()
        require(bookmarks.length() <= MAX_BOOKMARKS) { "Cloud snapshot has too many bookmarks" }
        require(history.length() <= MAX_HISTORY) { "Cloud snapshot has too much history" }
        require(workspaces.length() <= MAX_WORKSPACES) { "Cloud snapshot has too many workspaces" }
        val safeBookmarks = JSONArray()
        val urls = linkedSetOf<String>()
        for (i in 0 until bookmarks.length()) {
            val item = bookmarks.optJSONObject(i) ?: continue
            val url = HistoryPolicy.sanitizeForStorage(item.optString("url")) ?: continue
            if (url.length > MAX_URL_LENGTH) continue
            safeBookmarks.put(JSONObject(item.toString()).put("url", url)
                .put("title", item.optString("title").take(MAX_TITLE_LENGTH))
                .put("folder", item.optString("folder", "General").take(MAX_FOLDER_LENGTH)))
            urls.add(url)
        }
        val safeHistory = (0 until history.length()).mapNotNull { history.optJSONObject(it) }
            .mapNotNull { item ->
                val url = HistoryPolicy.sanitizeForStorage(item.optString("url")) ?: return@mapNotNull null
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (url.length > MAX_URL_LENGTH || item.optLong("visitedAt") <= snapshot.optLong("history_cleared_at", 0L)) return@mapNotNull null
                JSONObject(item.toString()).put("id", id).put("url", url).put("title", item.optString("title").take(MAX_TITLE_LENGTH))
            }.sortedByDescending { it.optLong("visitedAt") }.take(MAX_HISTORY)
        val historyArray = JSONArray().also { array -> safeHistory.forEach { array.put(it) } }
        val workspaceSet = mutableSetOf<String>()
        for (i in 0 until workspaces.length()) {
            val item = workspaces.optJSONObject(i) ?: continue
            val name = item.optString("name").take(MAX_WORKSPACE_NAME_LENGTH)
            val tabs = item.optJSONArray("tabs") ?: JSONArray()
            if (name.isBlank() || tabs.length() > MAX_WORKSPACE_TABS) continue
            val safeTabs = JSONArray()
            for (j in 0 until tabs.length()) {
                val tab = tabs.optJSONObject(j) ?: continue
                val url = HistoryPolicy.sanitizeForStorage(tab.optString("url")) ?: continue
                if (url.length <= MAX_URL_LENGTH) safeTabs.put(JSONObject(tab.toString()).put("url", url)
                    .put("title", tab.optString("title").take(MAX_TITLE_LENGTH)))
            }
            workspaceSet.add(JSONObject(item.toString()).put("name", name).put("tabs", safeTabs).toString())
        }
        val settings = snapshot.optJSONObject("settings") ?: JSONObject()
        val editor = p.edit().putString("bookmarks_v2", safeBookmarks.toString()).putStringSet("bookmarks", urls)
            .putString("bookmark_tombstones", (snapshot.optJSONObject("bookmark_tombstones") ?: JSONObject()).toString())
            .putString("workspace_tombstones", (snapshot.optJSONObject("workspace_tombstones") ?: JSONObject()).toString())
            .putLong("history_cleared_at", snapshot.optLong("history_cleared_at", 0L))
            .putString("history_v2", historyArray.toString()).putStringSet("saved_workspaces", workspaceSet)
            .putString("search_engine", settings.optString("search_engine", "Google"))
            .putString("shield_mode", settings.optString("shield_mode", "STANDARD"))
            .putBoolean("show_home_shortcuts", settings.optBoolean("show_home_shortcuts", true))
            .putInt("font_scale_index", settings.optInt("font_scale_index", 2))
            .putStringSet("desktop_sites", settings.optJSONArray("desktop_sites").toStringSet())
            .putStringSet("bookmark_folders", settings.optJSONArray("bookmark_folders").toStringSet())
        check(editor.commit()) { "Could not persist the restored Nova sync snapshot" }
    }

    private fun readArray(raw: String?): JSONArray? = raw?.let { runCatching { JSONArray(it) }.getOrNull() }

    private const val MAX_SNAPSHOT_CHARS = 2 * 1024 * 1024
    private const val MAX_BOOKMARKS = 1000
    private const val MAX_HISTORY = 500
    private const val MAX_WORKSPACES = 100
    private const val MAX_WORKSPACE_TABS = 100
    private const val MAX_URL_LENGTH = 2048
    private const val MAX_TITLE_LENGTH = 512
    private const val MAX_FOLDER_LENGTH = 128
    private const val MAX_WORKSPACE_NAME_LENGTH = 128
    private fun JSONArray?.toStringSet(): Set<String> = this?.let { array -> (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.toSet() } ?: emptySet()
}
