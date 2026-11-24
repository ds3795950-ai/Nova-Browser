package com.nova.browser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NovaWorkspaceStoragePolicyTest {
    private fun workspace(name: String = "Work") = JSONObject().put("name", name)
        .put("createdAt", 10L)
        .put("tabs", JSONArray().put(JSONObject().put("url", "https://example.com")
            .put("title", "Example").put("group", "Research"))).toString()

    @Test fun rejectsUnsafeWorkspaceUrls() {
        assertThrows(IllegalArgumentException::class.java) {
            NovaWorkspaceStoragePolicy.serialize(setOf(JSONObject().put("name", "Bad")
                .put("tabs", JSONArray().put(JSONObject().put("url", "javascript:alert(1)"))).toString()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NovaWorkspaceStoragePolicy.serialize(setOf(JSONObject().put("name", "Bad")
                .put("tabs", JSONArray().put(JSONObject().put("url", "file:///private/data"))).toString()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NovaWorkspaceStoragePolicy.serialize(setOf(JSONObject().put("name", "Bad")
                .put("tabs", JSONArray().put(JSONObject().put("url", "https://user:pass@example.com"))).toString()))
        }
    }

    @Test fun allowsBlankAndWebWorkspaceUrls() {
        val value = JSONObject().put("name", "Safe").put("tabs", JSONArray()
            .put(JSONObject().put("url", "about:blank"))
            .put(JSONObject().put("url", "https://example.com"))
            .put(JSONObject().put("url", "http://localhost:3000"))).toString()
        assertEquals(setOf(value), NovaWorkspaceStoragePolicy.parse(NovaWorkspaceStoragePolicy.serialize(setOf(value))))
    }

    @Test fun workspaceSetSerializesAndParsesDeterministically() {
        val values = linkedSetOf(workspace("Second"), workspace("First"))
        val serialized = NovaWorkspaceStoragePolicy.serialize(values)
        assertEquals(values, NovaWorkspaceStoragePolicy.parse(serialized))
    }

    @Test fun rejectsOversizedAndMalformedWorkspaceEntries() {
        assertThrows(IllegalArgumentException::class.java) {
            NovaWorkspaceStoragePolicy.serialize((0..NovaWorkspaceStoragePolicy.MAX_WORKSPACES).map { workspace("W$it") }.toSet())
        }
        assertThrows(IllegalArgumentException::class.java) { NovaWorkspaceStoragePolicy.serialize(setOf("not-json")) }
        assertThrows(IllegalArgumentException::class.java) { NovaWorkspaceStoragePolicy.parse("not-json") }
        assertThrows(IllegalArgumentException::class.java) { NovaWorkspaceStoragePolicy.parse("[1]") }
        assertThrows(IllegalArgumentException::class.java) {
            NovaWorkspaceStoragePolicy.serialize(setOf(JSONObject().put("name", "Work").put("createdAt", -1L)
                .put("tabs", JSONArray()).toString()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NovaWorkspaceStoragePolicy.serialize(setOf(JSONObject().put("name", "Work").put("createdAt", Long.MAX_VALUE)
                .put("tabs", JSONArray()).toString()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NovaWorkspaceStoragePolicy.serialize(setOf(JSONObject().put("name", "Work").put("tabs", JSONArray().put(JSONObject())).toString()))
        }
    }

    @Test fun rejectsInvalidWorkspaceTombstones() {
        assertThrows(IllegalArgumentException::class.java) { NovaWorkspaceStoragePolicy.validateTombstones("not-json") }
        assertThrows(IllegalArgumentException::class.java) { NovaWorkspaceStoragePolicy.validateTombstones(JSONObject().put("Work", 0L).toString()) }
        assertThrows(IllegalArgumentException::class.java) { NovaWorkspaceStoragePolicy.validateTombstones(JSONObject().put("Work", -1L).toString()) }
        assertThrows(IllegalArgumentException::class.java) { NovaWorkspaceStoragePolicy.validateTombstones(JSONObject().put("Work", Long.MAX_VALUE).toString()) }
        assertEquals(JSONObject().put("Work", 123L).toString(), NovaWorkspaceStoragePolicy.validateTombstones(JSONObject().put("Work", 123L).toString()))
    }

    @Test fun acceptsBracketedPathAndPipeInQuery() {
        val entry = JSONObject(workspace()).put("engine", "GECKOVIEW")
        entry.getJSONArray("tabs").getJSONObject(0).put("url", "https://example.com/search?filter[type]=news|tech")
        assertEquals(1, NovaWorkspaceStoragePolicy.parse(NovaWorkspaceStoragePolicy.serialize(setOf(entry.toString()))).size)
    }

    @Test fun acceptsLongOAuthUrlsWithinWorkspaceLimit() {
        val entry = JSONObject(workspace()).put("engine", "GECKOVIEW")
        entry.getJSONArray("tabs").getJSONObject(0).put("url", "https://example.com/callback?state=" + "x".repeat(4096))
        assertEquals(1, NovaWorkspaceStoragePolicy.parse(NovaWorkspaceStoragePolicy.serialize(setOf(entry.toString()))).size)
    }
}
