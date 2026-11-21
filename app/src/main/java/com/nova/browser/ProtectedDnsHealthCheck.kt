package com.nova.browser

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/** Tests AdGuard's DoH endpoint reachability; it does not claim to inspect GeckoView's live resolver. */
internal object ProtectedDnsHealthCheck {
    private const val ENDPOINT = "https://dns.adguard-dns.com/dns-query"
    private const val QUERY_ID = 0x4e56
    private const val MAX_RESPONSE_BYTES = 4096
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "NovaDnsHealthCheck").apply { isDaemon = true }
    }

    fun check(onComplete: (Result<Boolean>) -> Unit) {
        executor.execute {
            val result = runCatching {
                val encodedQuery = Base64.getUrlEncoder().withoutPadding().encodeToString(buildDnsQuery())
                val connection = (URL("$ENDPOINT?dns=$encodedQuery").openConnection() as HttpsURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("Accept", "application/dns-message")
                    setRequestProperty("Cache-Control", "no-store")
                }
                try {
                    val status = connection.responseCode
                    if (status != HttpsURLConnection.HTTP_OK) throw IOException("AdGuard DoH returned HTTP $status")
                    val contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
                    if (contentType != "application/dns-message") throw IOException("AdGuard returned an unexpected DNS response type")
                    val response = connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(1024)
                        var total = 0
                        while (total < MAX_RESPONSE_BYTES) {
                            val count = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES - total))
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            total += count
                        }
                        if (total == 0) throw IOException("AdGuard returned an empty DNS response")
                        if (input.read() != -1) throw IOException("AdGuard DNS response exceeded the size limit")
                        output.toByteArray()
                    }
                    if (!isValidDnsResponse(response)) throw IOException("AdGuard DNS response was invalid or contained no answer")
                    true
                } finally {
                    connection.disconnect()
                }
            }
            runCatching { onComplete(result) }
        }
    }

    internal fun buildDnsQuery(): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { dns ->
            dns.writeShort(QUERY_ID)
            dns.writeShort(0x0100) // standard recursive query
            dns.writeShort(1) // one question
            dns.writeShort(0)
            dns.writeShort(0)
            dns.writeShort(0)
            dns.writeByte(7); dns.write("example".toByteArray(StandardCharsets.US_ASCII))
            dns.writeByte(3); dns.write("com".toByteArray(StandardCharsets.US_ASCII))
            dns.writeByte(0)
            dns.writeShort(1) // A record
            dns.writeShort(1) // IN class
        }
        return output.toByteArray()
    }

    internal fun isValidDnsResponse(response: ByteArray): Boolean {
        if (response.size < 12) return false
        val id = ((response[0].toInt() and 0xff) shl 8) or (response[1].toInt() and 0xff)
        val flags = ((response[2].toInt() and 0xff) shl 8) or (response[3].toInt() and 0xff)
        val answerCount = ((response[6].toInt() and 0xff) shl 8) or (response[7].toInt() and 0xff)
        return id == QUERY_ID && (flags and 0x8000) != 0 && (flags and 0x000f) == 0 && answerCount > 0
    }
}
