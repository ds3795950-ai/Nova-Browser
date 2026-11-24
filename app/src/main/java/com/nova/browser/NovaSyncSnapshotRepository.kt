package com.nova.browser

import android.content.Context
import android.net.Uri
import com.nova.browser.engine.BrowserEngineType
import org.json.JSONArray
import org.json.JSONObject

/** Encrypted sync repository with separate Normal-mode records for each browser engine. */
internal object NovaSyncSnapshotRepository {
    private fun prefs(context: Context) = context.getSharedPreferences("nova_local", Context.MODE_PRIVATE)
    private fun key(base: String, engine: BrowserEngineType) = EngineProfileStoragePolicy.key(base, engine)

    fun build(context: Context, searchEngine: String? = null): JSONObject {
        val p = prefs(context)
        val profiles = JSONObject()
        for (engine in BrowserEngineType.values()) {
            val bookmarksSource = readArray(p.getString(key("bookmarks_v2", engine), null))
            val bookmarks = if (bookmarksSource != null) JSONArray().also { out ->
                for (i in 0 until bookmarksSource.length()) {
                    val item = bookmarksSource.optJSONObject(i) ?: continue
                    val url = item.optString("url")
                    if (url.startsWith("http://") || url.startsWith("https://")) out.put(item)
                }
            } else JSONArray().also { out ->
                (p.getStringSet(key("bookmarks", engine), emptySet()) ?: emptySet()).forEach { url ->
                    if (url.startsWith("http://") || url.startsWith("https://")) out.put(JSONObject().put("url", url).put("title", Uri.parse(url).host ?: url).put("folder", "General").put("addedAt", 0L))
                }
            }
            val historySource = readArray(p.getString(key("history_v2", engine), null))
            val history = if (historySource != null) JSONArray().also { out ->
                for (i in 0 until historySource.length()) {
                    val item = historySource.optJSONObject(i) ?: continue
                    val url = item.optString("url")
                    if (url.startsWith("http://") || url.startsWith("https://")) out.put(item)
                }
            } else JSONArray().also { out ->
                (p.getStringSet(key("history", engine), emptySet()) ?: emptySet()).forEach { url ->
                    if (url.startsWith("http://") || url.startsWith("https://")) out.put(JSONObject().put("url", url).put("title", Uri.parse(url).host ?: url).put("visitedAt", 0L))
                }
            }
            val validatedWorkspaceEntries = linkedSetOf<String>()
            (p.getStringSet(key("saved_workspaces", engine), emptySet()) ?: emptySet()).sorted().forEach { raw ->
                val candidate = runCatching { JSONObject(raw).put("engine", engine.name).toString() }.getOrNull()
                    ?: return@forEach
                val next = validatedWorkspaceEntries + candidate
                if (runCatching { NovaWorkspaceStoragePolicy.serialize(next) }.isSuccess) {
                    validatedWorkspaceEntries.add(candidate)
                }
            }
            val workspaces = JSONArray().also { array ->
                validatedWorkspaceEntries.forEach { array.put(JSONObject(it)) }
            }
            val workspaceTombstones = NovaWorkspaceStoragePolicy.validateTombstones(
                p.getString(key("workspace_tombstones", engine), "{}") ?: "{}"
            )
            profiles.put(engine.name, JSONObject()
                .put("bookmarks", bookmarks)
                .put("history", history)
                .put("workspaces", workspaces)
                .put("bookmark_tombstones", readObject(p.getString(key("bookmark_tombstones", engine), "{}")))
                .put("workspace_tombstones", JSONObject(workspaceTombstones))
                .put("history_cleared_at", p.getLong(key("history_cleared_at", engine), 0L))
                .put("bookmark_folders", JSONArray(p.getStringSet(key("bookmark_folders", engine), emptySet())?.toList() ?: emptyList<String>()))
            )
        }
        val settings = JSONObject().put("search_engine", searchEngine ?: p.getString("search_engine", "Google"))
            .put("shield_mode", p.getString("shield_mode", "STANDARD"))
            .put("show_home_shortcuts", p.getBoolean("show_home_shortcuts", true))
            .put("font_scale_index", p.getInt("font_scale_index", 1))
            .put("desktop_sites", JSONArray(p.getStringSet("desktop_sites", emptySet())?.toList() ?: emptyList<String>()))
        val snapshot = JSONObject().put("schema", 4).put("profiles", profiles).put("settings", settings)
        validate(snapshot)
        return snapshot
    }

