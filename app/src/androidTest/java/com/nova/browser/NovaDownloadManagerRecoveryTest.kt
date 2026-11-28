package com.nova.browser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NovaDownloadManagerRecoveryTest {
    @Test
    fun processRestartMarksActiveTransfersInterruptedButKeepsUserPausedItemsPaused() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("nova_downloads_cefrium", Context.MODE_PRIVATE)
        val records = JSONArray()
            .put(record("running-transfer", "RUNNING"))
            .put(record("finalizing-transfer", "FINALIZING"))
            .put(record("user-paused-transfer", "PAUSED"))
        preferences.edit().putString("items", records.toString()).commit()

        try {
            // Constructing a new manager simulates process recreation and record restoration.
            val restored = NovaDownloadManager(context, "cefrium", null).items().associateBy { it.id }

            assertEquals("INTERRUPTED", restored["running-transfer"]?.state)
            assertEquals("INTERRUPTED", restored["finalizing-transfer"]?.state)
            assertEquals("PAUSED", restored["user-paused-transfer"]?.state)
            assertEquals(2, restored.values.count { it.state == "INTERRUPTED" })
        } finally {
            preferences.edit().clear().commit()
        }
    }

    private fun record(id: String, state: String): JSONObject = JSONObject()
        .put("id", id)
        .put("url", "https://downloads.example.test/file.bin")
        .put("fileName", "file.bin")
        .put("mimeType", "application/octet-stream")
        .put("downloaded", 128L)
        .put("total", 512L)
        .put("state", state)
        .put("etag", "\"stable-etag\"")
        .put("lastModified", "")
        .put("savedUri", "")
        .put("error", "")
        .put("resourceUrl", "https://downloads.example.test/file.bin")
}
