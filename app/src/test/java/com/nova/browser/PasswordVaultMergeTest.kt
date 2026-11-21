package com.nova.browser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PasswordVaultMergeTest {
    private fun credential(updatedAt: Long) = JSONObject()
        .put("guid", "login-1")
        .put("origin", "https://example.com")
        .put("formActionOrigin", "")
        .put("httpRealm", "")
        .put("username", "alice")
        .put("password", "secret")
        .put("updatedAt", updatedAt)

    @Test
    fun newerDeleteTombstoneWinsOverOlderCredential() {
        val local = JSONObject().put("credentials", JSONArray().put(credential(10L))).put("deleted", JSONObject())
        val remote = JSONObject().put("credentials", JSONArray())
            .put("deleted", JSONObject().put("https://example.com|alice", 11L))
        assertEquals(0, PasswordVault.mergeSyncBundles(local, remote).getJSONArray("credentials").length())
    }

    @Test
    fun newerCredentialSurvivesOlderDeleteTombstone() {
        val local = JSONObject().put("credentials", JSONArray().put(credential(12L))).put("deleted", JSONObject())
        val remote = JSONObject().put("credentials", JSONArray())
            .put("deleted", JSONObject().put("https://example.com|alice", 11L))
        assertEquals(1, PasswordVault.mergeSyncBundles(local, remote).getJSONArray("credentials").length())
    }

    @Test
    fun invalidDeleteTimestampIsRejected() {
        val bundle = JSONObject().put("credentials", JSONArray())
            .put("deleted", JSONObject().put("https://example.com|alice", -1L))
        assertThrows(IllegalArgumentException::class.java) {
            PasswordVault.mergeSyncBundles(JSONObject(), bundle)
        }
    }
}
