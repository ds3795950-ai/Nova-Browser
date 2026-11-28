package com.nova.browser

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** App-controlled HTTP downloader with resumable Range requests and persisted partial files. */
class NovaDownloadManager(
    private val context: Context,
    storageNamespace: String,
    @Volatile private var changed: ((List<Item>) -> Unit)?
) {
    private val storageNamespace = storageNamespace.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it == '-' || it == '_' }.also { require(it.isNotBlank()) { "A non-empty engine storage namespace is required" } }
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
        val resourceUrl: String = ""
    ) {
        fun progressLabel(): String = if (total > 0) "${(downloaded * 100 / total).coerceIn(0, 100)}% · ${formatBytes(downloaded)} / ${formatBytes(total)}" else "${formatBytes(downloaded)} downloaded"
    }

    private data class Running(
        @Volatile var item: Item,
        val pause: AtomicBoolean = AtomicBoolean(false),
        val cancel: AtomicBoolean = AtomicBoolean(false),
        @Volatile var connection: HttpURLConnection? = null,
        @Volatile var input: InputStream? = null
    )

    private val prefs = context.getSharedPreferences("nova_downloads_${storageNamespace}", Context.MODE_PRIVATE)
    private val executor = Executors.newCachedThreadPool()
    private val running = ConcurrentHashMap<String, Running>()
    private val records = LinkedHashMap<String, Item>()

    init { loadRecords() }

    fun setOnChangedListener(listener: ((List<Item>) -> Unit)?) { changed = listener }

    @Synchronized fun items(): List<Item> = records.values.toList().reversed()

    @Synchronized private fun put(item: Item) {
        records[item.id] = item
        persist()
        changed?.invoke(items())
        NovaDownloadService.update(context, running.size)
    }

    @Synchronized private fun persist() {
        val array = JSONArray()
        records.values.forEach { item ->
            array.put(JSONObject().put("id", item.id).put("url", item.url).put("fileName", item.fileName)
                .put("mimeType", item.mimeType).put("downloaded", item.downloaded).put("total", item.total)
                .put("state", item.state).put("etag", item.etag).put("lastModified", item.lastModified)
                .put("savedUri", item.savedUri).put("error", item.error).put("resourceUrl", item.resourceUrl))
        }
        prefs.edit().putString("items", array.toString()).apply()
    }

    @Synchronized private fun loadRecords() {
        val raw = prefs.getString("items", null) ?: return
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val item = Item(o.getString("id"), o.getString("url"), o.getString("fileName"),
                    o.optString("mimeType", "application/octet-stream"), o.optLong("downloaded"),
                    o.optLong("total"), o.optString("state", "PAUSED"), o.optString("etag"),
                    o.optString("lastModified"), o.optString("savedUri"), o.optString("error"), o.optString("resourceUrl"))
                records[item.id] = when (item.state) {
                    "RUNNING" -> item.copy(state = "INTERRUPTED", error = "Download was interrupted by Android. It will resume when Nova starts.")
                    "SAVING", "FINALIZING" -> item.copy(state = "INTERRUPTED", error = "The final save was interrupted. Resume to retry the transfer.")
                    else -> item
                }
            }
        }
    }

    /** Records a file already downloaded by a browser engine and published to MediaStore. */
    fun registerCompleted(url: String, fileName: String, mimeType: String, savedUri: String, bytes: Long): String {
        val parsedUrl = runCatching { URL(url) }.getOrNull()
        val safeUrl = parsedUrl?.takeIf { it.protocol == "http" || it.protocol == "https" }?.toString().orEmpty()
        val uri = Uri.parse(savedUri)
        require(uri.scheme == "content") { "A published Android Downloads URI is required" }
        val id = "engine-${UUID.randomUUID()}"
        put(Item(
            id = id,
            url = safeUrl.take(8192),
            fileName = sanitize(fileName).ifBlank { "nova-download.bin" },
            mimeType = mimeType.take(128).ifBlank { "application/octet-stream" },
            downloaded = bytes.coerceAtLeast(0L),
            total = bytes.coerceAtLeast(0L),
            state = "COMPLETE",
            savedUri = uri.toString()
        ))
        return id
    }

    fun enqueue(url: String, fileName: String? = null, mimeType: String = "application/octet-stream"): String {
        val parsed = URL(url)
        require(parsed.protocol == "http" || parsed.protocol == "https") { "Only HTTP(S) downloads are supported" }
        val name = sanitize(fileName ?: DownloadProtocol.fileNameFromResponse(url, null).ifBlank { "nova-download.bin" })
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
            if (item.state == "QUEUED" || item.state == "FAILED") put(item.copy(state = "PAUSED"))
        }
    }

    fun cancel(id: String) {
        val worker = running[id]
        worker?.cancel?.set(true)
        worker?.connection?.disconnect()
        runCatching { worker?.input?.close() }
        if (worker == null) partial(id).delete()
        synchronized(this) { records.remove(id); persist(); changed?.invoke(items()) }
        NovaDownloadService.update(context, running.size)
    }

    private fun transfer(worker: Running) {
        val id = worker.item.id
        try {
            val part = partial(id)
            part.parentFile?.mkdirs()
            var offset = part.length()
            // If-Range needs a strong ETag or a Last-Modified date; otherwise a partial file cannot be validated.
            // If an ETag exists but is weak, do not silently fall back to Last-Modified
            // for If-Range; restart rather than risk appending bytes from another entity.
            // Resume only when the server supplied a strong entity tag.
            // Last-Modified is not sufficient to guarantee byte-for-byte identity.
            val validator = worker.item.etag.takeIf {
                it.isNotBlank() && !it.startsWith("W/", ignoreCase = true)
            }
            if (offset > 0 && (validator == null || worker.item.resourceUrl.isBlank())) { part.delete(); offset = 0 }
            var connection = openConnection(worker, offset, validator)
            var restartedFromZero = false
            val finalResourceUrl = connection.url.toString()
            if (offset > 0 && (finalResourceUrl.length >= 8192 || finalResourceUrl != worker.item.resourceUrl)) {
                connection.disconnect()
                part.delete()
                offset = 0
                restartedFromZero = true
                connection = openConnection(worker, 0, null)
            }
            var code = connection.responseCode
            if (code == 416 && offset > 0) {
                // The partial file is already complete or no longer matches the server copy: restart cleanly.
                connection.disconnect()
                part.delete()
                offset = 0
                restartedFromZero = true
                connection = openConnection(worker, 0, null)
                code = connection.responseCode
            }
            if (code !in 200..299) throw IllegalStateException("Server returned HTTP $code")
            val contentRange = DownloadProtocol.parseContentRange(connection.getHeaderField("Content-Range"))
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                if (contentRange == null || contentRange.start != offset) {
                    throw IllegalStateException("Server returned an invalid byte range; partial file was preserved")
                }
            } else if (offset > 0) {
                part.delete()
                offset = 0
                restartedFromZero = true
            }
            val etag = connection.getHeaderField("ETag") ?: if (restartedFromZero || offset == 0L) null else worker.item.etag
            val modified = connection.getHeaderField("Last-Modified") ?: if (restartedFromZero || offset == 0L) null else worker.item.lastModified
            val length = connection.getHeaderFieldLong("Content-Length", -1L)
            if (length > 0L) {
                val availableBytes = runCatching { StatFs(part.parentFile!!.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
                val requiredBytes = length + 8L * 1024L * 1024L
                if (availableBytes < requiredBytes) {
                    throw IllegalStateException("Not enough free storage to finish this download")
                }
            }
            val hasValidatedResume = code == HttpURLConnection.HTTP_PARTIAL && offset > 0 && validator != null
            val total = when {
                contentRange?.total != null -> contentRange.total
                length >= 0 -> offset + length
                hasValidatedResume -> worker.item.total.takeIf { it >= offset } ?: -1L
                else -> -1L
            }
            val headerName = DownloadProtocol.parseContentDispositionName(connection.getHeaderField("Content-Disposition"))?.let { DownloadProtocol.safeFileName(it) }
            worker.item = worker.item.copy(
                downloaded = offset,
                total = total,
                state = "RUNNING",
                etag = etag.orEmpty(),
                lastModified = modified.orEmpty(),
                fileName = headerName ?: worker.item.fileName,
                resourceUrl = connection.url.toString().take(8192)
            )
            put(worker.item)
            connection.inputStream.use { input ->
                FileOutputStream(part, offset > 0).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var lastUpdate = System.currentTimeMillis()
                    while (true) {
                        if (worker.cancel.get() || worker.pause.get()) break
                        val count = input.read(buffer)
                        if (count < 0) break
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
                part.delete()
            }
        } catch (error: Exception) {
            val part = partial(id)
            if (worker.pause.get()) {
                worker.item = worker.item.copy(downloaded = part.length(), state = "PAUSED")
            } else if (!worker.cancel.get()) {
                worker.item = worker.item.copy(downloaded = part.length(), state = "FAILED", error = error.message ?: "Download failed")
            }
            if (!worker.cancel.get()) put(worker.item)
        } finally {
            worker.connection?.disconnect()
            if (worker.cancel.get()) partial(id).delete()
            running.remove(id)
            NovaDownloadService.update(context, running.size)
        }
    }

    /** Opens the URL following redirects manually, because HttpURLConnection will not follow http -> https. */
    private fun openConnection(worker: Running, offset: Long, validator: String?): HttpURLConnection {
        var current = URL(worker.item.url)
        var hops = 0
        while (true) {
            require(current.protocol == "http" || current.protocol == "https") { "Only HTTP(S) downloads are supported" }
            val connection = current.openConnection() as HttpURLConnection
            worker.connection = connection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            connection.setRequestProperty("User-Agent", "NovaBrowser/0.4 GeckoView")
            // Keep bytes identical to the server copy so Content-Length and Range offsets stay valid.
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) {
                connection.setRequestProperty("Range", "bytes=$offset-")
                if (validator != null) connection.setRequestProperty("If-Range", validator)
            }
            val status = connection.responseCode
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank() || ++hops > 5) throw IllegalStateException("Too many redirects")
                val next = URL(current, location)
                if (!DownloadProtocol.isRedirectAllowed(current.toString(), next.toString())) {
                    throw IllegalStateException("Blocked unsafe download redirect")
                }
                current = next
                continue
            }
            return connection
        }
    }

    fun saveExternalStream(stream: InputStream, fileName: String, mimeType: String) {
        val id = UUID.randomUUID().toString()
        val item = Item(id, "", sanitize(fileName), mimeType, 0, 0, "SAVING")
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
                    synchronized(this) { records.remove(id); persist(); changed?.invoke(items()) }
                } else {
                    val uri = publish(part, item.fileName, item.mimeType)
                    worker.item = worker.item.copy(downloaded = part.length(), state = "COMPLETE", savedUri = uri.toString())
                    put(worker.item)
                }
            } catch (error: Exception) {
                runCatching { stream.close() }
                if (!worker.cancel.get()) put(worker.item.copy(
                    state = if (worker.item.url.isBlank()) "INTERRUPTED" else "FAILED",
                    error = error.message ?: "Could not save browser download"
                ))
            } finally {
                runCatching { stream.close() }
                part.delete()
                running.remove(id)
                NovaDownloadService.update(context, running.size)
            }
        }
    }

    private fun partial(id: String) = File(File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "nova-partials/${storageNamespace}"), "$id.part")

    private fun publish(file: File, name: String, mime: String): Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Nova/${storageNamespace}")
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
        private val sharedManagers = mutableMapOf<String, NovaDownloadManager>()

        private fun normalizeNamespace(value: String): String =
            value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                .also { require(it.isNotBlank()) { "A non-empty engine storage namespace is required" } }

        fun shared(context: Context, namespace: String, listener: (List<Item>) -> Unit): NovaDownloadManager {
            val normalized = normalizeNamespace(namespace)
            return synchronized(sharedManagers) {
                sharedManagers.getOrPut(normalized) {
                    NovaDownloadManager(context.applicationContext, normalized) { }
                }.also { it.setOnChangedListener(listener) }
            }
        }

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