    /** Validate cloud workspace payloads before upload or local mutation. */
    fun validate(snapshot: JSONObject) {
        val profiles = snapshot.optJSONObject("profiles") ?: return
        for (engine in BrowserEngineType.values()) {
            if (!profiles.has(engine.name)) continue
            val profile = profiles.optJSONObject(engine.name)
                ?: throw IllegalArgumentException("Cloud browser profile is malformed")
            val workspaces = profile.optJSONArray("workspaces") ?: JSONArray()
            val entries = linkedSetOf<String>()
            for (i in 0 until workspaces.length()) {
                val item = workspaces.optJSONObject(i)
                    ?: throw IllegalArgumentException("Cloud workspace entry is malformed")
                require(item.optString("engine") == engine.name) { "Cloud workspace belongs to a different browser engine" }
                entries.add(item.toString())
            }
            NovaWorkspaceStoragePolicy.serialize(entries)
            val rawTombstones = profile.opt("workspace_tombstones")
            val tombstones = when (rawTombstones) {
                null -> JSONObject()
                is JSONObject -> rawTombstones
                else -> throw IllegalArgumentException("Cloud workspace deletion history is malformed")
            }
            NovaWorkspaceStoragePolicy.validateTombstones(tombstones.toString())
        }
    }

    fun apply(context: Context, snapshot: JSONObject) {
        validate(snapshot)
        val p = prefs(context)
        val editor = p.edit()
        val profiles = snapshot.optJSONObject("profiles")
        if (profiles != null) {
            for (engine in BrowserEngineType.values()) {
                if (!profiles.has(engine.name)) continue
                val profile = profiles.optJSONObject(engine.name) ?: continue
                val bookmarks = profile.optJSONArray("bookmarks") ?: JSONArray()
                val safeBookmarks = JSONArray()
                val urls = linkedSetOf<String>()
                for (i in 0 until bookmarks.length()) {
                    val item = bookmarks.optJSONObject(i) ?: continue
                    val url = item.optString("url")
                    if (url.startsWith("http://") || url.startsWith("https://")) { safeBookmarks.put(item); urls.add(url) }
                }
                val clearedAt = profile.optLong("history_cleared_at", 0L)
                val history = profile.optJSONArray("history") ?: JSONArray()
                val safeHistory = (0 until history.length()).mapNotNull { history.optJSONObject(it) }
                    .filter { (it.optString("url").startsWith("http://") || it.optString("url").startsWith("https://")) && it.optLong("visitedAt") > clearedAt }
                    .sortedByDescending { it.optLong("visitedAt") }.take(500)
                val historyArray = JSONArray().also { array -> safeHistory.forEach(array::put) }
                val workspaceEntries = linkedSetOf<String>()
                val workspaceArray = profile.optJSONArray("workspaces") ?: JSONArray()
                for (i in 0 until workspaceArray.length()) {
                    val item = workspaceArray.getJSONObject(i)
                    if (item.optString("engine") == engine.name) {
                        workspaceEntries.add(JSONObject(item.toString()).apply { remove("engine") }.toString())
                    }
                }
                val workspaces = NovaWorkspaceStoragePolicy.parse(
                    NovaWorkspaceStoragePolicy.serialize(workspaceEntries)
                ).toMutableSet()
                val folders = profile.optJSONArray("bookmark_folders").toStringSet()
                editor.putString(key("bookmarks_v2", engine), safeBookmarks.toString())
                    .putStringSet(key("bookmarks", engine), urls)
                    .putString(key("bookmark_tombstones", engine), (profile.optJSONObject("bookmark_tombstones") ?: JSONObject()).toString())
                    .putString(key("workspace_tombstones", engine), (profile.optJSONObject("workspace_tombstones") ?: JSONObject()).toString())
                    .putLong(key("history_cleared_at", engine), clearedAt)
                    .putString(key("history_v2", engine), historyArray.toString())
                    .putStringSet(key("saved_workspaces", engine), workspaces)
                    .putStringSet(key("bookmark_folders", engine), folders)
            }
        }
        // Old unscoped snapshots are deliberately not imported: they have no trustworthy engine provenance.
        val settings = snapshot.optJSONObject("settings") ?: JSONObject()
        editor.putString("search_engine", settings.optString("search_engine", "Google"))
            .putString("shield_mode", settings.optString("shield_mode", "STANDARD"))
            .putBoolean("show_home_shortcuts", settings.optBoolean("show_home_shortcuts", true))
            .putInt("font_scale_index", settings.optInt("font_scale_index", 1))
            .putStringSet("desktop_sites", settings.optJSONArray("desktop_sites").toStringSet())
        editor.apply()
    }

    private fun readArray(raw: String?): JSONArray? = raw?.let { runCatching { JSONArray(it) }.getOrNull() }
    private fun readObject(raw: String?): JSONObject = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
    private fun JSONArray?.toStringSet(): Set<String> = this?.let { array ->
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.toSet()
    } ?: emptySet()
}
