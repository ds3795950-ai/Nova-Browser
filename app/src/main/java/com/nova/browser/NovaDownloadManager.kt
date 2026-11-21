package com.nova.browser

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** App-controlled HTTP downloader with resumable Range requests and persisted partial files. */
class NovaDownloadManager(
    private val context: Context,
    private val changed: (List<Item>) -> Unit
) {
    data class Item(
        val id: String,
        val url: String,
        val fileName: String,
        val mimeType: String,
        val downloaded: Long,
        val total: Long,
        val state: String,
        val etag: String = "",
        val lastModified: String = "",
        val savedUri: String = "",
        val error: String = "",
        val originLabel: String = ""
    ) {
        fun progressLabel(): String = if (total > 0) "${(downloaded.toDouble() * 100.0 / total).toInt().coerceIn(0, 100)}% Â· ${formatBytes(downloaded)} / ${formatBytes(total)}" else "${formatBytes(downloaded)} downloaded"
    }

    private data class Running(
        @Volatile var item: Item,
        val pause: AtomicBoolean = AtomicBoolean(false),
        val cancel: AtomicBoolean = AtomicBoolean(false),
        @Volatile var connection: HttpURLConnection? = null,
        @Volatile var input: InputStream? = null
    )

    private val prefs = context.getSharedPreferences("nova_downloads", Context.MODE_PRIVATE)
    @Volatile private var changedListener: ((List<Item>) -> Unit)? = changed
    private val executor = Executors.newFixedThreadPool(MAX_CONCURRENT_DOWNLOADS)
    private val running = ConcurrentHashMap<String, Running>()
    private val records = LinkedHashMap<String, Item>()

    init {
        loadRecords()
        cleanupOrphanPartials()
    }

    fun shutdown() {
        changedListener = null
        executor.shutdownNow()
    }

    @Synchronized fun items(): List<Item> = records.values.toList().reversed()

    @Synchronized private fun put(item: Item) {
        records[item.id] = item
        persist()
        changedListener?.invoke(items())
        updateServiceNotification()
    }

    private fun updateServiceNotification() {
        val active = running.values.firstOrNull { it.item.state in setOf("RUNNING", "SAVING", "FINALIZING") }?.item
        NovaDownloadService.update(context, running.size, active?.fileName, active?.downloaded ?: 0L, active?.total ?: 0L, active?.id)
    }

    @Synchronized private fun persist() {
        val array = JSONArray()
        records.values.forEach { item ->
            // Never persist full download URLs: query strings and userinfo may contain bearer tokens.
            array.put(JSONObject().put("id", item.id).put("url", "")
                .put("originLabel", item.originLabel.ifBlank { DownloadProtocol.hostLabel(item.url) })
                .put("fileName", item.fileName).put("mimeType", item.mimeType)
                .put("downloaded", item.downloaded).put("total", item.total)
                .put("state", item.state).put("etag", item.etag).put("lastModified", item.lastModified)
                .put("savedUri", item.savedUri).put("error", item.error))
        }
        prefs.edit().putString("items", array.toString()).apply()
    }

    @Synchronized private fun loadRecords() {
        val raw = prefs.getString("items", null) ?: return
        val array = runCatching { JSONArray(raw) }.getOrElse {
            if (!prefs.contains("items_corrupt_backup")) prefs.edit().putString("items_corrupt_backup", raw).apply()
            return
        }
        var hadBadItem = false
        for (i in 0 until array.length()) {
            val item = runCatching {
                val o = array.getJSONObject(i)
                Item(o.getString("id"), "", o.getString("fileName"),
                    o.optString("mimeType", "application/octet-stream"), o.optLong("downloaded"),
                    o.optLong("total"), o.optString("state", "PAUSED"), o.optString("etag"),
                    o.optString("lastModified"), o.optString("savedUri"), o.optString("error"),
                    o.optString("originLabel"))
            }.getOrNull()
            if (item == null) { hadBadItem = true; continue }
            val restored = when (item.state) {
                "RUNNING", "QUEUED", "SAVING", "FINALIZING" -> item.copy(
                    state = "INTERRUPTED",
                    error = "Download URL is intentionally not stored. Start it again from the original page."
                )
                else -> item
            }
            records[restored.id] = restored
        }
        if (hadBadItem && !prefs.contains("items_corrupt_backup")) {
            prefs.edit().putString("items_corrupt_backup", raw).apply()
        }
    }

    @Synchronized fun enqueue(url: String, fileName: String? = null, mimeType: String = "application/octet-stream"): String {
        val parsed = URL(url)
        require(parsed.protocol == "https") { "Nova's downloader requires HTTPS. HTTP downloads are blocked by the app's cleartext security policy." }
        running.values.firstOrNull { it.item.url == url && it.item.state in setOf("QUEUED", "RUNNING", "SAVING", "FINALIZING") }?.let {
            return it.item.id
        }
        val name = sanitize(fileName ?: URLDecoder.decode(parsed.path.substringAfterLast('/').ifBlank { "nova-download.bin" }, "UTF-8"))
        val id = UUID.randomUUID().toString()
        put(Item(id, url, name, mimeType, 0, 0, "QUEUED"))
        resume(id)
        return id
    }

    /** Restarts only transfers that were interrupted by process death; user-paused items stay paused. */
    fun resumeInterrupted() {
        val pending = synchronized(this) { records.values.filter { it.state == "INTERRUPTED" || it.state == "QUEUED" }.map { it.id } }
        pending.forEach(::resume)
    }

    fun resume(id: String) {
        val item = synchronized(this) { records[id] } ?: return
        if (item.state == "COMPLETE" || running.containsKey(id)) return
        if (item.url.isBlank()) {
            put(item.copy(state = "INTERRUPTED", error = "This browser response cannot be resumed. Download it again from the page."))
            return
        }
        val worker = Running(item.copy(state = "RUNNING", error = ""))
        if (running.putIfAbsent(id, worker) != null) return
        put(worker.item)
        executor.execute { transfer(worker) }
    }

    fun pause(id: String) {
        val worker = running[id]
        if (worker != null) {
            worker.pause.set(true)
            worker.connection?.disconnect()
        } else {
            val item = synchronized(this) { records[id] } ?: return
            if (item.state in setOf("QUEUED", "FAILED", "INTERRUPTED")) put(item.copy(state = "PAUSED"))
        }
    }

    fun cancel(id: String) {
        val worker = running[id]
        worker?.cancel?.set(true)
        worker?.connection?.disconnect()
        runCatching { worker?.input?.close() }
        if (worker == null) partial(id).delete()
        synchronized(this) { records.remove(id); persist(); changedListener?.invoke(items()) }
        updateServiceNotification()
    }

    private fun openConnection(worker: Running, offset: Long): HttpURLConnection {
        var target = URL(worker.item.url)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            if (worker.cancel.get() || worker.pause.get()) throw java.io.IOException("Download interrupted")
            require(target.protocol == "http" || target.protocol == "https") { "Only HTTP(S) redirects are supported" }
            val connection = target.openConnection() as HttpURLConnection
            worker.connection = connection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "NovaBrowser/0.2 GeckoView")
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) {
                connection.setRequestProperty("Range", "bytes=$offset-")
                val validator = worker.item.etag.ifBlank { worker.item.lastModified }
                if (validator.isNotBlank()) connection.setRequestProperty("If-Range", validator)
            }
            connection.connect()
            val code = connection.responseCode
            if (code !in 300..399) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (redirectCount == MAX_REDIRECTS) throw java.io.IOException("Too many redirects")
            if (location.isNullOrBlank()) throw java.io.IOException("Redirect response did not include a destination")
            val redirected = URL(target, location)
            if (!DownloadProtocol.isRedirectAllowed(target.toExternalForm(), redirected.toExternalForm())) {
                throw java.io.IOException("Blocked an insecure or unsupported download redirect")
            }
            target = redirected
        }
        throw java.io.IOException("Too many redirects")
    }

    private fun ensureAvailableSpace(part: File, bytesNeeded: Long) {
        val available = part.parentFile?.usableSpace ?: context.filesDir.usableSpace
        val required = bytesNeeded.coerceAtLeast(0L) + MIN_FREE_SPACE_BYTES
        if (available < required) throw java.io.IOException("Not enough free storage space to continue this download")
    }

    private fun transfer(worker: Running) {
        val id = worker.item.id
        try {
            val part = partial(id)
            part.parentFile?.mkdirs()
            var offset = part.length()
            var connection = openConnection(worker, offset)
            var code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("Server returned HTTP $code")
            val contentRange = DownloadProtocol.parseContentRange(connection.getHeaderField("Content-Range"))
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                if (contentRange == null || contentRange.start != offset) {
                    throw IllegalStateException("Server returned an invalid byte range; partial file was preserved")
                }
            } else if (offset > 0) {
                part.delete()
                offset = 0
            }
            val etag = connection.getHeaderField("ETag") ?: worker.item.etag
            val modified = connection.getHeaderField("Last-Modified") ?: worker.item.lastModified
            val length = connection.getHeaderFieldLong("Content-Length", -1L)
            val total = when {
                contentRange?.total != null -> contentRange.total
                length >= 0 -> offset + length
                else -> worker.item.total
            }
            worker.item = worker.item.copy(downloaded = offset, total = total, state = "RUNNING", etag = etag, lastModified = modified)
            if (total > 0) ensureAvailableSpace(part, (total - offset).coerceAtLeast(0))
            put(worker.item)
            connection.inputStream.use { input ->
                FileOutputStream(part, offset > 0).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var lastUpdate = System.currentTimeMillis()
                    while (true) {
                        if (worker.cancel.get() || worker.pause.get()) break
                        val count = input.read(buffer)
                        if (count < 0) break
                        ensureAvailableSpace(part, count.toLong())
                        output.write(buffer, 0, count)
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 500) {
                            worker.item = worker.item.copy(downloaded = part.length())
                            put(worker.item)
                            lastUpdate = now
                        }
                    }
                    output.fd.sync()
                }
            }
            connection.disconnect()
            worker.connection = null
            if (worker.cancel.get()) return
            if (worker.pause.get()) {
                worker.item = worker.item.copy(downloaded = part.length(), state = "PAUSED")
                put(worker.item)
            } else {
                if (worker.item.total > 0 && part.length() != worker.item.total) {
                    throw IllegalStateException("Download incomplete: received ${part.length()} of ${worker.item.total} bytes")
                }
                worker.item = worker.item.copy(downloaded = part.length(), state = "FINALIZING")
                put(worker.item)
                val uri = publish(part, worker.item.fileName, worker.item.mimeType)
                worker.item = worker.item.copy(state = "COMPLETE", savedUri = uri.toString(), downloaded = part.length(), error = "")
                put(worker.item)
                NovaDownloadService.notifyResult(context, worker.item)
                part.delete()
            }
        } catch (error: Exception) {
            val part = partial(id)
            if (worker.pause.get()) {
                worker.item = worker.item.copy(downloaded = part.length(), state = "PAUSED")
            } else if (!worker.cancel.get()) {
                worker.item = worker.item.copy(downloaded = part.length(), state = "FAILED", error = error.message ?: "Download failed")
            }
            if (!worker.cancel.get()) {
                put(worker.item)
                if (worker.item.state == "FAILED") NovaDownloadService.notifyResult(context, worker.item)
            }
        } finally {
            worker.connection?.disconnect()
            if (worker.cancel.get()) partial(id).delete()
            running.remove(id)
            updateServiceNotification()
        }
    }

    fun saveExternalStream(stream: InputStream, fileName: String, mimeType: String, contentLength: Long = -1L) {
        val id = UUID.randomUUID().toString()
        val safeName = sanitize(fileName)
        val resolvedMime = resolveMimeType(safeName, mimeType)
        val total = contentLength.takeIf { it > 0L } ?: 0L
        val item = Item(id, "", safeName, resolvedMime, 0, total, "SAVING")
        val worker = Running(item, input = stream)
        running[id] = worker
        put(item)
        executor.execute {
            val part = partial(id)
            try {
                part.parentFile?.mkdirs()
                stream.use { input ->
                    FileOutputStream(part, false).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var lastUpdate = System.currentTimeMillis()
                        while (!worker.cancel.get()) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            ensureAvailableSpace(part, count.toLong())
                            output.write(buffer, 0, count)
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate >= 500) {
                                worker.item = worker.item.copy(downloaded = part.length())
                                put(worker.item)
                                lastUpdate = now
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (worker.cancel.get()) {
                    synchronized(this) { records.remove(id); persist(); changedListener?.invoke(items()) }
                } else {
                    if (worker.item.total > 0 && part.length() != worker.item.total) {
                        throw IllegalStateException("Browser download incomplete: received ${part.length()} of ${worker.item.total} bytes")
                    }
                    val uri = publish(part, item.fileName, item.mimeType)
                    worker.item = worker.item.copy(downloaded = part.length(), state = "COMPLETE", savedUri = uri.toString())
                    put(worker.item)
                    NovaDownloadService.notifyResult(context, worker.item)
                }
            } catch (error: Exception) {
                runCatching { stream.close() }
                if (!worker.cancel.get()) {
                    worker.item = worker.item.copy(
                        state = "FAILED",
                        error = error.message ?: "Could not save browser download"
                    )
                    put(worker.item)
                    if (worker.item.state == "FAILED") NovaDownloadService.notifyResult(context, worker.item)
                }
            } finally {
                runCatching { stream.close() }
                part.delete()
                running.remove(id)
                updateServiceNotification()
            }
        }
    }

    private fun partial(id: String) = File(File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "nova-partials"), "$id.part")

    @Synchronized private fun cleanupOrphanPartials() {
        val directory = partial("placeholder").parentFile ?: return
        val knownIds = records.keys
        directory.listFiles()?.filter { it.isFile && it.name.endsWith(".part") }?.forEach { file ->
            val id = file.name.removeSuffix(".part")
            if (id !in knownIds) file.delete()
        }
    }

    private fun resolveMimeType(fileName: String, fallback: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: fallback
    }

    private fun publish(file: File, name: String, mime: String): Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Nova")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create Downloads file")
            try {
                context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: throw IllegalStateException("Could not write Downloads file")
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                return uri
            } catch (error: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw error
            }
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val target = File(dir, name)
        file.copyTo(target, overwrite = true)
        return Uri.fromFile(target)
    }

    private fun sanitize(value: String): String = DownloadProtocol.safeFileName(value)

    companion object {
        private const val MAX_CONCURRENT_DOWNLOADS = 3
        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MIN_FREE_SPACE_BYTES = 1L * 1024 * 1024

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
