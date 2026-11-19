package com.nova.browser.engine

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import com.cefrium.CefriumBrowser
import com.cefrium.CefriumDownloadClient
import com.cefrium.CefriumProfile
import com.nova.browser.DownloadProtocol
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Metadata for a Cefrium download after it has been published to Android Downloads. */
data class CefriumDownloadInfo(
    val url: String,
    val fileName: String,
    val mimeType: String,
    val bytes: Long,
    val savedUri: String
)

/** Cefrium implementation of the shared per-tab engine contract. */
class CefriumBrowserEngine(
    activity: Activity,
    profile: CefriumProfile,
    private val listener: BrowserEngineListener? = null,
    private val onDownloadCompleted: (CefriumDownloadInfo) -> Unit = {}
) : BrowserEngine {
    override val type = BrowserEngineType.CEFRIUM
    private val browser = CefriumBrowser.createWithSurface(activity, profile)
    private val downloadPublisher = CefriumDownloadPublisher(activity.applicationContext, onDownloadCompleted)
    override val view: View = browser.getSurfaceContainer()
    private var currentState = BrowserEngineState(
        url = browser.getUrl(),
        title = browser.getTitle(),
        progress = browser.getProgress()
    )
    private var closed = false

    override val state: BrowserEngineState
        get() = currentState

    init {
        // Cefrium is pre-1.0. Keep its callback surface retained here so SDK API migrations do not
        // leak into Nova's tab manager.
        browser.setOnUrlChangedListener { url ->
            publish(currentState.copy(url = url))
        }
        browser.setOnTitleChangedListener { title ->
            publish(currentState.copy(title = title))
        }
        browser.setOnLoadingStateChangedListener { loading, canGoBack, canGoForward ->
            publish(currentState.copy(
                isLoading = loading,
                progress = browser.getProgress().coerceIn(0, 100),
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                error = null
            ))
        }
        browser.setDownloadClient(downloadPublisher.client)
    }

    override fun loadUrl(url: String) {
        checkOpen()
        browser.loadUrl(url)
    }

    override fun goBack() {
        checkOpen()
        if (currentState.canGoBack) browser.goBack()
    }

    override fun goForward() {
        checkOpen()
        if (currentState.canGoForward) browser.goForward()
    }

    override fun reload() {
        checkOpen()
        browser.reload()
    }

    override fun setActive(active: Boolean, focused: Boolean) {
        checkOpen()
        browser.setPageVisible(active)
        browser.setFocus(focused && active)
    }

    override fun onHostPause() {
        if (!closed) browser.onPause()
    }

    override fun onHostResume() {
        if (!closed) browser.onResume()
    }

    override fun close() {
        if (closed) return
        closed = true
        browser.close()
    }

    private fun publish(next: BrowserEngineState) {
        currentState = next
        listener?.onStateChanged(next)
    }

    private fun checkOpen() {
        check(!closed && !browser.isClosed()) { "Cefrium browser engine is closed" }
    }
}

/**
 * Routes completed Cefrium downloads into the user's public Downloads/Nova folder.
 * The engine writes to its app-private Downloads directory first; only completed files
 * are copied to MediaStore, and the private source is deleted only after publication.
 */
private class CefriumDownloadPublisher(
    context: Context,
    private val onDownloadCompleted: (CefriumDownloadInfo) -> Unit
) {
    private val appContext = context.applicationContext
    private val publishingIds = ConcurrentHashMap.newKeySet<Int>()

    val client: CefriumDownloadClient = object : CefriumDownloadClient {
        override fun onBeforeDownload(
            browser: CefriumBrowser,
            item: CefriumBrowser.DownloadItem,
            callback: CefriumBrowser.BeforeDownloadCallback
        ) {
            // Let Cefrium choose its safe app-private Downloads path. Nova publishes it below.
            callback.allow(null)
        }

        override fun onDownloadUpdated(browser: CefriumBrowser, item: CefriumBrowser.DownloadItem) {
            if (!item.isComplete || !publishingIds.add(item.id)) return
            val downloadId = item.id
            val sourcePath = item.fullPath
            val downloadUrl = item.url
            val fileName = DownloadProtocol.safeFileName(item.suggestedName)
            val mimeType = item.mimeType.ifBlank { "application/octet-stream" }
            PUBLISH_EXECUTOR.execute {
                var publishedSuccessfully = false
                try {
                    val published = publishCompletedFile(File(sourcePath), fileName, mimeType)
                    publishedSuccessfully = true
                    runCatching {
                        onDownloadCompleted(CefriumDownloadInfo(
                            url = downloadUrl,
                            fileName = fileName,
                            mimeType = mimeType,
                            bytes = published.second,
                            savedUri = published.first.toString()
                        ))
                    }
                    notifyUser("Download saved to Downloads/Nova")
                } catch (error: Exception) {
                    notifyUser("Download completed but could not be moved to Downloads/Nova: ${error.localizedMessage ?: "unknown error"}")
                } finally {
                    if (!publishedSuccessfully) publishingIds.remove(downloadId)
                }
            }
        }
    }

    private fun publishCompletedFile(source: File, fileName: String, mimeType: String): Pair<Uri, Long> {
        check(source.isFile) { "Cefrium's completed download file is unavailable" }
        val byteCount = source.length()
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Nova")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = appContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Android could not create the Downloads file")
        try {
            appContext.contentResolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Android could not write the Downloads file")
            val ready = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            appContext.contentResolver.update(uri, ready, null, null)
            // A failed cleanup must not invalidate a successfully published public download.
            runCatching { source.delete() }
            return uri to byteCount
        } catch (error: Exception) {
            appContext.contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private fun notifyUser(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        val PUBLISH_EXECUTOR = Executors.newSingleThreadExecutor()
    }
}
