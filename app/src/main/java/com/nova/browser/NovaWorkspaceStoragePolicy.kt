package com.nova.browser

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Bounds and validates workspace data before it is encrypted or restored from cloud sync. */
internal object NovaWorkspaceStoragePolicy {
    const val MAX_WORKSPACES = 100
    const val MAX_WORKSPACE_CHARS = 512 * 1024
    const val MAX_TOTAL_WORKSPACE_CHARS = 2 * 1024 * 1024
    const val MAX_WORKSPACE_TABS = 100
    const val MAX_NAME_LENGTH = 128
    const val MAX_TITLE_LENGTH = 512
    const val MAX_URL_LENGTH = 2048

    fun serialize(workspaces: Set<String>): String {
        require(workspaces.size <= MAX_WORKSPACES) { "Too many saved workspaces" }
        require(workspaces.sumOf { it.length.toLong() } <= MAX_TOTAL_WORKSPACE_CHARS.toLong()) {
            "Saved workspace data is too large"
        }
        val output = JSONArray()
        workspaces.sorted().forEach { raw ->
            validateWorkspace(raw)
            output.put(raw)
        }
        return output.toString().also { require(it.length <= MAX_TOTAL_WORKSPACE_CHARS) { "Saved workspace data is too large" } }
    }

    fun parse(serialized: String): Set<String> {
        require(serialized.length <= MAX_TOTAL_WORKSPACE_CHARS) { "Saved workspace data is too large" }
        val array = try {
            JSONArray(serialized)
        } catch (error: JSONException) {
            throw IllegalArgumentException("Saved workspace list is malformed", error)
        }
        require(array.length() <= MAX_WORKSPACES) { "Too many saved workspaces" }
        val result = linkedSetOf<String>()
        for (i in 0 until array.length()) {
            val raw = try {
                array.getString(i)
            } catch (error: JSONException) {
                throw IllegalArgumentException("Saved workspace entry is malformed", error)
            }
            result.add(raw)
        }
        serialize(result)
        return result
    }

    fun validateTombstones(serialized: String): String {
        val tombstones = try {
            JSONObject(serialized)
        } catch (error: JSONException) {
            throw IllegalArgumentException("Workspace deletion history is malformed", error)
        }
        require(tombstones.length() <= 4000) { "Workspace deletion history is too large" }
        val keys = tombstones.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val timestamp = tombstones.opt(key)
            require(key.isNotBlank() && key.length <= MAX_NAME_LENGTH && timestamp is Number &&
                timestamp.toLong() in 1 until Long.MAX_VALUE && timestamp.toDouble() == timestamp.toLong().toDouble()) {
                "Invalid workspace deletion marker"
            }
        }
        return tombstones.toString()
    }

    private fun validateWorkspace(raw: String) {
        require(raw.isNotBlank() && raw.length <= MAX_WORKSPACE_CHARS) { "Saved workspace is too large or empty" }
        val workspace = try {
            JSONObject(raw)
        } catch (error: JSONException) {
            throw IllegalArgumentException("Saved workspace is malformed", error)
        }
        val name = workspace.opt("name")
        require(name is String && name.isNotBlank() && name.length <= MAX_NAME_LENGTH) {
            "Saved workspace has an invalid name"
        }
        val createdAt = workspace.opt("createdAt")
        require(createdAt == null || createdAt is Number && createdAt.toLong() in 0 until Long.MAX_VALUE &&
            createdAt.toDouble() == createdAt.toLong().toDouble()) { "Saved workspace has an invalid creation timestamp" }
        val tabs = workspace.optJSONArray("tabs") ?: throw IllegalArgumentException("Saved workspace has no tab list")
        require(tabs.length() <= MAX_WORKSPACE_TABS) { "Saved workspace contains too many tabs" }
        for (i in 0 until tabs.length()) {
            val tab = tabs.optJSONObject(i) ?: throw IllegalArgumentException("Saved workspace contains an invalid tab")
            val url = tab.opt("url")
            val title = tab.opt("title")
            val group = tab.opt("group")
            require(url is String && url.length <= MAX_URL_LENGTH) { "Saved workspace contains an invalid URL" }
            require(title == null || title is String && title.length <= MAX_TITLE_LENGTH) { "Saved workspace contains an oversized title" }
            require(group == null || group is String && group.length <= MAX_NAME_LENGTH) { "Saved workspace contains an oversized tab group" }
        }
    }
}
