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
import android.webkit.ValueCallback
import org.json.JSONObject
import org.json.JSONTokener
import com.cefrium.CefriumBrowser
import com.cefrium.CefriumDownloadClient
import com.cefrium.CefriumChromeClient
import com.cefrium.CefriumPermissionRequest
import com.cefrium.CefriumProfile
import com.cefrium.CefriumNavigationClient
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
    private val profile: CefriumProfile,
    private val listener: BrowserEngineListener? = null,
    private val onDownloadCompleted: (CefriumDownloadInfo) -> Unit = {},
    private val onPermissionRequest: (origin: String, permissions: List<String>, respond: (Boolean) -> Unit) -> Unit = { _, _, respond -> respond(false) },
    desktopMode: Boolean = false
) : BrowserEngine {
    override val type = BrowserEngineType.CEFRIUM
    override val capabilities = BrowserEngineCapabilitySets.CEFRIUM
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
        browser.addDocumentStartJavaScript(DESKTOP_VIEWPORT_SCRIPT, setOf("*"))
        if (desktopMode) browser.setUserAgentOverride(DESKTOP_USER_AGENT)
        CefriumVpnController.registerProfile(profile)
        browser.setNavigationClient(object : CefriumNavigationClient {
            override fun onRenderProcessTerminated(
                browser: CefriumBrowser,
                status: com.cefrium.CefriumRendererTerminationStatus,
                errorCode: Int
            ) {
                publish(currentState.copy(
                    isLoading = false,
                    progress = 0,
                    error = "Cefrium renderer terminated: $status (exit $errorCode)"
                ))
            }

            override fun onHttpAuthRequest(
                browser: CefriumBrowser,
                host: String,
                realm: String,
                port: Int,
                isProxy: Boolean,
                callback: CefriumBrowser.HttpAuthCallback
            ) {
                CefriumVpnController.provideProxyCredentials(
                    host = host,
                    port = port,
                    isProxy = isProxy,
                    callback = { username, password -> callback.proceed(username, password) },
                    cancel = { callback.cancel() }
                )
            }
        })
        browser.setDownloadClient(downloadPublisher.client)
        browser.setChromeClient(object : CefriumChromeClient {
            override fun onPermissionRequest(browser: CefriumBrowser, request: CefriumPermissionRequest) {
                // Deny insecure/opaque origins and any permission kind Nova does not recognize.
                val requested = CefriumSitePermissionPolicy.normalize(
                    request.origin,
                    request.permissions.map { it.toString() }
                )
                if (requested == null) {
                    request.deny()
                    return
                }
                onPermissionRequest(request.origin, requested) { allowed ->
                    if (allowed) request.grant() else request.deny()
                }
            }
        })
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

    fun refreshVpnSettings() {
        checkOpen()
        CefriumVpnController.registerProfile(profile)
    }

    /** Loads trusted app-provided HTML under an explicit HTTP(S) origin (also used by tests). */
    fun loadHtmlWithBaseUrl(html: String, baseUrl: String) {
        checkOpen()
        val parsed = Uri.parse(baseUrl)
        require((parsed.scheme == "https" || parsed.scheme == "http") && !parsed.host.isNullOrBlank()) {
            "HTML base URL must be an absolute HTTP(S) origin"
        }
        require(html.length <= MAX_READER_HTML_CHARS) { "HTML content exceeds Nova's safe rendering limit" }
        browser.loadHtmlWithBaseUrl(html, baseUrl)
    }

    /** Executes a bounded script in the current page; callers must not pass untrusted code. */
    fun evaluateJavaScript(script: String, callback: ValueCallback<String?>) {
        checkOpen()
        require(script.length <= MAX_SCRIPT_CHARS) { "JavaScript input is too large" }
        browser.evaluateJavaScript(script, callback)
    }

    fun setDesktopMode(enabled: Boolean) {
        checkOpen()
        browser.setUserAgentOverride(if (enabled) DESKTOP_USER_AGENT else null)
    }

    fun loadReaderView(originalUrl: String, onResult: (Boolean, String) -> Unit) {
        checkOpen()
        val extractionScript = """
            (() => {
              const root = document.querySelector('article') ||
                document.querySelector('[role="main"]') ||
                document.querySelector('main') || document.body;
              if (!root) return { title: document.title || 'Reader view', html: '' };
              const clone = root.cloneNode(true);
              clone.querySelectorAll('script,style,link,nav,header,footer,aside,form,button,input,select,textarea,iframe,object,embed,canvas,svg,noscript,[hidden],[aria-hidden="true"]').forEach(n => n.remove());
              clone.querySelectorAll('*').forEach(el => {
                Array.from(el.attributes).forEach(attr => {
                  const name = attr.name.toLowerCase();
                  const value = attr.value.trim();
                  if (name.startsWith('on') || name === 'style' || name === 'srcdoc' || name === 'srcset') {
                    el.removeAttribute(attr.name);
                  } else if (name === 'href' || name === 'src') {
                    try {
                      const resolved = new URL(value, document.baseURI);
                      if (resolved.protocol !== 'http:' && resolved.protocol !== 'https:') el.removeAttribute(attr.name);
                    } catch (_) { el.removeAttribute(attr.name); }
                  }
                });
              });
              return { title: document.title || (clone.querySelector('h1')?.textContent || 'Reader view'), html: clone.innerHTML };
            })()
        """.trimIndent()

        browser.evaluateJavaScript(extractionScript, ValueCallback { raw ->
            try {
                val decoded = JSONTokener(raw ?: "{}").nextValue()
                val payload = if (decoded is JSONObject) decoded else JSONObject(decoded.toString())
                val title = payload.optString("title").ifBlank { "Reader view" }
                val content = payload.optString("html")
                check(content.isNotBlank()) { "No readable article content was found" }
                check(content.length <= MAX_READER_HTML_CHARS) { "Article is too large for safe Reader Mode extraction" }
                val safeBase = escapeHtml(originalUrl)
                val html = buildString {
                    append("<!doctype html><html><head><meta charset=\"utf-8\">")
                    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                    append("<base href=\"").append(safeBase).append("\">")
                    append("<title>").append(escapeHtml(title)).append("</title>")
                    append("<style>")
                    append(":root { color-scheme: light dark; }")
                    append("body { max-width: 760px; margin: 0 auto; padding: 28px 20px 56px; font: 18px/1.75 system-ui, sans-serif; overflow-wrap: anywhere; }")
                    append("h1,h2,h3 { line-height: 1.25; } img,video { max-width: 100%; height: auto; }")
                    append("a { text-decoration: underline; } blockquote { margin-inline: 0; padding-left: 1em; border-left: 3px solid #888; }")
                    append("</style></head><body><main>")
                    append(content)
                    append("</main></body></html>")
                }
                browser.loadHtmlWithBaseUrl(html, originalUrl)
                onResult(true, title)
            } catch (error: Exception) {
                onResult(false, error.localizedMessage ?: "Could not extract this page")
            }
        })
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

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

    private companion object {
        const val MAX_READER_HTML_CHARS = 1_500_000
        const val MAX_SCRIPT_CHARS = 64_000
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36"

        const val DESKTOP_VIEWPORT_SCRIPT = """
            (() => {
              if (window.top !== window || /Android|Mobile|iPhone|iPad/i.test(navigator.userAgent)) return;
              const apply = () => {
                document.querySelectorAll('meta[name="viewport"]').forEach(node => node.remove());
                if (document.head && !document.querySelector('meta[name="viewport"][data-nova-desktop]')) {
                  const meta = document.createElement('meta');
                  meta.name = 'viewport';
                  meta.content = 'width=1280';
                  meta.setAttribute('data-nova-desktop', 'true');
                  document.head.appendChild(meta);
                }
              };
              new MutationObserver(apply).observe(document, { childList: true, subtree: true });
              apply();
            })();
        """
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
            val publishedRows = appContext.contentResolver.update(uri, ready, null, null)
            check(publishedRows > 0) { "Android could not publish the completed download" }
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
