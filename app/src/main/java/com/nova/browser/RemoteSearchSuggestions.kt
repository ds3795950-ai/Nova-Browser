package com.nova.browser

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Network suggestions are called only after the user explicitly enables them. */
internal object RemoteSearchSuggestions {
    fun fetch(query: String, engine: String): List<String> {
        if (Thread.currentThread().isInterrupted) return emptyList()
        val clean = query.trim().takeIf { it.length in 2..128 } ?: return emptyList()
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val endpoint = when (engine) {
            "Bing" -> "https://api.bing.com/osjson.aspx?query=$encoded"
            "DuckDuckGo" -> "https://ac.duckduckgo.com/ac/?q=$encoded&type=list"
            "Google" -> "https://suggestqueries.google.com/complete/search?client=firefox&q=$encoded"
            // Brave's suggestion API is not a stable public contract; do not guess an endpoint.
            else -> return emptyList()
        }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2500
            readTimeout = 2500
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "NovaBrowser/0.4")
        }
        return try {
            if (Thread.currentThread().isInterrupted) return emptyList()
            val responseCode = connection.responseCode
            if (Thread.currentThread().isInterrupted) return emptyList()
            if (responseCode != HttpURLConnection.HTTP_OK || connection.contentLengthLong > 64 * 1024) return emptyList()
            val text = connection.inputStream.bufferedReader().use { it.readText().take(64 * 1024) }
            if (Thread.currentThread().isInterrupted) return emptyList()
            val json = JSONArray(text)
            when (engine) {
                "DuckDuckGo" -> List(json.length()) { i -> json.optJSONObject(i)?.optString("phrase").orEmpty() }
                else -> json.optJSONArray(1)?.let { values -> List(values.length()) { i -> values.optString(i) } }.orEmpty()
            }.asSequence().map { it.trim() }.filter { it.isNotBlank() && it.length <= 256 }
                .distinct().take(5).toList()
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection.disconnect()
        }
    }
}
