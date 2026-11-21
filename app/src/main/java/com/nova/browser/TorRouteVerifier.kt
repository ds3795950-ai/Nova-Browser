package com.nova.browser

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/** Verifies the app's current egress with Tor Project's public check endpoint. */
internal object TorRouteVerifier {
    private const val CHECK_URL = "https://check.torproject.org/api/ip"
    private const val MAX_RESPONSE_BYTES = 4096
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "NovaTorRouteCheck").apply { isDaemon = true }
    }

    fun verify(onComplete: (Result<Boolean>) -> Unit) {
        executor.execute {
            val result = runCatching {
                val connection = (URL(CHECK_URL).openConnection() as HttpsURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Cache-Control", "no-store")
                }
                try {
                    val status = connection.responseCode
                    if (status != HttpsURLConnection.HTTP_OK) {
                        throw IOException("Tor check endpoint returned HTTP $status")
                    }
                    val body = connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(1024)
                        var total = 0
                        while (total < MAX_RESPONSE_BYTES) {
                            val count = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES - total))
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            total += count
                        }
                        if (total == 0) throw IOException("Tor check endpoint returned an empty response")
                        if (input.read() != -1) throw IOException("Tor check response exceeded the size limit")
                        String(output.toByteArray(), StandardCharsets.UTF_8)
                    }
                    val json = JSONObject(body)
                    if (!json.has("IsTor")) throw IOException("Tor check response did not include IsTor")
                    json.optBoolean("IsTor", false)
                } finally {
                    connection.disconnect()
                }
            }
            runCatching { onComplete(result) }
        }
    }

    fun parseIsTor(json: String): Boolean = runCatching {
        JSONObject(json).optBoolean("IsTor", false)
    }.getOrDefault(false)
}
