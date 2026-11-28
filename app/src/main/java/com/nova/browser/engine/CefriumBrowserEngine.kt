package com.nova.browser.engine

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import android.webkit.ValueCallback
import org.json.JSONObject
import org.json.JSONTokener
import com.cefrium.CefriumBrowser
import com.cefrium.CefriumExperimental
import com.cefrium.CefriumDownloadClient
import com.cefrium.CefriumChromeClient
import com.cefrium.CefriumPermissionRequest
import com.cefrium.CefriumPermission
import com.cefrium.CefriumContentSetting
import com.cefrium.CefriumNavigationClient
import com.cefrium.CefriumProfile
import com.cefrium.CefriumContextMenu
import com.nova.browser.DownloadProtocol
import com.nova.browser.CefriumPasswordAutofillPolicy
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
@OptIn(CefriumExperimental::class)
class CefriumBrowserEngine(
    activity: Activity,
    private val profile: CefriumProfile,
    private val listener: BrowserEngineListener? = null,
    private val onDownloadCompleted: (CefriumDownloadInfo) -> Unit = {},
    private val onPermissionRequest: (origin: String, permissions: List<String>, respond: (Boolean) -> Unit) -> Unit = { _, _, respond -> respond(false) },
    private val onFindResult: (activeMatch: Int, totalMatches: Int) -> Unit = { _, _ -> },
    private val onFullscreenChanged: (Boolean) -> Unit = {},
    private val onPopupRequested: (CefriumBrowser, String, Boolean) -> Boolean = { _, _, _ -> false },
    private val onPopupClosed: (CefriumBrowser) -> Unit = {},
    private val onExternalNavigation: (String, Boolean) -> Boolean = { _, _ -> false },
    private val onHttpAuthRequest: (String, String, Boolean, (String?, String?) -> Unit) -> Unit = { _, _, _, respond -> respond(null, null) },
    private val onContextMenu: (CefriumContextMenu) -> Boolean = { it.cancel(); false },
    private val onBeforeUnloadDialog: (String, Boolean, (Boolean) -> Unit) -> Unit = { _, _, answer -> answer(false) },
    private val onPermissionRequestCanceled: () -> Unit = {},
    desktopMode: Boolean = false,
    existingBrowser: CefriumBrowser? = null,
    initialNavigationState: ByteArray? = null,
    initialNovaAddons: List<NovaScriptAddon> = emptyList(),
    private val javaScriptEnabledForUrl: (String) -> Boolean = { true },
    private val autoplayAllowedForUrl: (String) -> Boolean = { false },
    private val adBlockingEnabledForUrl: (String) -> Boolean = { true },
    private val soundAllowedForUrl: (String) -> Boolean = { true },
    private val onEngineRoutingRequired: (String, String) -> Boolean = { _, _ -> false }
) : BrowserEngine {
    override val type = BrowserEngineType.CEFRIUM
    override val capabilities = setOf(
        BrowserEngineCapability.DOWNLOADS,
        BrowserEngineCapability.SITE_PERMISSIONS,
        BrowserEngineCapability.READER_MODE,
        BrowserEngineCapability.DESKTOP_MODE,
        BrowserEngineCapability.FIND_IN_PAGE,
        BrowserEngineCapability.FULLSCREEN,
        BrowserEngineCapability.POPUP_TABS,
        BrowserEngineCapability.JS_DIALOGS,
        BrowserEngineCapability.FILE_CHOOSER,
        BrowserEngineCapability.CONTEXT_MENU,
        BrowserEngineCapability.HTTP_AUTH,
        BrowserEngineCapability.EXTERNAL_NAVIGATION,
        BrowserEngineCapability.SITE_DATA,
        BrowserEngineCapability.PASSWORD_FILL
    )
    private val browser = existingBrowser ?: CefriumBrowser.createWithSurface(activity, profile)
    private val downloadPublisher = CefriumDownloadPublisher(activity.applicationContext, onDownloadCompleted)
    override val view: View = browser.getSurfaceContainer()
    private var currentState = BrowserEngineState(
        url = browser.getUrl().orEmpty(),
        title = browser.getTitle(),
        progress = browser.getProgress()
    )
    @Volatile private var closed = false
    private val novaAddonScriptHandlers = mutableMapOf<String, CefriumBrowser.ScriptHandler>()
    var didRestoreInitialNavigationState: Boolean = false
        private set

    override val state: BrowserEngineState
        get() = currentState

    init {
        // Restore the encrypted per-tab navigation stack before any new navigation.
        if (initialNavigationState != null && initialNavigationState.isNotEmpty()) {
            didRestoreInitialNavigationState = runCatching {
                browser.restoreNavigationState(initialNavigationState)
                true
            }.getOrDefault(false)
            if (didRestoreInitialNavigationState) {
                currentState = currentState.copy(
                    url = browser.getUrl().orEmpty(),
                    title = browser.getTitle(),
                    canGoBack = browser.canGoBack(),
                    canGoForward = browser.canGoForward(),
                    progress = browser.getProgress().coerceIn(0, 100)
                )
            }
        }
        // Use the typed 0.21 clients rather than deprecated one-listener-per-event APIs.
        browser.setPageVisible(false)
        browser.setFocus(false)
        browser.setJavaScriptEnabled(javaScriptEnabledForUrl(browser.getUrl().orEmpty()))
        browser.setNavigationClient(object : CefriumNavigationClient {
            override fun onBeforeNavigate(browser: CefriumBrowser, request: com.cefrium.CefriumNavigationRequest): Boolean {
                val url = request.url
                // These settings belong to the tab's top-level origin, not an embedded iframe.
                val scheme = Uri.parse(url).scheme?.lowercase()
                if (request.isForMainFrame) {
                    applySiteSettingsForUrl(url)
                    if (scheme in setOf("http", "https", "about", "data") &&
                        this@CefriumBrowserEngine.onEngineRoutingRequired.invoke(url, request.method.orEmpty())) return true
                }
                return when (scheme) {
                    "http", "https", "about", "data", "blob", "javascript" -> false
                    "file" -> !url.startsWith("file:///android_asset/", ignoreCase = true)
                    "mailto", "tel", "sms", "smsto", "geo", "market", "intent" ->
                        this@CefriumBrowserEngine.onExternalNavigation.invoke(url, navigationHasUserGesture(request))
                    else -> true // Never hand file/content/arbitrary schemes to the Android package resolver.
                }
            }
            override fun onUrlChanged(browser: CefriumBrowser, url: String) {
                publish(currentState.copy(url = url, error = null))
            }
            override fun onLoadStart(browser: CefriumBrowser, url: String) {
                publish(currentState.copy(url = url, isLoading = true, progress = 0, error = null))
            }
            override fun onProgressChanged(browser: CefriumBrowser, percent: Int) {
                publish(currentState.copy(progress = percent.coerceIn(0, 100)))
            }
            override fun onLoadingStateChanged(browser: CefriumBrowser, loading: Boolean, canGoBack: Boolean, canGoForward: Boolean) {
                // Cefrium may deliver a queued load-state callback after close() has
                // released the native browser. Never call JNI getters on that handle.
                if (closed || browser.isClosed()) return
                publish(currentState.copy(
                    isLoading = loading,
                    progress = browser.getProgress().coerceIn(0, 100),
                    canGoBack = canGoBack,
                    canGoForward = canGoForward,
                    error = if (loading) null else currentState.error
                ))
            }
            override fun onLoadError(browser: CefriumBrowser, error: com.cefrium.CefriumLoadError) {
                publish(currentState.copy(isLoading = false, error = error.toString()))
            }
            override fun onRenderProcessTerminated(browser: CefriumBrowser, status: com.cefrium.CefriumRendererTerminationStatus, errorCode: Int) {
                publish(currentState.copy(isLoading = false, error = "Cefrium renderer stopped ($status, code $errorCode)"))
            }
            override fun onRenderProcessUnresponsive(browser: CefriumBrowser) {
                publish(currentState.copy(error = "This page is not responding"))
            }
            override fun onRenderProcessResponsive(browser: CefriumBrowser) {
                publish(currentState.copy(error = null))
            }
            override fun onHttpAuthRequest(browser: CefriumBrowser, host: String, realm: String, port: Int, isProxy: Boolean, callback: CefriumBrowser.HttpAuthCallback) {
                this@CefriumBrowserEngine.onHttpAuthRequest.invoke(host, realm, isProxy) { username, password ->
                    if (username == null || password == null) callback.cancel() else callback.proceed(username, password)
                }
            }
        })
        browser.addDocumentStartJavaScript(DESKTOP_VIEWPORT_SCRIPT, setOf("*"))
        setNovaScriptAddons(initialNovaAddons)
        if (desktopMode) browser.setUserAgentOverride(DESKTOP_USER_AGENT)
        browser.setDownloadClient(downloadPublisher.client)
        browser.setChromeClient(object : CefriumChromeClient {
            override fun onTitleChanged(browser: CefriumBrowser, title: String) {
                publish(currentState.copy(title = title))
            }
            override fun onContextMenu(browser: CefriumBrowser, menu: CefriumContextMenu): Boolean =
                this@CefriumBrowserEngine.onContextMenu.invoke(menu)
            override fun onBeforeUnloadDialog(browser: CefriumBrowser, message: String, isReload: Boolean, callback: CefriumBrowser.JsDialogCallback): Boolean {
                this@CefriumBrowserEngine.onBeforeUnloadDialog.invoke(message, isReload) { leave ->
                    // Cefrium 0.21 exposes a Java callback carrying success + prompt text.
                    // Resolve that signature without binding the adapter to a method name.
                    val resolver = callback.javaClass.methods.firstOrNull { method ->
                        method.parameterCount == 2 &&
                            (method.parameterTypes[0] == Boolean::class.javaPrimitiveType || method.parameterTypes[0] == Boolean::class.java) &&
                            method.parameterTypes[1] == String::class.java
                    }
                    if (resolver != null) runCatching { resolver.invoke(callback, leave, "") }
                }
                return true
            }
            override fun onFindResult(browser: CefriumBrowser, activeMatch: Int, matches: Int) {
                this@CefriumBrowserEngine.onFindResult.invoke(activeMatch, matches)
            }
            override fun onFullscreenChanged(browser: CefriumBrowser, fullscreen: Boolean) {
                this@CefriumBrowserEngine.onFullscreenChanged.invoke(fullscreen)
            }
            override fun onJsDialog(
                browser: CefriumBrowser,
                type: com.cefrium.CefriumJsDialogType,
                message: String,
                defaultText: String,
                callback: CefriumBrowser.JsDialogCallback
            ): Boolean = false // Keep Cefrium's native, correctly typed alert/confirm/prompt UI.
            override fun onShowFileChooser(
                browser: CefriumBrowser,
                params: com.cefrium.CefriumFileChooserParams,
                callback: com.cefrium.CefriumFileChooserCallback
            ): Boolean = false // Keep the SDK's surface-mode Android picker and camera integration.
            override fun onCreateWindow(browser: CefriumBrowser, popup: CefriumBrowser, url: String, userGesture: Boolean): Boolean =
                this@CefriumBrowserEngine.onPopupRequested.invoke(popup, url, userGesture)
            override fun onCloseWindow(browser: CefriumBrowser, popup: CefriumBrowser) {
                this@CefriumBrowserEngine.onPopupClosed.invoke(popup)
            }
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
                this@CefriumBrowserEngine.onPermissionRequest.invoke(request.origin, requested) { allowed ->
                    if (allowed) request.grant() else request.deny()
                }
            }
            override fun onPermissionRequestCanceled(browser: CefriumBrowser, request: CefriumPermissionRequest) {
                this@CefriumBrowserEngine.onPermissionRequestCanceled.invoke()
            }
        })
    }

    fun ownsBrowser(candidate: CefriumBrowser): Boolean = browser === candidate

    fun setJavaScriptEnabled(enabled: Boolean) {
        checkOpen()
        browser.setJavaScriptEnabled(enabled)
    }
    fun setAutoplayAllowed(allowed: Boolean) {
        checkOpen()
        browser.setMediaPlaybackRequiresUserGesture(!allowed)
    }
    fun setAdBlockingEnabled(enabled: Boolean) {
        checkOpen()
        browser.setAdBlockEnabled(enabled)
    }
    fun setAudioMuted(muted: Boolean) {
        checkOpen()
        browser.setAudioMuted(muted)
    }

    fun setNovaScriptAddons(addons: List<NovaScriptAddon>): List<String> {
        checkOpen()
        novaAddonScriptHandlers.values.forEach { runCatching { it.remove() } }
        novaAddonScriptHandlers.clear()
        val failures = mutableListOf<String>()
        addons.filter { it.enabled }.forEach { addon ->
            if (addon.allowedOriginRules.isEmpty()) {
                failures += addon.id
                android.util.Log.w("NovaBrowser", "Skipping add-on ${addon.id}: empty origin allowlist")
                return@forEach
            }
            runCatching {
                val idLiteral = org.json.JSONObject.quote(addon.id)
                val nameLiteral = org.json.JSONObject.quote(addon.name)
                val wrapped = """
                    (() => {
                      try {
                        globalThis.__novaAddonLoaded = globalThis.__novaAddonLoaded || Object.create(null);
                        if (globalThis.__novaAddonLoaded[$idLiteral]) return;
                        globalThis.__novaAddonLoaded[$idLiteral] = true;
                        ${addon.script}
                      } catch (error) {
                        console.error("[Nova Add-on: " + $nameLiteral + "]", error);
                      }
                    })();
                """.trimIndent()
                browser.addDocumentStartJavaScript(wrapped, addon.allowedOriginRules)
            }.onSuccess { handler ->
                novaAddonScriptHandlers[addon.id] = handler
            }.onFailure { error ->
                failures += addon.id
                android.util.Log.w("NovaBrowser", "Could not register Nova add-on ${addon.id}", error)
            }
        }
        return failures.distinct()
    }
    /** Persist a per-origin website permission decision in this Cefrium profile. */
    fun setSitePermission(origin: String, permission: String, decision: String) {
        if (closed) return
        val cefriumPermission = when (permission.uppercase()) {
            "GEOLOCATION" -> CefriumPermission.GEOLOCATION
            "CAMERA" -> CefriumPermission.CAMERA
            "MICROPHONE" -> CefriumPermission.MICROPHONE
            "NOTIFICATIONS" -> CefriumPermission.NOTIFICATIONS
            "CLIPBOARD_READ" -> CefriumPermission.CLIPBOARD_READ
            "NFC" -> CefriumPermission.NFC
            else -> return
        }
        val contentSetting = when (decision.uppercase()) {
            "ALLOW" -> CefriumContentSetting.ALLOW
            "BLOCK" -> CefriumContentSetting.BLOCK
            else -> CefriumContentSetting.ASK
        }
        browser.setContentSetting(origin, cefriumPermission, contentSetting)
    }

    fun startDownload(url: String) { if (!closed) browser.startDownload(url) }
    fun serializeNavigationState(): ByteArray? = if (closed) null else runCatching { browser.serializeNavigationState() }.getOrNull()

    /** Executes JavaScript through Cefrium's typed callback API. */
    fun evaluateJavaScript(script: String, onResult: (String?) -> Unit) {
        checkOpen()
        browser.evaluateJavaScript(script, ValueCallback { value -> onResult(value) })
    }

    /**
     * Fill a saved login only after an explicit user action and only into a same-origin form.
     * No credential is stored in page state, logged, or sent to a cross-origin form action.
     */
    fun fillSavedLogin(
        expectedOrigin: String,
        username: String,
        password: String,
        formActionOrigin: String?,
        onResult: (Boolean, String) -> Unit
    ) {
        checkOpen()
        if (!CefriumPasswordAutofillPolicy.isAllowed(browser.getUrl().orEmpty(), expectedOrigin, formActionOrigin)) {
            onResult(false, "The page or saved form action no longer matches this login")
            return
        }
        val originLiteral = JSONObject.quote(expectedOrigin)
        val usernameLiteral = JSONObject.quote(username)
        val passwordLiteral = JSONObject.quote(password)
        val script = """
            (() => {
              const expectedOrigin = $originLiteral;
              if (location.origin !== expectedOrigin) return JSON.stringify({ok:false, reason:'origin-changed'});
              const forms = Array.from(document.forms);
              const form = forms.find(candidate => {
                try {
                  const action = new URL(candidate.getAttribute('action') || location.href, location.href);
                  return action.origin === expectedOrigin && !!candidate.querySelector('input[type="password"]');
                } catch (_) { return false; }
              });
              if (!form) return JSON.stringify({ok:false, reason:'same-origin-password-form-not-found'});
              const passwordField = form.querySelector('input[type="password"]');
              if (!passwordField || passwordField.disabled || passwordField.readOnly)
                return JSON.stringify({ok:false, reason:'password-field-unavailable'});
              const usernameField = form.querySelector('input[autocomplete="username"], input[type="email"], input[name*="user" i], input[id*="user" i], input[name*="email" i], input[id*="email" i]');
              const setValue = (field, value) => {
                const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
                if (setter) setter.call(field, value); else field.value = value;
                field.dispatchEvent(new Event('input', {bubbles:true}));
                field.dispatchEvent(new Event('change', {bubbles:true}));
              };
              if (usernameField && !usernameField.disabled && !usernameField.readOnly) setValue(usernameField, $usernameLiteral);
              setValue(passwordField, $passwordLiteral);
              return JSON.stringify({ok:true});
            })()
        """.trimIndent()
        browser.evaluateJavaScript(script, ValueCallback { raw ->
            val result = runCatching { JSONTokener(raw ?: "{}").nextValue() as? JSONObject }.getOrNull()
            val success = result?.optBoolean("ok") == true
            val reason = result?.optString("reason").orEmpty()
            onResult(success, if (success) "Saved login filled" else when (reason) {
                "origin-changed" -> "The page changed origin; login was not filled"
                "password-field-unavailable" -> "The password field is not available"
                else -> "No same-origin password form was found"
            })
        })
    }

    /** Loads HTML with an explicit origin for deterministic local compatibility tests and Reader Mode. */
    fun loadHtmlWithBaseUrl(html: String, baseUrl: String) {
        checkOpen()
        browser.loadHtmlWithBaseUrl(html, baseUrl)
    }

    override fun loadUrl(url: String) {
        checkOpen()
        applySiteSettingsForUrl(url)
        browser.loadUrl(url)
    }

    override fun goBack() {
        checkOpen()
        if (currentState.canGoBack) {
            applyHistoryDestinationSettings(-1)
            browser.goBack()
        }
    }

    override fun goForward() {
        checkOpen()
        if (currentState.canGoForward) {
            applyHistoryDestinationSettings(1)
            browser.goForward()
        }
    }

    override fun reload() {
        checkOpen()
        applySiteSettingsForUrl(browser.getUrl().orEmpty())
        browser.reload()
    }

    override fun stop() { if (!closed) browser.stopLoading() }

    override fun findInPage(query: String, forward: Boolean, findNext: Boolean) {
        if (!closed) browser.find(query, forward, false, findNext)
    }

    override fun stopFinding() { if (!closed) browser.stopFinding(true) }
    override fun clearCache() { if (!closed) browser.clearCache() }
    override fun exitFullscreen() { if (!closed) browser.exitFullscreen() }

    override fun setDesktopMode(enabled: Boolean) {
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

    override fun detach() {
        (view.parent as? android.view.ViewGroup)?.removeView(view)
    }

    override fun close() {
        if (closed) return
        detach()
        novaAddonScriptHandlers.values.forEach { runCatching { it.remove() } }
        novaAddonScriptHandlers.clear()
        closed = true
        browser.close()
    }

    private fun applyHistoryDestinationSettings(offset: Int) {
        val entries = runCatching { browser.getNavigationEntries() }.getOrNull().orEmpty()
        val currentIndex = entries.indexOfFirst { it.current }
        if (currentIndex < 0) return
        val destinationUrl = entries.getOrNull(currentIndex + offset)?.url ?: return
        // cefrium-sdk 0.21.0 exposes URL/title/current for history entries, but no request method.
        // Keep Back/Forward native rather than guessing GET and replaying a POST as a different request.
        applySiteSettingsForUrl(destinationUrl)
    }

    private fun applySiteSettingsForUrl(url: String) {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return
        browser.setJavaScriptEnabled(javaScriptEnabledForUrl(url))
        browser.setMediaPlaybackRequiresUserGesture(!autoplayAllowedForUrl(url))
        browser.setAdBlockEnabled(adBlockingEnabledForUrl(url))
        browser.setAudioMuted(!soundAllowedForUrl(url))
    }

    private fun navigationHasUserGesture(request: com.cefrium.CefriumNavigationRequest): Boolean = runCatching {
        val method = request.javaClass.methods.firstOrNull {
            it.parameterCount == 0 && it.name in setOf("hasUserGesture", "isUserGesture", "getUserGesture", "hasGesture", "getGesture", "isGesture")
        }
        val fromMethod = method?.invoke(request) as? Boolean
        if (fromMethod != null) fromMethod else {
            val field = request.javaClass.declaredFields.firstOrNull { it.name.contains("gesture", ignoreCase = true) }
            field?.isAccessible = true
            field?.get(request) as? Boolean ?: false
        }
    }.getOrDefault(false)

    private fun publish(next: BrowserEngineState) {
        // Drop late native callbacks after teardown; observers may already own a new engine.
        if (closed) return
        currentState = next
        listener?.onStateChanged(next)
    }

    private fun checkOpen() {
        check(!closed && !browser.isClosed()) { "Cefrium browser engine is closed" }
    }

    private companion object {
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36"

        const val DESKTOP_VIEWPORT_SCRIPT = """
            (() => {
              if (window.top !== window || /Android|Mobile|iPhone|iPad/i.test(navigator.userAgent)) return;
              const apply = () => {
                const head = document.head;
                if (!head) return;
                const desired = document.querySelector('meta[name="viewport"][data-nova-desktop]');
                const viewports = Array.from(document.querySelectorAll('meta[name="viewport"]'));
                if (desired && desired.content === 'width=1280' && viewports.length === 1) return;
                viewports.forEach(node => { if (node !== desired) node.remove(); });
                if (desired) {
                  desired.content = 'width=1280';
                  return;
                }
                const meta = document.createElement('meta');
                meta.name = 'viewport';
                meta.content = 'width=1280';
                meta.setAttribute('data-nova-desktop', 'true');
                head.appendChild(meta);
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
@OptIn(CefriumExperimental::class)
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
