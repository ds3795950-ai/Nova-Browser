package com.nova.browser

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Network suggestions are called only after the user explicitly enables them. */
internal object RemoteSearchSuggestions {
    class Cancellation {
        @Volatile private var cancelled = false
        @Volatile private var connection: HttpURLConnection? = null

        fun isCancelled(): Boolean = cancelled || Thread.currentThread().isInterrupted

        fun cancel() {
            cancelled = true
            connection?.disconnect()
        }

        fun attach(value: HttpURLConnection) {
            connection = value
            if (isCancelled()) value.disconnect()
        }

        fun clear(value: HttpURLConnection) {
            if (connection === value) connection = null
        }
    }

    fun fetch(
        query: String,
        engine: String,
        cancellation: Cancellation = Cancellation(),
        isEligible: () -> Boolean = { true }
    ): List<String> {
        fun mayContinue() = !cancellation.isCancelled() && isEligible()
        if (!mayContinue()) return emptyList()
        val clean = query.trim().takeIf { it.length in 2..128 } ?: return emptyList()
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val endpoint = when (engine) {
            "Bing" -> "https://api.bing.com/osjson.aspx?query=$encoded"
            "DuckDuckGo" -> "https://ac.duckduckgo.com/ac/?q=$encoded&type=list"
            "Google" -> "https://suggestqueries.google.com/complete/search?client=firefox&q=$encoded"
            // Brave's suggestion API is not a stable public contract; do not guess an endpoint.
            else -> return emptyList()
        }
        if (!mayContinue()) return emptyList()
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2500
            readTimeout = 2500
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "NovaBrowser/0.4")
        }
        cancellation.attach(connection)
        return try {
            if (!mayContinue()) return emptyList()
            val responseCode = connection.responseCode
            if (!mayContinue()) return emptyList()
            if (responseCode != HttpURLConnection.HTTP_OK || connection.contentLengthLong > 64 * 1024) return emptyList()
            val text = connection.inputStream.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = stream.read(buffer, 0, minOf(buffer.size, 64 * 1024 + 1 - total))
                    if (!mayContinue()) return emptyList()
                    if (count < 0) break
                    total += count
                    if (total > 64 * 1024) throw IllegalStateException("Search suggestion response is too large")
                    output.write(buffer, 0, count)
                }
                String(output.toByteArray(), Charsets.UTF_8)
            }
            if (!mayContinue()) return emptyList()
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
            cancellation.clear(connection)
        }
    }
}
