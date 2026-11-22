package com.nova.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Loads only the site's own HTTPS /favicon.ico; failed loads retain the local monogram. */
internal object ShortcutFaviconLoader {
    private const val MAX_BYTES = 512 * 1024
    private val executor = Executors.newSingleThreadExecutor()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val memory = ConcurrentHashMap<String, Bitmap>()

    internal fun faviconUrl(pageUrl: String): String? {
        val uri = runCatching { URI(pageUrl) }.getOrNull() ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT) ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme !in setOf("http", "https") || uri.rawUserInfo != null) return null
        val port = uri.port
        if (port != -1 && port !in 1..65535) return null
        // Preserve non-default HTTPS ports so the favicon request stays on the same origin.
        val portSuffix = if (scheme == "https" && port != -1 && port != 443) ":$port" else ""
        return "https://$host$portSuffix/favicon.ico"
    }

    fun load(context: Context, pageUrl: String, callback: (Bitmap?) -> Unit) {
        val favicon = faviconUrl(pageUrl) ?: return
        val key = sha256(favicon)
        memory[key]?.let { callback(it); return }
        executor.execute {
            val file = File(File(context.applicationContext.cacheDir, "shortcut-favicons"), "$key.png")
            val bitmap = memory[key] ?: runCatching {
                if (file.isFile) BitmapFactory.decodeFile(file.absolutePath) else null
            }.getOrNull() ?: download(favicon)?.also { decoded ->
                runCatching {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { decoded.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
            if (bitmap != null) memory[key] = bitmap
            main.post { callback(bitmap) }
        }
    }

    private fun download(address: String): Bitmap? {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3500
            readTimeout = 3500
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("Accept", "image/*")
            setRequestProperty("User-Agent", "NovaBrowser/0.4")
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val type = connection.contentType
            if (type != null && !type.startsWith("image/", ignoreCase = true)) return null
            if (connection.contentLengthLong > MAX_BYTES) return null
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_BYTES) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 128 || bounds.outHeight / sample > 128) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
