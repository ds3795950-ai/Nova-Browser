package com.nova.browser

import androidx.activity.ComponentActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.app.AlertDialog
import android.app.Dialog
import android.text.Editable
import android.text.TextWatcher
import java.text.SimpleDateFormat
import java.io.File
import java.util.Date
import java.util.Locale
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.WebResponse
import org.mozilla.geckoview.TranslationsController
import org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSession.PromptDelegate.FolderUploadPrompt
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.WindowInsets
import android.view.WindowManager
import android.view.PixelCopy
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.content.res.Configuration
import android.view.ViewGroup
import android.graphics.Typeface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.ImageView
import android.widget.AutoCompleteTextView
import android.widget.ProgressBar
import android.widget.PopupWindow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import android.view.inputmethod.EditorInfo
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Spinner
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.StorageController
import android.widget.CheckBox
import android.content.ClipData
import android.content.ClipboardManager
import org.mozilla.geckoview.GeckoView
import com.cefrium.Cefrium
import com.cefrium.CefriumExperimental
import com.cefrium.CefriumProfile
import com.cefrium.CefriumBrowser
import com.cefrium.CefriumContextMenu
import com.nova.browser.engine.BrowserEngine as TabBrowserEngine
import com.nova.browser.engine.BrowserEngineType
import com.nova.browser.engine.BrowserEngineCapability
import com.nova.browser.engine.BrowserEngineCapabilityPolicy
import com.nova.browser.engine.BrowserEngineCapabilitySets
import com.nova.browser.engine.BrowserEngineProfilePolicy
import com.nova.browser.engine.BrowserRendererRecoveryPolicy
import com.nova.browser.engine.BrowserEngineMode
import com.nova.browser.engine.BrowserEngineState
import com.nova.browser.engine.BrowserEngineListener
import com.nova.browser.engine.BrowserEnginePreferenceStore
import com.nova.browser.engine.CefriumTabStateCipher
import com.nova.browser.engine.BrowserProfileKind
import com.nova.browser.engine.EngineSelectionRequest
import com.nova.browser.engine.EngineSelectionPolicy
import com.nova.browser.engine.CefriumBrowserEngine
import com.nova.browser.engine.CefriumSitePermissionPolicy
import com.nova.browser.engine.CefriumRequestPolicy
import com.nova.browser.engine.CefriumProfileResourceInterceptor
import com.nova.browser.engine.GeckoViewBrowserEngine
import com.nova.browser.engine.ThreatProtectionPolicy

/** Nova browser core. Normal tabs may use Cefrium; Private and Guest remain GeckoView-only. */
private class NovaAddressAutoCompleteTextView(context: Context) : androidx.appcompat.widget.AppCompatAutoCompleteTextView(context) {
    override fun enoughToFilter(): Boolean = true
    override fun performFiltering(text: CharSequence, keyCode: Int) {
        // Nova supplies an already-ranked list; Android's label-only filter must not replace it.
    }

    override fun replaceText(text: CharSequence) {
        // Keep the query and adapter intact until the click listener can use the selected row object.
    }
}

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(NovaThemeManager.wrapBaseContext(newBase))
    }

    private data class PendingCefriumAndroidPermission(
        val tabId: String,
        val permissions: Array<String>,
        val complete: (Boolean) -> Unit,
        var cancelled: Boolean = false
    )
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var cefriumPermissionLauncher: ActivityResultLauncher<Array<String>>
    private val pendingCefriumAndroidPermissions = java.util.ArrayDeque<PendingCefriumAndroidPermission>()
    private var activeCefriumAndroidPermission: PendingCefriumAndroidPermission? = null
    private val cefriumPermissionCancelActions = mutableMapOf<String, MutableSet<() -> Unit>>()
    private lateinit var enginePreferences: BrowserEnginePreferenceStore
    private var activePrimaryEngineView: View? = null
    private var activeSecondaryEngineView: View? = null
    private var notificationPermissionRequestPending = false
    private lateinit var runtime: GeckoRuntime
    private lateinit var browserView: GeckoView
    private lateinit var secondaryBrowserView: GeckoView
    private lateinit var secondaryPane: LinearLayout
    private lateinit var secondaryAddressBar: EditText
    private lateinit var browserPane: LinearLayout
    private lateinit var topChrome: LinearLayout
    private lateinit var bottomToolbar: LinearLayout
    private var findBar: LinearLayout? = null
    private var findInput: EditText? = null
    private var isFullscreen = false
    private var fullscreenTabId: String? = null
    private var pipLeaveRequestId: Long = 0L
    private var splitTabId: String? = null
    private val collapsedGroups = mutableSetOf<String>()
    private var pendingFilePrompt: FilePrompt? = null
    private var pendingFileResult: GeckoResult<PromptResponse>? = null
    private val activePromptDialogs = linkedSetOf<AlertDialog>()
    private var tabSearchResultsDialog: AlertDialog? = null
    private var tabSearchInputDialog: AlertDialog? = null
    private val activeBottomSheetDialogs = linkedSetOf<Dialog>()
    private val activeUndoPopups = linkedSetOf<PopupWindow>()
    private var pendingAndroidPermissionCallback: GeckoSession.PermissionDelegate.Callback? = null
    private var bookmarkTransferMode: String? = null
    private var guestContextId: String = ""
    private var guestTorStartPending: Boolean = false
    @Volatile private var guestModeEpoch: Long = 0L
    private val guestContextCleanupLock = Any()

    private fun guestTorReservesGeckoRuntime(): Boolean =
        guestTorStartPending || TorGuestRoutingController.blocksNormalGeckoSessions()

    private fun canUseTabEngine(tab: BrowserTab, action: String): Boolean {
        if (GuestTorIsolationPolicy.shouldBlockNonGuestSession(tab.isGuest, guestTorReservesGeckoRuntime())) {
            toast("$action is paused while Guest Tor owns the browser")
            return false
        }
        if (BrowserVpnNavigationPolicy.shouldBlockNavigation(NovaBrowserVpnController.isVpnEnabled(), NovaBrowserVpnController.isVpnRoutingReady())) {
            tab.needsReload = tab.lastUrl != HOME_URL
            applyVpnRoutingReadiness()
            toast("$action is paused until 1VPN confirms protected routing")
            return false
        }
        return true
    }
    private val downloadManagers = mutableMapOf<BrowserEngineType, NovaDownloadManager>()

    private fun downloadManager(engineType: BrowserEngineType): NovaDownloadManager = synchronized(downloadManagers) {
        downloadManagers.getOrPut(engineType) {
            NovaDownloadManager.shared(applicationContext, EngineProfileStoragePolicy.suffix(engineType)) { items ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && currentTab()?.engineType == engineType) updateDownloadSummary(items)
                }
            }.also { it.resumeInterrupted() }
        }
    }
    private fun queueGuestContextCleanup(contextId: String?) {
        val pending = prefs.getStringSet("guest_context_cleanup_pending", emptySet())?.toSet() ?: emptySet()
        val queued = GuestContextCleanupPolicy.queue(pending, contextId.orEmpty())
        if (!prefs.edit().putStringSet("guest_context_cleanup_pending", queued).commit()) {
            android.util.Log.w("NovaBrowser", "Could not persist pending Guest context cleanup")
            return
        }
        // GeckoView's clearDataForSessionContext is fire-and-forget; keep IDs and retry on launch.
        queued.forEach { id -> runCatching { runtime.storageController.clearDataForSessionContext(id) }
            .onFailure { android.util.Log.w("NovaBrowser", "Guest context cleanup will be retried", it) } }
    }

    private val geckoDownloads: NovaDownloadManager by lazy { downloadManager(BrowserEngineType.GECKOVIEW) }
    private val cefriumDownloads: NovaDownloadManager by lazy { downloadManager(BrowserEngineType.CEFRIUM) }
    private val passwordVault: PasswordVault by lazy { PasswordVault.shared(this) }
    private val clipboardHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var clipboardClearTask: Runnable? = null
    private val cloudSync: NovaCloudSync by lazy { NovaCloudSync(this) }
    private var pendingHistoryExport: String? = null
    private var pendingExternalLink: String? = null
    private var pendingExternalLinkGuest: Boolean? = null
    private var launchIntentHandled = false
    private val historyExportRequestCode = 4103
    private val detectedPageLanguages = mutableMapOf<String, String>()
    private var pendingTranslationTabId: String? = null
    private val bookmarkRequestCode = 4101
    private val uploadRequestCode = 4102
    private val desktopSites get() = prefs.getStringSet("desktop_sites", emptySet())?.toMutableSet() ?: mutableSetOf()
    private lateinit var addressBar: AutoCompleteTextView
    private var addressSuggestionItems: List<BrowserSuggestion> = emptyList()
    private var isSelectingAddressSuggestion = false
    private val cloudSyncStateLock = Any()
    @Volatile private var cloudSyncGeneration = 0L
    private val addressSuggestionHandler = Handler(Looper.getMainLooper())
    private var pendingRemoteSuggestion: Runnable? = null
    private var activeRemoteSuggestionFuture: java.util.concurrent.Future<*>? = null
    private var activeRemoteSuggestionCancellation: RemoteSearchSuggestions.Cancellation? = null
    @Volatile private var remoteSuggestionTabId: String? = null
    @Volatile private var remoteSuggestionsAllowedForActiveTab = false
    private val remoteSuggestionExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var separatingFold: FoldingFeature? = null
    private lateinit var addressSuggestionsAdapter: ArrayAdapter<BrowserSuggestion>
    private lateinit var tabLabel: TextView
    private lateinit var pageStatus: TextView
    private lateinit var root: LinearLayout
    private lateinit var homePanel: LinearLayout
    private lateinit var homeScroll: ScrollView
    private lateinit var homeShortcuts: GridLayout
    private lateinit var tabsButton: Button
    private lateinit var bookmarkButton: android.widget.ImageButton
    private lateinit var progressBar: ProgressBar
    private var currentPageBookmarked = false
    private var statusState = BrowserStatusState("", 0)
    private var downloadsDialog: AlertDialog? = null
    private var downloadsAdapter: android.widget.BaseAdapter? = null
    private var downloadsItems: List<NovaDownloadManager.Item> = emptyList()
    private lateinit var searchEngineButton: Button
    private val tabs = mutableListOf<BrowserTab>()
    private val pendingRendererPaintResetTabIds = mutableSetOf<String>()
    private var aiPageReaderBridge: WebExtension? = null
    private val aiPageText = mutableMapOf<String, String>()
    private val aiPageUrl = mutableMapOf<String, String>()
    private var activeIndex = 0
    private var lastUiMode = 0
    private var lastFontScale = 1f
    private var searchEngine = "Google"
    private val prefs by lazy { getSharedPreferences("nova_local", MODE_PRIVATE) }
    private data class BookmarkEntry(val url: String, val title: String, val folder: String, val addedAt: Long)
    private data class HistoryEntry(val url: String, val title: String, val visitedAt: Long)
    private data class HomeShortcut(val title: String, val url: String)

    private data class FireClearCompletion(
        val operationId: String,
        val succeeded: Boolean,
        val message: String?
    )

    private data class BrowserTab(
        var session: GeckoSession?,
        val isPrivate: Boolean,
        val isGuest: Boolean = false,
        val id: String = UUID.randomUUID().toString(),
        var lastUrl: String = HOME_URL,
        var group: String = "Ungrouped",
        var title: String = "New tab",
        var renamedTitle: String? = null,
        var canGoBack: Boolean = false,
        var canGoForward: Boolean = false,
        var lastAccess: Long = 0L,
        var needsReload: Boolean = false,
        var isLoading: Boolean = false,
        var progress: Int = 0,
        var securityStatus: String = "",
        var hasCrashed: Boolean = false,
        var isErrorPage: Boolean = false,
        var thumbnail: Bitmap? = null,
        var engineType: BrowserEngineType = BrowserEngineType.GECKOVIEW,
        var explicitEngineChoice: BrowserEngineType? = null,
        var browserEngine: TabBrowserEngine? = null,
        var engineFallbackReason: String? = null,
        var cefriumNavigationState: ByteArray? = null, // AndroidKeyStore AES-GCM ciphertext only.
        var desktopSite: Boolean = false,
        var readerOriginalUrl: String? = null,
        var trackingPermission: GeckoSession.PermissionDelegate.ContentPermission? = null,
        var contentPermissions: List<GeckoSession.PermissionDelegate.ContentPermission> = emptyList(),
        var rendererRecoveryAttempts: Int = 0,
        var mediaSession: org.mozilla.geckoview.MediaSession? = null,
        var isFullscreenVideoPlaying: Boolean = false,
        var wasSleepingForMemoryBudget: Boolean = false,
        var appliedSiteDarkMode: String? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        NovaThemeManager.applyBeforeCreate(this)
        super.onCreate(savedInstanceState)
        currentActivityReference = java.lang.ref.WeakReference(this)
        lastUiMode = resources.configuration.uiMode
        lastFontScale = resources.configuration.fontScale
        notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationPermissionRequestPending = false
            if (!granted) toast("Downloads still work, but Android may hide their notifications until permission is enabled")
        }
        cefriumPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val request = activeCefriumAndroidPermission
            activeCefriumAndroidPermission = null
            if (request != null) {
                val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
                val fine = Manifest.permission.ACCESS_FINE_LOCATION
                val locationRequested = results.containsKey(coarse) || results.containsKey(fine)
                val locationGranted = !locationRequested || results[coarse] == true || results[fine] == true ||
                    checkSelfPermission(coarse) == PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(fine) == PackageManager.PERMISSION_GRANTED
                val unrelatedPermissionsGranted = results.filterKeys { it != coarse && it != fine }.values.all { it }
                request.complete(!request.cancelled && results.isNotEmpty() && locationGranted && unrelatedPermissionsGranted)
            }
            launchNextCefriumAndroidPermission()
        }
        enginePreferences = BrowserEnginePreferenceStore(prefs)
        launchIntentHandled = savedInstanceState?.getBoolean("nova_launch_intent_handled") == true
        pendingExternalLink = prefs.getString("pending_external_link_url", null)?.takeIf {
            it.startsWith("https://", true) || it.startsWith("http://", true)
        }
        pendingExternalLinkGuest = if (pendingExternalLink != null && prefs.contains("pending_external_link_guest")) {
            prefs.getBoolean("pending_external_link_guest", false)
        } else null
        if (pendingExternalLink == null) {
            prefs.edit().remove("pending_external_link_token")
                .remove("pending_external_link_url").remove("pending_external_link_guest").apply()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val tab = currentTab()
                if (findBar != null) { closeFindBar(); return }
                if (isFullscreen) {
                    val fullscreenTab = fullscreenTabId?.let { id -> tabs.firstOrNull { it.id == id } } ?: currentTab()
                    if (fullscreenTab != null) {
                        if (fullscreenTab.engineType == BrowserEngineType.CEFRIUM) fullscreenTab.browserEngine?.exitFullscreen()
                        else fullscreenTab.session?.exitFullScreen()
                        return
                    }
                }
                if (splitTabId != null) { exitSplitScreen(); return }
                if (tab?.canGoBack == true && homeScroll.visibility != View.VISIBLE) { tab.browserEngine?.goBack(); return }
                if (tab != null && tab.lastUrl != HOME_URL) { goHome(); return }
                if (tabs.size > 1) { closeTabAt(activeIndex); return }
                moveTaskToBack(true)
            }
        })
        searchEngine = prefs.getString("search_engine", "Google") ?: "Google"
        runtime = sharedRuntime ?: GeckoRuntime.create(applicationContext).also { sharedRuntime = it }
        purgeRetiredUserPackages()
        registerAiPageReaderBridge()
        val webScheme = when (prefs.getString("web_color_scheme", "system")) {
            "dark" -> GeckoRuntimeSettings.COLOR_SCHEME_DARK
            "light" -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
            else -> GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
        }
        runtime.settings.setPreferredColorScheme(webScheme)
        applyProtectedDns(prefs.getBoolean("protected_dns_enabled", false))
        if (prefs.getBoolean("cloud_sync_enabled", false) && AutoSyncPassphraseStore.has(this)) NovaAutoSync.schedule(this)
        NovaShield.apply(runtime, NovaShield.fromStored(prefs.getString("shield_mode", "STANDARD")))
        applyGeckoCookieBehavior()
        applyGeckoImageBehavior()
        val fontScaleFactors = floatArrayOf(0.9f, 1.0f, 1.15f, 1.3f, 1.5f)
        val savedFontScale = prefs.getInt("font_scale_index", 1).coerceIn(fontScaleFactors.indices)
        runtime.settings.setFontSizeFactor(fontScaleFactors[savedFontScale])
        runtime.settings.setForceUserScalableEnabled(true)
        runtime.setAutocompleteStorageDelegate(object : Autocomplete.StorageDelegate {
            override fun onLoginFetch(domain: String): GeckoResult<Array<Autocomplete.LoginEntry>> {
                if (currentTab()?.let { it.isPrivate || it.isGuest } == true) {
                    return GeckoResult.fromValue(emptyArray<Autocomplete.LoginEntry>())
                }
                val matches = runCatching { passwordVault.all().filter { Uri.parse(it.origin).host.equals(domain, true) } }.getOrDefault(emptyList())
                return GeckoResult.fromValue(matches.toTypedArray())
            }
            override fun onLoginSave(login: Autocomplete.LoginEntry) {
                if (currentTab()?.let { it.isPrivate || it.isGuest } == true) return
                runCatching { passwordVault.save(login) }
                    .onSuccess { runOnUiThread { toast("Login saved securely in Nova Password Manager") } }
                    .onFailure { runOnUiThread { toast("Could not save login securely: ${it?.localizedMessage ?: "Unknown error"}") } }
            }
        })
        runtime.settings.setLoginAutofillEnabled(true)
        NovaBrowserVpnController.initialize(this, runtime)
        NovaBrowserVpnController.setStatusListener(this) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed && ::browserPane.isInitialized) applyVpnRoutingReadiness()
            }
        }
        val retained = retainedTabsForConfiguration
        if (retained != null) {
            aiPageText.putAll(retainedAiPageText.orEmpty())
            aiPageUrl.putAll(retainedAiPageUrl.orEmpty())
            retainedAiPageText = null
            retainedAiPageUrl = null
            tabs.addAll(retained)
            activeIndex = retainedActiveIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
            splitTabId = retainedSplitTabId
            guestContextId = retainedGuestContextId.orEmpty()
            retainedTabsForConfiguration = null
            retainedSplitTabId = null
            retainedGuestContextId = null
        } else {
            val previousGuestContext = prefs.getString("guest_context_id", null)
            if (prefs.getBoolean("guest_context_used", false)) queueGuestContextCleanup(previousGuestContext)
            else queueGuestContextCleanup(null) // Retry cleanup IDs retained from earlier launches.
            guestContextId = "nova-guest-${UUID.randomUUID()}"
            prefs.edit().putString("guest_context_id", guestContextId).putBoolean("guest_context_used", false).commit()
        }
        buildBrowserUi()
        observeWindowLayout()
        if (retained != null) {
            if (tabs.any { it.isGuest }) {
                // Never resume Guest sessions until Tor routing and proxy/WebRTC protections are verified.
                guestTorStartPending = true
                TorGuestRoutingController.start(
                    context = this,
                    onReady = {
                        guestTorStartPending = false
                        if (isFinishing || isDestroyed) return@start
                        for (tab in tabs.filter { it.isGuest }) {
                            val restored = runCatching { ensureSession(tab) }
                            if (restored.isFailure) {
                                closeGuestTabs()
                                toast("Guest Mode could not be restored safely: ${restored.exceptionOrNull()?.localizedMessage ?: "browser initialization failed"}")
                                break
                            }
                        }
                        if (tabs.isEmpty()) {
                            if (!createTab(false, HOME_URL)) {
                                ensureHomeTabRecord()
                                persistTabs()
                            }
                        } else {
                            switchTab(activeIndex)
                        }
                        restoreSplitPane()
                        retryPendingExternalLink()
                    },
                    onFailure = { reason ->
                        guestTorStartPending = false
                        if (isChangingConfigurations) return@start
                        if (tabs.any { it.isGuest }) closeGuestTabs()
                        if (tabs.isEmpty()) {
                            if (!createTab(false, HOME_URL)) {
                                ensureHomeTabRecord()
                                persistTabs()
                            }
                        } else {
                            switchTab(activeIndex)
                        }
                        toast("Guest Mode closed because Tor could not be verified: $reason")
                    }
                )
            } else if (guestTorReservesGeckoRuntime() || BrowserVpnNavigationPolicy.shouldBlockNavigation(
                    NovaBrowserVpnController.isVpnEnabled(), NovaBrowserVpnController.isVpnRoutingReady()
                )) {
                if (splitTabId != null) exitSplitScreen()
                tabs.forEach { tab ->
                    if (tab.browserEngine != null || tab.session != null) closeTabRuntime(tab)
                    tab.needsReload = tab.lastUrl != HOME_URL
                }
                if (TorGuestRoutingController.blocksNormalGeckoSessions()) {
                    // The previous Activity may have started shutdown during rotation.
                    // Take ownership of its completion callback in this Activity.
                    TorGuestRoutingController.stop { runOnUiThread { restoreAfterGuestTorStop() } }
                }
            } else {
                tabs.filter { it.session != null && it.engineType == BrowserEngineType.GECKOVIEW }.forEach { ensureSession(it) }
                if (tabs.isEmpty()) createTab(false, HOME_URL) else switchTab(activeIndex)
                restoreSplitPane()
            }
        } else {
            restoreTabs()
            if (tabs.isEmpty() && !createTab(false, HOME_URL)) {
                ensureHomeTabRecord()
                persistTabs()
            }
            if (tabs.none { it.isGuest } && TorGuestRoutingController.blocksNormalGeckoSessions()) {
                TorGuestRoutingController.stop { runOnUiThread { restoreAfterGuestTorStop() } }
            }
        }
        if (tabs.isEmpty()) ensureHomeTabRecord()
        applyVpnRoutingReadiness()
        if (!launchIntentHandled && intent?.data != null) {
            handleViewIntent(intent)
            launchIntentHandled = true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("nova_launch_intent_handled", launchIntentHandled)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
        launchIntentHandled = true
    }

    /** Opens safe HTTP(S) links sent by other apps in a new tab. */
    private fun handleViewIntent(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_VIEW) return
        val link = incoming.data ?: return
        val scheme = link.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return
        val url = link.toString()
        // Never trust an ownership flag supplied by another app. Only a random token
        // stored in Nova's private preferences can restore a deferred link's context
        // after Activity recreation or process death.
        val ownershipKey = "com.nova.browser.extra.PENDING_LINK_GUEST"
        val tokenKey = "com.nova.browser.extra.PENDING_LINK_TOKEN"
        val suppliedToken = incoming.getStringExtra(tokenKey)
        val storedToken = prefs.getString("pending_external_link_token", null)
        val restoredGuestOwner = DeferredExternalLinkOwnershipPolicy.isTrusted(
            suppliedToken = suppliedToken,
            storedToken = storedToken,
            storedUrl = prefs.getString("pending_external_link_url", null),
            incomingUrl = url
        )
        if (suppliedToken != null && !restoredGuestOwner) {
            prefs.edit().remove("pending_external_link_token")
                .remove("pending_external_link_url")
                .remove("pending_external_link_guest").apply()
        }
        val guestOwned = if (restoredGuestOwner) {
            prefs.getBoolean("pending_external_link_guest", false)
        } else {
            currentTab()?.isGuest == true || guestTorStartPending || TorGuestRoutingController.isReady()
        }
        if (createTab(false, url, isGuest = guestOwned)) {
            pendingExternalLink = null
            pendingExternalLinkGuest = null
            prefs.edit().remove("pending_external_link_token")
                .remove("pending_external_link_url")
                .remove("pending_external_link_guest").apply()
            incoming.data = null
            incoming.removeExtra(tokenKey)
            incoming.removeExtra(ownershipKey)
        } else {
            pendingExternalLink = url
            pendingExternalLinkGuest = guestOwned
            val token = if (restoredGuestOwner) suppliedToken!! else UUID.randomUUID().toString()
            prefs.edit().putString("pending_external_link_token", token)
                .putString("pending_external_link_url", url)
                .putBoolean("pending_external_link_guest", guestOwned).apply()
            incoming.putExtra(tokenKey, token)
            incoming.removeExtra(ownershipKey)
            toast("External link saved · it will open when protected routing is ready")
        }
    }

    private fun retryPendingExternalLink() {
        val url = pendingExternalLink ?: return
        if (!NovaFireClearPolicy.canResumeAfterGeckoClear(
                geckoClearActive = pendingGeckoDataClearActive,
                fireClearActive = pendingFireClearOperationId != null
            )) return
        val guest = pendingExternalLinkGuest == true
        val activeGuest = currentTab()?.isGuest == true
        val torReserved = TorGuestRoutingController.blocksNormalGeckoSessions()
        if (PendingExternalLinkPolicy.shouldDiscardGuestLink(
                pendingForGuest = guest,
                activeTabIsGuest = activeGuest,
                guestStartPending = guestTorStartPending,
                torProxyReserved = torReserved
            )) {
            // A Guest-owned link must never fall through into a Normal/Private tab after Guest exits.
            pendingExternalLink = null
            pendingExternalLinkGuest = null
            prefs.edit().remove("pending_external_link_token")
                .remove("pending_external_link_url")
                .remove("pending_external_link_guest").apply()
            if (intent?.data?.toString() == url) {
                intent?.data = null
                intent?.removeExtra("com.nova.browser.extra.PENDING_LINK_TOKEN")
                intent?.removeExtra("com.nova.browser.extra.PENDING_LINK_GUEST")
            }
            return
        }
        if (!PendingExternalLinkPolicy.canOpen(
                pendingForGuest = guest,
                activeTabIsGuest = activeGuest,
                guestStartPending = guestTorStartPending,
                torProxyReserved = torReserved,
                guestTorReady = TorGuestRoutingController.isReady()
            )) return
        if (BrowserVpnNavigationPolicy.shouldBlockNavigation(
                NovaBrowserVpnController.isVpnEnabled(), NovaBrowserVpnController.isVpnRoutingReady()
            )) return
        if (createTab(false, url, isGuest = guest)) {
            pendingExternalLink = null
            pendingExternalLinkGuest = null
            prefs.edit().remove("pending_external_link_token")
                .remove("pending_external_link_url")
                .remove("pending_external_link_guest").apply()
            if (intent?.data?.toString() == url) {
                intent?.data = null
                intent?.removeExtra("com.nova.browser.extra.PENDING_LINK_TOKEN")
                intent?.removeExtra("com.nova.browser.extra.PENDING_LINK_GUEST")
            }
        }
    }

    private fun observeWindowLayout() {
        lifecycleScope.launch {
            WindowInfoTracker.getOrCreate(this@MainActivity)
                .windowLayoutInfo(this@MainActivity)
                .collect { info ->
                    val fold = info.displayFeatures.filterIsInstance<FoldingFeature>()
                        .firstOrNull { it.isSeparating }
                    applyFoldAwareLayout(fold)
                }
        }
    }

    private fun foldPaneGeometry(): IntArray? {
        val fold = separatingFold?.takeIf { it.isSeparating } ?: return null
        if (!::browserPane.isInitialized || browserPane.width <= 0 || browserPane.height <= 0) return null
        val location = IntArray(2)
        browserPane.getLocationInWindow(location)
        return if (fold.orientation == FoldingFeature.Orientation.VERTICAL) {
            val start = (fold.bounds.left - location[0]).coerceIn(0, browserPane.width)
            val end = (fold.bounds.right - location[0]).coerceIn(start, browserPane.width)
            intArrayOf(start, end - start, browserPane.width - end)
        } else {
            val start = (fold.bounds.top - location[1]).coerceIn(0, browserPane.height)
            val end = (fold.bounds.bottom - location[1]).coerceIn(start, browserPane.height)
            intArrayOf(start, end - start, browserPane.height - end)
        }
    }

    private fun applyFoldAwareLayout(fold: FoldingFeature?) {
        separatingFold = fold
        if (!::browserPane.isInitialized || !::browserView.isInitialized || !::secondaryPane.isInitialized) return
        val sideBySide = if (fold?.isSeparating == true) {
            fold.orientation == FoldingFeature.Orientation.VERTICAL
        } else resources.configuration.screenWidthDp >= 600
        browserPane.orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (secondaryPane.visibility == View.VISIBLE && fold?.isSeparating == true &&
            (browserPane.width <= 0 || browserPane.height <= 0)) {
            browserPane.post { if (!isFinishing && !isDestroyed) applyFoldAwareLayout(separatingFold) }
            return
        }
        val geometry = foldPaneGeometry()
        val secondaryVisible = secondaryPane.visibility == View.VISIBLE
        if (secondaryVisible && geometry != null && fold?.orientation == FoldingFeature.Orientation.VERTICAL) {
            val (primaryWidth, hingeWidth, secondaryWidth) = geometry.toList()
            secondaryPane.layoutParams = LinearLayout.LayoutParams(
                secondaryWidth.coerceAtLeast(1), ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { marginStart = hingeWidth }
            secondaryPane.setPadding(0, 0, 0, 0)
            (activePrimaryEngineView ?: browserView).layoutParams =
                LinearLayout.LayoutParams(primaryWidth.coerceAtLeast(1), ViewGroup.LayoutParams.MATCH_PARENT)
        } else if (secondaryVisible && geometry != null) {
            val (primaryHeight, hingeHeight, secondaryHeight) = geometry.toList()
            secondaryPane.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, secondaryHeight.coerceAtLeast(1)
            ).apply { topMargin = hingeHeight }
            secondaryPane.setPadding(0, 0, 0, 0)
            (activePrimaryEngineView ?: browserView).layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, primaryHeight.coerceAtLeast(1))
        } else if (secondaryVisible && sideBySide) {
            secondaryPane.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            secondaryPane.setPadding(0, 0, 0, 0)
        } else if (secondaryVisible) {
            secondaryPane.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            secondaryPane.setPadding(0, 0, 0, 0)
        } else {
            secondaryPane.layoutParams = LinearLayout.LayoutParams(0, 0)
            (activePrimaryEngineView ?: browserView).layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        refreshActiveEnginePaneLayoutParams()
    }

    private fun refreshActiveEnginePaneLayoutParams() {
        if (::browserPane.isInitialized && ::browserView.isInitialized) {
            (activePrimaryEngineView ?: browserView).layoutParams = enginePaneLayoutParams(browserPane)
        }
        if (::secondaryPane.isInitialized && ::secondaryBrowserView.isInitialized && secondaryPane.visibility == View.VISIBLE) {
            (activeSecondaryEngineView ?: secondaryBrowserView).layoutParams = enginePaneLayoutParams(secondaryPane)
        }
    }

    private fun enginePaneLayoutParams(pane: LinearLayout): LinearLayout.LayoutParams {
        if (pane === browserPane) {
            if (!::secondaryPane.isInitialized || secondaryPane.visibility != View.VISIBLE) {
                return LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            val geometry = foldPaneGeometry()
            if (geometry != null) {
                return if (separatingFold?.orientation == FoldingFeature.Orientation.VERTICAL) {
                    LinearLayout.LayoutParams(geometry[0].coerceAtLeast(1), ViewGroup.LayoutParams.MATCH_PARENT)
                } else {
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, geometry[0].coerceAtLeast(1))
                }
            }
        }
        return if (pane.orientation == LinearLayout.HORIZONTAL) {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
    }

    private fun buildBrowserUi() {
        topChrome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(4))
            NovaBrowserChromeStyle.applyChromeSurface(this@MainActivity, this)
        }
        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addressBar = NovaAddressAutoCompleteTextView(this).apply {
            hint = getString(R.string.address_bar_hint)
            contentDescription = getString(R.string.address_bar_accessibility)
            isSingleLine = true
            setOnEditorActionListener { _, actionId, event ->
                val enterKey = event?.let {
                    it.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                        it.action == android.view.KeyEvent.ACTION_DOWN
                } == true
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_SEARCH || enterKey
                ) {
                    submitAddressBar()
                    true
                } else false
            }
            NovaBrowserChromeStyle.applyAddressField(this@MainActivity, this)
        }
        addressSuggestionsAdapter = ArrayAdapter<BrowserSuggestion>(this, android.R.layout.simple_dropdown_item_1line, mutableListOf())
        // Suggestions are already ranked by Nova; disable Android's second, label-only filter.
        addressBar.threshold = 1
        addressBar.setAdapter(addressSuggestionsAdapter)
        addressBar.setOnItemClickListener { parent, _, position, _ ->
            val selected = parent.getItemAtPosition(position) as? BrowserSuggestion ?: return@setOnItemClickListener
            isSelectingAddressSuggestion = true
            addressBar.setText(selected.url, false)
            addressBar.setSelection(addressBar.text.length)
            addressBar.dismissDropDown()
            isSelectingAddressSuggestion = false
        }
        addressBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isSelectingAddressSuggestion) updateAddressSuggestions(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        addressBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) updateAddressSuggestions(addressBar.text?.toString().orEmpty()) else {
                cancelPendingRemoteSuggestions()
                addressBar.dismissDropDown()
            }
        }
        addressRow.minimumHeight = dp(NovaBrowserChromeStyle.sharedMetrics.addressHeightDp)
        addressRow.addView(addressBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val clearAddress = actionButton(getString(R.string.clear_address_button)) { addressBar.text?.clear(); addressBar.requestFocus() }.apply {
            contentDescription = getString(R.string.clear_address)
            minWidth = dp(NovaBrowserChromeStyle.sharedMetrics.controlMinSizeDp)
            minimumWidth = dp(NovaBrowserChromeStyle.sharedMetrics.controlMinSizeDp)
        }
        addressRow.addView(clearAddress, LinearLayout.LayoutParams(dp(NovaBrowserChromeStyle.sharedMetrics.controlMinSizeDp), ViewGroup.LayoutParams.WRAP_CONTENT))
        val attachSearchButton = actionButton(getString(R.string.attach_search_button)) {
            val query = addressBar.text?.toString().orEmpty()
            val share = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, query) }
            startActivity(Intent.createChooser(share, getString(R.string.attach_search_query)))
        }.apply { contentDescription = getString(R.string.attach_search_query) }
        addressRow.addView(attachSearchButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        searchEngineButton = actionButton(getString(R.string.search_engine_button, searchEngine)) { chooseSearchEngine() }
        searchEngineButton.contentDescription = getString(R.string.search_engine_accessibility, searchEngine)
        searchEngineButton.minWidth = dp(64)
        searchEngineButton.minimumWidth = dp(64)
        addressRow.addView(searchEngineButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val goButton = actionButton(getString(R.string.go)) { submitAddressBar() }
        goButton.minWidth = dp(NovaBrowserChromeStyle.sharedMetrics.controlMinSizeDp)
        goButton.minimumWidth = dp(NovaBrowserChromeStyle.sharedMetrics.controlMinSizeDp)
        addressRow.addView(goButton, LinearLayout.LayoutParams(dp(NovaBrowserChromeStyle.sharedMetrics.controlMinSizeDp), ViewGroup.LayoutParams.WRAP_CONTENT))
        topChrome.addView(addressRow)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            contentDescription = getString(R.string.page_loading_progress)
        }
        topChrome.addView(progressBar, LinearLayout.LayoutParams(-1, dp(2)))

        tabLabel = TextView(this).apply {
            text = getString(R.string.app_name)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            NovaBrowserChromeStyle.applyMetaLabel(
                this@MainActivity, this, NovaBrowserChromeStyle.sharedMetrics.tabLabelTextSizeSp, 5, 5
            )
        }
        pageStatus = TextView(this).apply {
            text = getString(R.string.ready)
            contentDescription = getString(R.string.nova_session_status)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            NovaBrowserChromeStyle.applyMetaLabel(
                this@MainActivity, this, NovaBrowserChromeStyle.sharedMetrics.statusTextSizeSp, 0, 4
            )
        }
        topChrome.addView(tabLabel)
        topChrome.addView(pageStatus)

        browserView = GeckoView(this).also { NovaBrowserChromeStyle.applyEngineSurface(this, it) }
        secondaryBrowserView = GeckoView(this).also { NovaBrowserChromeStyle.applyEngineSurface(this, it) }
        secondaryAddressBar = EditText(this).apply {
            hint = getString(R.string.secondary_address_bar_hint)
            setOnEditorActionListener { _, actionId, event ->
                val enterKey = event?.let {
                    it.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                        it.action == android.view.KeyEvent.ACTION_DOWN
                } == true
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH || enterKey) {
                    navigateSecondary(text.toString())
                    true
                } else false
            }
            NovaBrowserChromeStyle.applyAddressField(this@MainActivity, this)
        }
        val secondaryControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            NovaBrowserChromeStyle.applyChromeSurface(this@MainActivity, this, minimumHeight = true)
            addView(actionButton("Back") { tabs.firstOrNull { it.id == splitTabId }?.browserEngine?.goBack() })
            addView(actionButton("Forward") { tabs.firstOrNull { it.id == splitTabId }?.browserEngine?.goForward() })
            addView(actionButton("Reload") {
                tabs.firstOrNull { it.id == splitTabId }?.let { tab ->
                    val policyUrl = applyHttpsOnlyPolicy(tab.lastUrl) ?: return@let
                    if (policyUrl != tab.lastUrl) loadSecondaryTabUrl(tab, policyUrl)
                    else tab.browserEngine?.reload()
                }
            })
            addView(secondaryAddressBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(actionButton("Go") { navigateSecondary(secondaryAddressBar.text.toString()) })
            addView(actionButton("Close split") { exitSplitScreen() })
        }
        secondaryPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(secondaryControls, LinearLayout.LayoutParams(-1, -2))
            addView(secondaryBrowserView, LinearLayout.LayoutParams(-1, 0, 1f))
            visibility = View.GONE
        }
        activeSecondaryEngineView = secondaryBrowserView
        val wideWindow = resources.configuration.screenWidthDp >= 600
        browserPane = LinearLayout(this).apply {
            orientation = if (wideWindow) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            if (wideWindow) {
                addView(browserView, LinearLayout.LayoutParams(0, -1, 1f))
                addView(secondaryPane, LinearLayout.LayoutParams(0, -1, 1f))
            } else {
                addView(browserView, LinearLayout.LayoutParams(-1, 0, 1f))
                addView(secondaryPane, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        }
        activePrimaryEngineView = browserView
        homePanel = createHomePanel()
        homeScroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false; addView(homePanel, ViewGroup.LayoutParams(-1, -2)) }
        bottomToolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            NovaBrowserChromeStyle.applyChromeSurface(this@MainActivity, this, minimumHeight = true)
        }
        bottomToolbar.addView(iconButton(R.drawable.ic_back, getString(R.string.back)) { goBack() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottomToolbar.addView(iconButton(R.drawable.ic_forward, getString(R.string.forward)) { goForward() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottomToolbar.addView(iconButton(R.drawable.ic_home, getString(R.string.home)) { goHome() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bookmarkButton = iconButton(R.drawable.ic_bookmark_outline, getString(R.string.bookmark_page)) { toggleBookmark() }
        bottomToolbar.addView(bookmarkButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabsButton = actionButton(getString(R.string.tabs_button_count, tabs.size)) { showTabs() }.apply { contentDescription = getString(R.string.tabs_count, tabs.size) }
        bottomToolbar.addView(tabsButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f))
        val menuButton = actionButton(getString(R.string.menu)) { }
        menuButton.setOnClickListener { showOverflowMenu(menuButton) }
        bottomToolbar.addView(menuButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            NovaBrowserChromeStyle.applyEngineSurface(this@MainActivity, this)
            addView(topChrome, LinearLayout.LayoutParams(-1, -2))
            addView(homeScroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(browserPane, LinearLayout.LayoutParams(-1, 0, 1f))
            browserPane.visibility = View.GONE
            addView(bottomToolbar, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.isFocusableInTouchMode = true
        setContentView(root)
        applyFoldAwareLayout(separatingFold)
        root.applySystemBarPadding()
        window.useLightSystemBarIcons()
        root.requestFocus()
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED)
    }

    private fun createHomePanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(getColor(R.color.nova_surface), getColor(R.color.nova_surface_elevated), getColor(R.color.nova_background))
            )
        }
        val dragon = ImageView(this).apply {
            setImageResource(R.drawable.ic_kali_dragon)
            contentDescription = getString(R.string.kali_dragon_artwork)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, 0, 0, dp(8))
        }
        val title = TextView(this).apply {
            text = getString(R.string.home_brand_title)
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.18f
            setTextColor(getColor(R.color.nova_text_primary))
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = getString(R.string.home_tagline)
            textSize = 13f
            setTextColor(getColor(R.color.nova_text_secondary))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(24))
        }
        val shortcutTitle = TextView(this).apply {
            text = getString(R.string.home_quick_access)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            setTextColor(getColor(R.color.nova_text_secondary))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        homeShortcuts = GridLayout(this).apply {
            columnCount = if (resources.configuration.screenWidthDp >= 600) 4 else 2
            useDefaultMargins = false
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }
        renderHomeShortcuts()
        val addShortcut = actionButton(getString(R.string.add_shortcut)) { showHomeShortcutEditor(null, -1) }
        addShortcut.contentDescription = getString(R.string.add_home_shortcut)
        panel.addView(dragon)
        panel.addView(title)
        panel.addView(subtitle)
        panel.addView(shortcutTitle)
        panel.addView(homeShortcuts)
        panel.addView(addShortcut)
        return panel
        /* original static shortcut row replaced by user-managed tiles */

    }

    private fun readHomeShortcuts(): List<HomeShortcut> {
        val raw = prefs.getString("home_shortcuts", null) ?: return listOf(
            HomeShortcut("Google", "https://www.google.com"),
            HomeShortcut("YouTube", "https://m.youtube.com"),
            HomeShortcut("Wikipedia", "https://www.wikipedia.org"),
            HomeShortcut("GitHub", "https://github.com")
        )
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index -> array.getJSONObject(index).let { HomeShortcut(it.optString("title"), it.optString("url")) } }
        }.getOrDefault(emptyList())
    }

    private fun saveHomeShortcuts(items: List<HomeShortcut>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("title", it.title).put("url", it.url)) }
        prefs.edit().putString("home_shortcuts", array.toString()).apply()
        renderHomeShortcuts()
    }

    private fun renderHomeShortcuts() {
        if (!::homeShortcuts.isInitialized) return
        homeShortcuts.removeAllViews()
        if (currentTab()?.isGuest == true) {
            homeShortcuts.visibility = View.GONE
            return
        }
        homeShortcuts.columnCount = if (resources.configuration.screenWidthDp >= 600) 4 else 2
        readHomeShortcuts().forEachIndexed { index, shortcut ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumWidth = dp(48)
                minimumHeight = dp(72)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply { setColor(getColor(R.color.nova_surface_elevated)); cornerRadius = dp(14).toFloat(); setStroke(dp(1), getColor(R.color.nova_outline)) }
                isFocusable = true
                contentDescription = getString(R.string.shortcut_tile_accessibility, shortcut.title, Uri.parse(shortcut.url).host ?: shortcut.url)
                setOnClickListener { navigate(shortcut.url) }
                setOnLongClickListener { showHomeShortcutActions(shortcut, index); true }
            }
            val faviconFrame = android.widget.FrameLayout(this).apply {
                background = GradientDrawable().apply { setColor(getColor(R.color.nova_accent)); cornerRadius = dp(12).toFloat() }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val faviconFallback = TextView(this).apply {
                text = shortcut.title.trim().take(1).uppercase(Locale.ROOT).ifBlank { "•" }
                gravity = Gravity.CENTER
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.nova_text_primary))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val faviconImage = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                visibility = View.GONE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                tag = shortcut.url
            }
            faviconFrame.addView(faviconFallback, android.widget.FrameLayout.LayoutParams(-1, -1))
            faviconFrame.addView(faviconImage, android.widget.FrameLayout.LayoutParams(-1, -1))
            // HttpURLConnection favicons bypass GeckoView's browser proxy. Do not issue
            // direct favicon requests while 1VPN is enabled or Guest owns the runtime.
            val faviconNetworkAllowed = !NovaBrowserVpnController.isVpnEnabled() &&
                !TorGuestRoutingController.blocksNormalGeckoSessions()
            if (faviconNetworkAllowed && currentTab()?.let { !it.isPrivate && !it.isGuest } == true) {
                ShortcutFaviconLoader.load(this, shortcut.url) { bitmap ->
                    if (bitmap != null && faviconImage.tag == shortcut.url && !isDestroyed) {
                        faviconImage.setImageBitmap(bitmap)
                        faviconImage.visibility = View.VISIBLE
                        faviconFallback.visibility = View.GONE
                    }
                }
            }
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
            details.addView(TextView(this).apply { text = shortcut.title; textSize = 13f; maxLines = 1; setTextColor(getColor(R.color.nova_text_primary)) })
            details.addView(TextView(this).apply { text = Uri.parse(shortcut.url).host ?: shortcut.url; textSize = 10f; maxLines = 1; setTextColor(getColor(R.color.nova_text_secondary)) })
            tile.addView(faviconFrame, LinearLayout.LayoutParams(dp(40), dp(40)))
            tile.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            homeShortcuts.addView(tile, GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(5), dp(5), dp(5), dp(5))
            })
        }
    }

    private fun showHomeShortcutActions(shortcut: HomeShortcut, index: Int) {
        showBottomSheetMenu(shortcut.title, listOf(
            getString(R.string.edit_shortcut) to { showHomeShortcutEditor(shortcut, index) },
            getString(R.string.remove_shortcut) to {
                showBottomSheetMenu(getString(R.string.remove_shortcut), listOf(
                    getString(R.string.cancel) to {},
                    getString(R.string.remove) to {
                        val previous = readHomeShortcuts()
                        saveHomeShortcuts(previous.filterIndexed { i, _ -> i != index })
                        showUndoSnackbar(getString(R.string.shortcut_removed), getString(R.string.undo)) { saveHomeShortcuts(previous) }
                    }
                ), getString(R.string.remove_shortcut_confirm, shortcut.title))
            }
        ))
    }

    private fun showHomeShortcutEditor(existing: HomeShortcut?, index: Int) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val title = EditText(this).apply { hint = getString(R.string.shortcut_title); setText(existing?.title.orEmpty()) }
        val url = EditText(this).apply { hint = getString(R.string.shortcut_url); setText(existing?.url.orEmpty()); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        form.addView(title); form.addView(url)
        showBottomSheetForm(getString(if (existing == null) R.string.add_shortcut else R.string.edit_shortcut), form, getString(R.string.save)) {
            val resolved = BrowserUrlResolver.resolve(url.text.toString(), searchEngine)
            if (resolved == null || !(resolved.startsWith("https://") || resolved.startsWith("http://"))) {
                toast(getString(R.string.invalid_shortcut_url)); false
            } else {
                val label = title.text.toString().trim().ifBlank { Uri.parse(resolved).host ?: getString(R.string.website) }
                val items = readHomeShortcuts().toMutableList()
                val shortcut = HomeShortcut(label, resolved)
                if (index in items.indices) items[index] = shortcut else items.add(shortcut)
                saveHomeShortcuts(items)
                true
            }
        }
    }

    private fun showHomeScreen() {
        // A Guest session must never reveal a retained Normal split pane.
        if (currentTab()?.isGuest == true && splitTabId != null) exitSplitScreen()
        homeScroll.visibility = View.VISIBLE
        if (splitTabId != null) {
            browserPane.visibility = View.VISIBLE
            browserView.visibility = View.GONE
            secondaryPane.visibility = View.VISIBLE
            applyFoldAwareLayout(separatingFold)
        } else browserPane.visibility = View.GONE
        addressBar.setText("")
        dismissAddressBarInput()
        val activeTab = currentTab()
        if (activeTab != null) updateTabChrome(activeTab)
        resetPageStatus(when {
            activeTab?.isGuest == true -> getString(R.string.guest_session_status)
            activeTab?.isPrivate == true -> getString(R.string.private_session_status)
            else -> getString(R.string.ready)
        }, 0)
        searchEngineButton.text = getString(R.string.search_engine_button, searchEngine)
        searchEngineButton.contentDescription = getString(R.string.search_engine_accessibility, searchEngine)
        renderHomeShortcuts()
        homeShortcuts.visibility = if (currentTab()?.isGuest == true || !prefs.getBoolean("show_home_shortcuts", true)) View.GONE else View.VISIBLE
    }

    private fun dismissAddressBarInput() {
        addressBar.clearFocus()
        root.requestFocus()
        val inputMethodManager = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        inputMethodManager?.hideSoftInputFromWindow(addressBar.windowToken, 0)
    }

    private fun goHome() {
        currentTab()?.let { tab ->
            tab.browserEngine?.stop()
            deactivateTabRuntime(tab)
            tab.lastUrl = HOME_URL
            tab.title = "Nova Home"
            tab.needsReload = false
            if (!tab.isPrivate && !tab.isGuest) persistTabs()
        }
        showHomeScreen()
    }

    private fun showBrowserPage() {
        homeScroll.visibility = View.GONE
        browserPane.visibility = View.VISIBLE
        browserView.visibility = if (activePrimaryEngineView == null || activePrimaryEngineView === browserView) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun toolbarScroll(row: LinearLayout) =
        android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            addView(row, android.view.ViewGroup.LayoutParams(-2, dp(NovaBrowserChromeStyle.sharedMetrics.toolbarMinHeightDp)))
        }

    private fun iconButton(icon: Int, description: String, action: () -> Unit) = android.widget.ImageButton(this).apply {
        setImageResource(icon)
        contentDescription = description
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        setBackgroundResource(android.R.color.transparent)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        minimumWidth = dp(NovaBrowserChromeStyle.sharedMetrics.controlMinSizeDp)
        minimumHeight = dp(NovaBrowserChromeStyle.sharedMetrics.controlMinSizeDp)
        setOnClickListener { action() }
    }

    private fun showUndoSnackbar(message: String, actionLabel: String, action: () -> Unit) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { setColor(getColor(R.color.nova_surface_elevated)); cornerRadius = dp(14).toFloat(); setStroke(dp(1), getColor(R.color.nova_outline)) }
        }
        content.addView(TextView(this).apply { text = message; textSize = 13f; setTextColor(getColor(R.color.nova_text_primary)) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val undo = actionButton(actionLabel) { }
        content.addView(undo)
        val popup = PopupWindow(content, WindowManager.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            isOutsideTouchable = false
            elevation = dp(8).toFloat()
        }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val dismiss = Runnable { if (popup.isShowing) popup.dismiss() }
        popup.setOnDismissListener { activeUndoPopups.remove(popup); handler.removeCallbacks(dismiss) }
        undo.setOnClickListener { handler.removeCallbacks(dismiss); popup.dismiss(); action() }
        activeUndoPopups.add(popup)
        popup.showAtLocation(window.decorView, Gravity.BOTTOM, dp(12), dp(68))
        handler.postDelayed(dismiss, 5000)
    }

    private fun showBottomSheetMenu(title: String, actions: List<Pair<String, () -> Unit>>, message: String? = null) {
        val dialog = Dialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
            background = GradientDrawable().apply {
                setColor(getColor(R.color.nova_surface))
                cornerRadii = floatArrayOf(dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        content.addView(TextView(this).apply {
            text = title
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.nova_text_primary))
            setPadding(dp(4), 0, dp(4), dp(10))
        })
        if (!message.isNullOrBlank()) content.addView(TextView(this).apply {
            text = message
            textSize = 12f
            setTextColor(getColor(R.color.nova_text_secondary))
            setPadding(dp(4), 0, dp(4), dp(12))
        })
        actions.forEach { (label, action) ->
            content.addView(actionButton(label) { dialog.dismiss(); action() }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        dialog.setContentView(scroll)
        dialog.setCanceledOnTouchOutside(true)
        activeBottomSheetDialogs.add(dialog)
        dialog.setOnDismissListener { activeBottomSheetDialogs.remove(dialog) }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.72f).toInt())
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.35f }
        }
    }

    private fun showBottomSheetForm(title: String, form: View, primaryLabel: String, onPrimary: () -> Boolean) {
        val dialog = Dialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
            background = GradientDrawable().apply {
                setColor(getColor(R.color.nova_surface))
                cornerRadii = floatArrayOf(dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        content.addView(TextView(this).apply {
            text = title; textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.nova_text_primary)); setPadding(dp(4), 0, dp(4), dp(12))
        })
        content.addView(form, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        actions.addView(actionButton(getString(R.string.cancel)) { dialog.dismiss() })
        actions.addView(actionButton(primaryLabel) { if (onPrimary()) dialog.dismiss() })
        content.addView(actions)
        dialog.setContentView(ScrollView(this).apply { isFillViewport = true; addView(content) })
        dialog.setCanceledOnTouchOutside(true)
        activeBottomSheetDialogs.add(dialog)
        dialog.setOnDismissListener { activeBottomSheetDialogs.remove(dialog) }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.78f).toInt())
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.35f }
        }
    }

    private fun showOverflowMenu(anchor: View) {
        val tab = currentTab() ?: return
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (tab.isGuest) {
            actions += "Search tabs" to { showTabSearch() }
            actions += getString(R.string.new_guest_tab) to { createGuestTab() }
            actions += getString(R.string.guest_settings) to { showSettings() }
            actions += getString(R.string.exit_guest_mode) to { confirmExitGuestMode() }
        } else {
            actions += getString(R.string.new_normal_tab) to { createTab(false, HOME_URL) }
            actions += getString(R.string.new_private_tab) to { createTab(true, HOME_URL) }
            actions += getString(R.string.new_guest_tab) to { createGuestTab() }
            actions += getString(R.string.split_view) to { chooseSplitTab() }
            actions += getString(R.string.bookmarks) to { showBookmarks() }
            if (!tab.isPrivate) actions += getString(R.string.history) to { showHistory() }
        if (!tab.isPrivate) actions += "Clear browsing data" to { showClearBrowsingData() }
            if (!tab.isPrivate) actions += "Fire · clear tabs & site data" to { confirmFireClear() }
            actions += "Print / Save as PDF" to { printCurrentPage() }
            actions += "Site dark mode" to { cycleSiteDarkMode() }
            actions += (if (prefs.getBoolean("https_only_mode", false)) "HTTPS-Only · on" else "HTTPS-Only · off") to {
                val next = !prefs.getBoolean("https_only_mode", false)
                prefs.edit().putBoolean("https_only_mode", next).apply()
                toast(if (next) "HTTPS-Only enabled" else "HTTPS-Only disabled")
            }
            if (!tab.isPrivate) actions += getString(R.string.downloads) to { showDownloads() }
            actions += getString(if (tab.isLoading) R.string.stop_loading else R.string.reload_page) to { toggleStopReload() }
            actions += getString(R.string.find_in_page) to { showFindBar() }
            actions += "Pop out video" to { requestUserPictureInPicture() }
            val shortcutUrl = if (tab.lastUrl.startsWith("about:reader")) tab.readerOriginalUrl ?: tab.lastUrl else tab.lastUrl
            if (!tab.isPrivate && !tab.isGuest &&
                (shortcutUrl.startsWith("https://") || shortcutUrl.startsWith("http://"))
            ) {
                actions += "Add to Home screen" to { addCurrentSiteToHomeScreen() }
            }
            actions += getString(if (tab.desktopSite) R.string.mobile_site else R.string.desktop_site) to { toggleDesktopSite() }
            actions += getString(if (tab.readerOriginalUrl != null) R.string.exit_reader_mode else R.string.reader_mode) to { if (tab.readerOriginalUrl != null) exitReaderMode() else openReaderMode() }
            actions += getString(R.string.translate_page) to { translatePage() }
        actions += "Quick settings" to { showQuickSettings() }
            actions += getString(R.string.share_page) to { sharePage() }
            actions += getString(R.string.nova_shield) to { showShieldSettings() }
            actions += getString(R.string.ai_assistants) to { showAiAssistant() }
            if (!tab.isPrivate && !tab.isGuest) actions += "Nova Hub" to {
                startActivity(Intent(this, NovaPhase2HubActivity::class.java).apply {
                    val hubUrl = if (tab.lastUrl.startsWith("about:reader")) tab.readerOriginalUrl ?: tab.lastUrl else tab.lastUrl
                    putExtra(NovaPhase2HubActivity.EXTRA_URL, hubUrl)
                    putExtra(NovaPhase2HubActivity.EXTRA_TITLE, tab.title)
                    putExtra(NovaPhase2HubActivity.EXTRA_ENGINE, tab.engineType.name)
                })
            }
            actions += "Search tabs" to { showTabSearch() }
            actions += getString(R.string.password_manager) to { showPasswordVault() }
            if (tab?.engineType == BrowserEngineType.CEFRIUM) {
                actions += "Fill saved login" to { fillSavedLoginForCurrentCefriumTab() }
            }
            actions += "Site Settings" to { showSiteSettingsHub() }
            actions += getString(R.string.accessibility_settings) to { showAccessibilitySettings() }
            actions += getString(R.string.protected_dns) to { showProtectedDnsSettings() }
            actions += getString(R.string.vpn_firewall) to { startActivity(Intent(this, NovaVpnFirewallActivity::class.java)) }
            actions += getString(R.string.settings_title) to { showSettings() }
        }
        showBottomSheetMenu(getString(R.string.browser_menu), actions)
    }

    private fun registerPromptDialog(dialog: AlertDialog, onDismiss: () -> Unit) {
        activePromptDialogs.add(dialog)
        dialog.setOnDismissListener {
            activePromptDialogs.remove(dialog)
            onDismiss()
        }
        dialog.show()
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        contentDescription = label
        textSize = 11f
        isAllCaps = false
        minWidth = dp(48)
        minimumWidth = dp(NovaBrowserChromeStyle.sharedMetrics.controlMinSizeDp)
        minimumHeight = dp(NovaBrowserChromeStyle.sharedMetrics.controlMinSizeDp)
        setPadding(dp(7), 0, dp(7), 0)
        setOnClickListener { action() }
    }

    /** Keep a selectable home tab record while protected routing is still coming up. */
    private fun ensureHomeTabRecord() {
        if (tabs.isNotEmpty()) return
        val tab = BrowserTab(null, isPrivate = false, lastUrl = HOME_URL)
        tab.engineType = resolveEngineForTab(tab)
        tabs.add(tab)
        activeIndex = 0
        if (::addressBar.isInitialized && !addressBar.hasFocus()) addressBar.setText(HOME_URL)
        updateTabChrome(tab)
        showHomeScreen()
    }

    private fun createTab(isPrivate: Boolean, url: String, isGuest: Boolean = false): Boolean {
        if (pendingGeckoDataClearActive) {
            toast(if (isGuest) "Guest Mode cannot start while GeckoView data deletion is running" else "Wait for GeckoView data deletion to finish before opening another tab")
            return false
        }
        if (isGuest && !TorGuestRoutingController.isReady()) {
            toast("Guest Mode waits until Nova verifies Tor routing")
            return false
        }
        if (BrowserVpnNavigationPolicy.shouldBlockNavigation(
                NovaBrowserVpnController.isVpnEnabled(), NovaBrowserVpnController.isVpnRoutingReady()
            )) {
            toast("New tabs are paused until Nova confirms protected routing")
            return false
        }
        if (isGuest && splitTabId != null) exitSplitScreen()
        cancelPendingRemoteSuggestions()
        if (currentTab()?.isGuest == true && !isGuest) {
            toast("Normal and Private tabs are unavailable in Guest Mode")
            return false
        }
        val tab = BrowserTab(null, isPrivate, isGuest, lastUrl = url, desktopSite = isDesktopUrl(url))
        tab.engineType = resolveEngineForTab(tab)
        if (GuestTorIsolationPolicy.shouldBlockNonGuestSession(tab.isGuest, guestTorReservesGeckoRuntime())) {
            toast("Normal and Private tabs are paused while Guest Tor routing is connecting, active, or disconnecting")
            return false
        }
        deactivateTabRuntime(currentTab())
        tabs.add(tab)
        activeIndex = tabs.lastIndex
        activateTab(tab)
        tab.lastAccess = System.nanoTime()
        if (!addressBar.hasFocus()) addressBar.setText(url)
        updateTabChrome(tab)
        if (!isPrivate && !isGuest) persistTabs()
        if (url == HOME_URL) showHomeScreen() else navigate(url)
        enforceSessionBudget()
        return true
    }

    private fun createGuestTab() {
        if (pendingGeckoDataClearActive) {
            toast("Guest Mode cannot start while GeckoView data deletion is running")
            return
        }
        if (guestTorStartPending) {
            toast("Guest Tor is already connecting")
            return
        }
        if (TorGuestRoutingController.blocksNormalGeckoSessions() && !TorGuestRoutingController.isReady()) {
            toast("Guest Tor is still disconnecting; wait for the proxy reset")
            return
        }
        if (!GuestTorIsolationPolicy.canStart(tabs.any { it.isPrivate })) {
            toast("Close Private tabs before starting Guest Mode. Guest Tor uses GeckoView's shared network proxy.")
            return
        }
        // Invalidate pending Normal-profile Downloads folder results as soon as Guest is requested.
        guestModeEpoch++
        if (splitTabId != null) exitSplitScreen()
        // GeckoView's proxy extension is runtime-wide rather than per-session. Suspend
        // every persistent Normal engine session before enabling Guest Tor, including
        // Cefrium tabs, so no Normal page continues background activity during Guest.
        // Navigation state is retained and sessions are recreated only after Tor reset.
        deactivateTabRuntime(currentTab())
        tabs.filter { tab ->
            GuestTorIsolationPolicy.shouldSuspendNormalTab(tab.isGuest, tab.isPrivate) &&
                (tab.browserEngine != null || tab.session != null)
        }.forEach { tab ->
            closeTabRuntime(tab)
            tab.needsReload = tab.lastUrl != HOME_URL
        }
        tabSearchInputDialog?.dismiss()
        tabSearchInputDialog = null
        tabSearchResultsDialog?.dismiss()
        tabSearchResultsDialog = null
        guestTorStartPending = true
        pageStatus.text = "Guest Mode · connecting to Tor…"
        TorGuestRoutingController.start(
            context = this,
            onReady = {
                guestTorStartPending = false
                if (isFinishing || isDestroyed) {
                    TorGuestRoutingController.stop { /* a recreated Activity resumes only after the extension confirms Tor is off */ }
                    return@start
                }
                if (!prefs.edit().putBoolean("guest_context_used", true).commit()) {
                    if (tabs.any { it.isGuest }) closeGuestTabs()
                    else TorGuestRoutingController.stop { runOnUiThread { restoreAfterGuestTorStop() } }
                    pageStatus.text = "Guest Mode unavailable · could not save cleanup state"
                    toast("Guest Mode could not start safely because its cleanup marker could not be saved")
                    return@start
                }
                val existingGuestIds = tabs.filter { it.isGuest }.map { it.id }.toSet()
                val previousTabId = currentTab()?.id
                val creation = runCatching { createTab(isPrivate = false, url = HOME_URL, isGuest = true) }
                val newGuestIds = GuestTorRoutingPolicy.newlyCreatedGuestTabIds(
                    existingGuestIds, tabs.filter { it.isGuest }.map { it.id }
                )
                if (creation.getOrDefault(false) && newGuestIds.isNotEmpty()) {
                    toast("Guest Mode started · Tor routing active")
                    retryPendingExternalLink()
                } else {
                    // Roll back only tabs created by this attempt; never tear down an existing Guest session.
                    newGuestIds.forEach { id ->
                        val failedIndex = tabs.indexOfFirst { it.id == id }
                        if (failedIndex >= 0) {
                            val failedTab = tabs.removeAt(failedIndex)
                            runCatching { closeTabRuntime(failedTab) }
                            failedTab.thumbnail?.recycle()
                            failedTab.thumbnail = null
                        }
                    }
                    // Restore the prior selection before starting asynchronous Tor
                    // shutdown. The completion callback can then safely resume it, including
                    // when 1VPN is still connecting and session creation remains gated.
                    val restoredIndex = previousTabId?.let { id -> tabs.indexOfFirst { it.id == id } }
                        ?.takeIf { it >= 0 }
                        ?: activeIndex.coerceAtMost(tabs.lastIndex).coerceAtLeast(0)
                    if (tabs.isNotEmpty()) activeIndex = restoredIndex
                    if (existingGuestIds.isNotEmpty()) {
                        if (tabs.isNotEmpty()) runCatching { switchTab(activeIndex.coerceIn(tabs.indices)) }
                    } else {
                        cleanupGuestContextIfIdle()
                    }
                    val reason = creation.exceptionOrNull()?.localizedMessage
                        ?: "Guest tab creation was blocked before a tab could be opened"
                    toast("Guest Mode could not start: $reason")
                }
            },
            onFailure = { reason ->
                guestTorStartPending = false
                if (isChangingConfigurations) return@start
                if (tabs.any { it.isGuest }) {
                    toast("Tor connection lost. Closing Guest Mode to prevent direct traffic.")
                    closeGuestTabs()
                } else {
                    TorGuestRoutingController.stop { runOnUiThread { restoreAfterGuestTorStop() } }
                    pageStatus.text = "Guest Mode unavailable · Tor required"
                    toast("Guest Mode blocked: Tor is unavailable. No direct connection was made. $reason")
                }
            }
        )
    }

    private fun isDesktopUrl(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return false
        return host in desktopSites
    }

    private fun tabKey(session: GeckoSession): String = tabs.firstOrNull { it.session === session }?.id ?: ""

    private fun ensureSession(tab: BrowserTab): GeckoSession {
        check(!GuestTorIsolationPolicy.shouldBlockNonGuestSession(tab.isGuest, guestTorReservesGeckoRuntime())) {
            "Normal and Private sessions are paused while Guest Tor owns the shared proxy"
        }
        check(!BrowserVpnNavigationPolicy.shouldBlockNavigation(
            NovaBrowserVpnController.isVpnEnabled(), NovaBrowserVpnController.isVpnRoutingReady()
        )) { "Gecko sessions are paused until 1VPN confirms protected routing" }
        check(!pendingGeckoDataClearActive && tab.id !in pendingGeckoDataClearTabIds) {
            "Browsing-data deletion is still running for this tab"
        }
        tab.session?.let { existing ->
            if (tab.browserEngine !is GeckoViewBrowserEngine) {
                val engine = GeckoViewBrowserEngine(existing, browserView)
                tab.browserEngine = engine
                engine.installDelegates { configureSession(it, tab) }
            }
            return existing
        }
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(tab.isPrivate || tab.isGuest)
            .contextId(if (tab.isGuest) guestContextId else null)
            .userAgentMode(if (tab.desktopSite) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(if (tab.desktopSite) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .allowJavascript(isJavaScriptAllowed(BrowserEngineType.GECKOVIEW, tab.lastUrl))
            .build()
        val session = GeckoSession(settings)
        tab.session = session
        val engine = GeckoViewBrowserEngine(session, browserView)
        tab.browserEngine = engine
        engine.installDelegates { configureSession(it, tab) }
        session.open(runtime)
        attachAiPageReader(session, tab)
        if (tab.needsReload && tab.lastUrl != HOME_URL) {
            val policyUrl = applyHttpsOnlyPolicy(tab.lastUrl)
            if (policyUrl != null) {
                tab.lastUrl = policyUrl
                engine.loadUrl(policyUrl)
            }
            tab.needsReload = false
        }
        return session
    }

    @OptIn(CefriumExperimental::class)
    private fun ensureCefriumEngine(tab: BrowserTab, existingBrowser: CefriumBrowser? = null): CefriumBrowserEngine {
        check(!GuestTorIsolationPolicy.shouldBlockNonGuestSession(tab.isGuest, guestTorReservesGeckoRuntime())) {
            "Normal and Private Cefrium engines are paused while Guest Tor owns the shared proxy"
        }
        check(!BrowserVpnNavigationPolicy.shouldBlockNavigation(
            NovaBrowserVpnController.isVpnEnabled(), NovaBrowserVpnController.isVpnRoutingReady()
        )) { "Cefrium engines are paused until 1VPN confirms protected routing" }
        retryRetiredCefriumProfileDeletes()
        (tab.browserEngine as? CefriumBrowserEngine)?.let { return it }
        check(!tab.isPrivate && !tab.isGuest) { "Private and Guest tabs must use GeckoView" }
        check(!guestTorReservesGeckoRuntime()) { "Normal Cefrium sessions are paused while Guest Tor owns the browser network context" }
        val restoredUrl = tab.lastUrl
        val policyUrl = applyHttpsOnlyPolicy(restoredUrl)
        if (policyUrl == null) {
            tab.needsReload = false
            tab.cefriumNavigationState = null
        } else if (policyUrl != restoredUrl) {
            tab.lastUrl = policyUrl
            tab.needsReload = true
            tab.cefriumNavigationState = null
        }
        val profile = CefriumProfile.get(enginePreferences.cefriumProfileName())
        val requestPolicy = CefriumRequestPolicy(prefs)
        // Install before creating the tab browser; this profile hook retains only preferences.
        profile.setResourceInterceptor(CefriumProfileResourceInterceptor(requestPolicy))
        val restoredNavigationState = tab.cefriumNavigationState?.let { CefriumTabStateCipher.decrypt(it, enginePreferences.cefriumProfileName()) }
        val engine = CefriumBrowserEngine(
            activity = this,
            profile = profile,
            initialNavigationState = restoredNavigationState,
            listener = BrowserEngineListener { state -> runOnUiThread { applyCefriumState(tab, state) } },
            onDownloadCompleted = { download ->
                runOnUiThread {
                    cefriumDownloads.registerCompleted(
                        download.url, download.fileName, download.mimeType, download.savedUri, download.bytes
                    )
                    toast("Downloaded ${download.fileName}")
                }
            },
            onFindResult = { activeMatch, totalMatches -> runOnUiThread {
                setPageStatus(if (totalMatches > 0) getString(R.string.find_result_count, activeMatch, totalMatches) else getString(R.string.find_no_matches), 2)
            } },
            onFullscreenChanged = { fullScreen -> runOnUiThread { setBrowserFullscreen(fullScreen, tab) } },
            onPopupRequested = { popup, popupUrl, userGesture ->
                if (!siteSettingAllowed(BrowserEngineType.CEFRIUM, tab.lastUrl, "popups", false)) { popup.close(); true }
                else handleCefriumPopup(tab, popup, popupUrl, userGesture)
            },
            onPopupClosed = { popup -> handleCefriumPopupClosed(popup) },
            onExternalNavigation = { url, hasUserGesture -> openExternalUrlFromCefrium(tab, url, hasUserGesture) },
            onEngineRoutingRequired = { url, method -> routeCefriumNavigation(tab, url, method) },
            onHttpAuthRequest = { host, realm, isProxy, respond -> showCefriumHttpAuthPrompt(host, realm, isProxy, respond) },
            onContextMenu = { menu -> showCefriumContextMenu(tab, menu) },
            onBeforeUnloadDialog = { message, isReload, answer -> showCefriumBeforeUnloadPrompt(message, isReload, answer) },
            onPermissionRequestCanceled = { runOnUiThread { cancelCefriumPermissionPrompt(tab.id) } },
            desktopMode = tab.desktopSite,
            existingBrowser = existingBrowser,
            javaScriptEnabledForUrl = { url -> isJavaScriptAllowed(BrowserEngineType.CEFRIUM, url) },
            autoplayAllowedForUrl = { url -> siteSettingAllowed(BrowserEngineType.CEFRIUM, url, "autoplay", false) },
            adBlockingEnabledForUrl = { url -> siteSettingAllowed(BrowserEngineType.CEFRIUM, url, "ads", true) },
            soundAllowedForUrl = { url -> siteSettingAllowed(BrowserEngineType.CEFRIUM, url, "sound", true) },
            requestPolicy = requestPolicy,
            onPermissionRequest = { origin, permissions, respond ->
                runOnUiThread {
                    if (tabs.any { it.id == tab.id }) showCefriumPermissionPrompt(tab.id, origin, permissions.toSet(), respond)
                    else respond(false)
                }
            }
        )
        applyCefriumPermissionSettings(tab, engine)
        tab.browserEngine = engine
        tab.cefriumNavigationState = null // The live browser now owns the restored state.
        if (engine.didRestoreInitialNavigationState) {
            tab.needsReload = false
        } else if ((restoredNavigationState != null || tab.needsReload) && tab.lastUrl != HOME_URL) {
            // A corrupt/old snapshot must never leave a restored tab blank.
            engine.loadUrl(tab.lastUrl)
            tab.needsReload = false
        }
        return engine
    }

    private fun handleCefriumPopupClosed(popup: CefriumBrowser) {
        val index = tabs.indexOfFirst { (it.browserEngine as? CefriumBrowserEngine)?.ownsBrowser(popup) == true }
        if (index >= 0) closeTabAt(index)
    }

    private fun openExternalUrlFromCefrium(initiator: BrowserTab, url: String, hasUserGesture: Boolean): Boolean {
        if (tabs.none { it.id == initiator.id } || initiator.engineType != BrowserEngineType.CEFRIUM) return true
        if (!hasUserGesture) {
            toast("Blocked an external-app link without a user gesture")
            return true
        }
        val parsedIntent = runCatching {
            if (url.startsWith("intent:", true)) Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }.getOrNull() ?: return true
        val fallbackUrl = parsedIntent.getStringExtra("browser_fallback_url")
        val destination = parsedIntent.data ?: return true
        val scheme = destination.scheme?.lowercase() ?: return true
        if (scheme in setOf("file", "content", "javascript", "intent")) return true
        // Never forward page-controlled components, selectors, actions, or extras to Android.
        val safeIntent = Intent(Intent.ACTION_VIEW, destination).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            parsedIntent.type?.let { setDataAndType(destination, it) }
        }
        return try {
            startActivity(safeIntent)
            true
        } catch (_: Exception) {
            val safeFallback = fallbackUrl?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
            if (safeFallback != null && tabs.any { it.id == initiator.id }) loadTabUrl(initiator, safeFallback)
            else toast("No installed app can open this link")
            true
        }
    }

    private fun showCefriumContextMenu(tab: BrowserTab, menu: CefriumContextMenu): Boolean {
        val link = menu.linkUrl.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val source = menu.sourceUrl.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val selection = menu.selectionText.takeIf { it.isNotBlank() }
        val page = menu.pageUrl.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        val openTarget = link ?: source
        if (openTarget != null) actions += getString(R.string.open_in_new_tab) to { createTab(false, openTarget) }
        if (link != null) actions += getString(R.string.open_in_private_tab) to { createTab(true, link) }
        if (link != null) actions += getString(R.string.copy_link) to {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(getString(R.string.link), link))
            toast(getString(R.string.link_copied))
        }
        if (source != null) actions += "Copy media URL" to {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Media URL", source))
            toast("Media URL copied")
        }
        if (source != null) actions += getString(R.string.save_image) to {
            val engine = tab.browserEngine as? CefriumBrowserEngine
            if (engine != null) runCatching { engine.startDownload(source) }
                .onSuccess { toast(getString(R.string.image_download_started)) }
                .onFailure { toast(getString(R.string.image_save_failed)) }
            else toast("This tab is no longer available")
        }
        if (selection != null) actions += "Copy selected text" to {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Selected text", selection))
            toast("Text copied")
        }
        val shareText = selection ?: openTarget ?: page
        if (shareText != null) actions += getString(R.string.share_link) to {
            val share = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, shareText) }
            startActivity(Intent.createChooser(share, getString(R.string.share_link)))
        }
        menu.cancel()
        if (actions.isEmpty()) return false
        showBottomSheetMenu(
            selection?.take(60) ?: link?.let { Uri.parse(it).host } ?: source?.let { Uri.parse(it).lastPathSegment } ?: getString(R.string.link_actions),
            actions
        )
        return true
    }

    private fun showCefriumBeforeUnloadPrompt(message: String, isReload: Boolean, answer: (Boolean) -> Unit) {
        var completed = false
        fun finish(leave: Boolean) { if (!completed) { completed = true; answer(leave) } }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isReload) "Reload page?" else "Leave page?")
            .setMessage(message.ifBlank { "This page may have unsaved changes." })
            .setPositiveButton(if (isReload) "Reload" else "Leave") { _, _ -> finish(true) }
            .setNegativeButton("Stay") { _, _ -> finish(false) }
            .create()
        registerPromptDialog(dialog) { finish(false) }
    }

    private fun cancelCefriumPermissionPrompt(tabId: String) {
        cefriumPermissionCancelActions.remove(tabId)?.toList()?.forEach { cancel -> cancel() }
        val queued = pendingCefriumAndroidPermissions.iterator()
        while (queued.hasNext()) {
            val request = queued.next()
            if (request.tabId == tabId) {
                queued.remove()
                request.cancelled = true
                request.complete(false)
            }
        }
        activeCefriumAndroidPermission?.takeIf { it.tabId == tabId }?.let { request ->
            request.cancelled = true
            request.complete(false)
            // Keep the active slot until Android returns its result so it cannot be
            // consumed by a different site's request.
        }
    }

    private fun launchNextCefriumAndroidPermission() {
        if (activeCefriumAndroidPermission != null || !::cefriumPermissionLauncher.isInitialized) return
        while (pendingCefriumAndroidPermissions.isNotEmpty()) {
            val request = pendingCefriumAndroidPermissions.removeFirst()
            if (request.cancelled) continue
            activeCefriumAndroidPermission = request
            try {
                cefriumPermissionLauncher.launch(request.permissions)
            } catch (_: Exception) {
                activeCefriumAndroidPermission = null
                request.complete(false)
                continue
            }
            return
        }
    }

    private fun showCefriumHttpAuthPrompt(
        host: String,
        realm: String,
        isProxy: Boolean,
        respond: (String?, String?) -> Unit
    ) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(4)) }
        val username = EditText(this).apply { hint = getString(R.string.username) }
        val password = EditText(this).apply {
            hint = getString(R.string.password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        form.addView(username)
        form.addView(password)
        var completed = false
        fun finish(user: String?, pass: String?) { if (!completed) { completed = true; respond(user, pass) } }
        val title = if (isProxy) "Proxy authentication required" else getString(R.string.authentication_required)
        val message = listOf(host, realm).filter { it.isNotBlank() }.joinToString("\n")
        val dialog = AlertDialog.Builder(this).setTitle(title).setMessage(message).setView(form)
            .setPositiveButton("Sign in") { _, _ -> finish(username.text.toString(), password.text.toString()) }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> finish(null, null) }
            .create()
        registerPromptDialog(dialog) { finish(null, null) }
    }

    private fun handleCefriumPopup(opener: BrowserTab, popup: CefriumBrowser, url: String, userGesture: Boolean): Boolean {
        val source = tabs.firstOrNull { it.id == opener.id }
        if (!userGesture || source == null || source.isPrivate || source.isGuest || source.engineType != BrowserEngineType.CEFRIUM ||
            !siteSettingAllowed(BrowserEngineType.CEFRIUM, source.lastUrl, "popups", false) ||
            cefriumCompatibilityReason(source, url) != null) {
            popup.close()
            return true // We own and explicitly close blocked windows; returning false would load the URL in the opener.
        }
        if (guestTorReservesGeckoRuntime() || BrowserVpnNavigationPolicy.shouldBlockNavigation(
                NovaBrowserVpnController.isVpnEnabled(), NovaBrowserVpnController.isVpnRoutingReady()
            )) {
            popup.close()
            toast("Popup paused until Nova confirms protected routing")
            return true
        }
        val tab = BrowserTab(null, false, false, lastUrl = url, desktopSite = source.desktopSite)
        tab.engineType = BrowserEngineType.CEFRIUM
        try {
            ensureCefriumEngine(tab, popup)
        } catch (failure: Exception) {
            if (tab.browserEngine != null) runCatching { closeTabRuntime(tab) } else runCatching { popup.close() }
            toast("Popup could not be opened in Cefrium")
            return true
        } catch (failure: LinkageError) {
            if (tab.browserEngine != null) runCatching { closeTabRuntime(tab) } else runCatching { popup.close() }
            toast("Popup could not be opened in Cefrium")
            return true
        }
        tabs.add(tab)
        activeIndex = tabs.lastIndex
        activateTab(tab)
        if (!addressBar.hasFocus()) addressBar.setText(url)
        updateTabChrome(tab)
        persistTabs()
        enforceSessionBudget(setOf(source.id))
        return true
    }

    private fun applyCefriumState(tab: BrowserTab, state: BrowserEngineState) {
        if (isDestroyed || tabs.none { it.id == tab.id }) return
        val rendererTermination = state.error?.takeIf {
            it.startsWith("Cefrium renderer stopped (") || it.startsWith("Cefrium renderer terminated:")
        }
        if (rendererTermination != null) {
            handleCefriumRendererTermination(tab, rendererTermination)
            return
        }
        if (!state.isLoading && state.progress >= 100 && !state.title.isNullOrBlank()) {
            resetRendererRecoveryAfterNextPaint(tab)
        }
        if (state.isLoading && state.progress == 0) tab.appliedSiteDarkMode = null
        val previousUrl = tab.lastUrl
        val previousTitle = tab.title
        val pendingReload = tab.needsReload
        val stateUrl = state.url.orEmpty()
        val stateTitle = state.title.orEmpty()
        // A stale engine callback must not replace an HTTPS-upgraded restore target.
        if (stateUrl.isNotBlank() && !pendingReload) tab.lastUrl = stateUrl
        if (stateTitle.isNotBlank() && !pendingReload) tab.title = stateTitle
        val displayUrl = if (pendingReload && tab.lastUrl.isNotBlank()) tab.lastUrl else stateUrl
        tab.canGoBack = state.canGoBack
        tab.canGoForward = state.canGoForward
        tab.isLoading = state.isLoading
        tab.progress = state.progress
        tab.isErrorPage = state.error != null
        if (!state.isLoading && state.progress >= 100 && !pendingReload) applyStoredSiteDarkMode(tab)
        val stateHost = Uri.parse(displayUrl).host ?: displayUrl
        if (displayUrl.startsWith("https://", true)) tab.securityStatus = getString(R.string.secure_connection, stateHost)
        else if (displayUrl.startsWith("http://", true)) tab.securityStatus = getString(R.string.not_secure, stateHost)
        if (state.error != null) tab.securityStatus = state.error
        if (tab.id == splitTabId && ::secondaryAddressBar.isInitialized && !secondaryAddressBar.hasFocus() && displayUrl.isNotBlank()) {
            secondaryAddressBar.setText(tab.lastUrl)
        }
        if (currentTab() === tab) {
            if (!addressBar.hasFocus() && displayUrl.isNotBlank()) addressBar.setText(tab.lastUrl)
            progressBar.progress = state.progress
            progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
            updateTabChrome(tab)
            refreshBookmarkButton()
            when {
                state.error != null -> setPageStatus(state.error, 5)
                state.isLoading || pendingReload -> resetPageStatus(getString(R.string.loading_page), 1)
                displayUrl.isNotBlank() -> setPageStatus(getString(R.string.ready_for_host, Uri.parse(displayUrl).host ?: displayUrl), 1)
            }
        }
        if (!pendingReload && !tab.isPrivate && !tab.isGuest && stateUrl.startsWith("http", true) && previousUrl != tab.lastUrl) {
            addHistory(tab.lastUrl)
            persistTabs()
        } else if (!pendingReload && !tab.isPrivate && !tab.isGuest && previousTitle != tab.title && tab.lastUrl.startsWith("http", true)) {
            addHistory(tab.lastUrl)
            persistTabs()
        }
        if (!state.isLoading && stateUrl.isNotBlank() && !pendingReload) captureTabThumbnail(tab)
    }

    private fun resetRendererRecoveryAfterNextPaint(tab: BrowserTab) {
        val view = tab.browserEngine?.view ?: return
        if (!pendingRendererPaintResetTabIds.add(tab.id)) return
        val observer = view.viewTreeObserver
        if (!observer.isAlive) {
            pendingRendererPaintResetTabIds.remove(tab.id)
            return
        }
        var drawHandled = false
        var listenerRemoved = false
        val listener = object : android.view.ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (drawHandled) return
                drawHandled = true
                view.post {
                    if (!listenerRemoved) {
                        listenerRemoved = true
                        if (observer.isAlive) observer.removeOnDrawListener(this)
                    }
                    pendingRendererPaintResetTabIds.remove(tab.id)
                    if (!isFinishing && !isDestroyed && view.isAttachedToWindow && tab.browserEngine?.view === view) {
                        tab.rendererRecoveryAttempts = BrowserRendererRecoveryPolicy.resetAfterSuccessfulPaint()
                    }
                }
            }
        }
        observer.addOnDrawListener(listener)
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                if (!listenerRemoved) {
                    listenerRemoved = true
                    if (observer.isAlive) observer.removeOnDrawListener(listener)
                }
                pendingRendererPaintResetTabIds.remove(tab.id)
                v.removeOnAttachStateChangeListener(this)
            }
        })
    }

    private fun engineSelectionForTab(tab: BrowserTab, url: String = tab.lastUrl): com.nova.browser.engine.EngineSelection {
        val profile = when {
            tab.isGuest -> BrowserProfileKind.GUEST
            tab.isPrivate -> BrowserProfileKind.PRIVATE
            else -> BrowserProfileKind.NORMAL
        }
        val compatibilityReason = cefriumCompatibilityReason(tab, url)
        val selection = enginePreferences.resolve(
            url = url,
            profile = profile,
            cefriumAvailable = true,
            cefriumCompatible = compatibilityReason == null
        )
        if (profile == BrowserProfileKind.NORMAL) {
            when (tab.explicitEngineChoice) {
                BrowserEngineType.GECKOVIEW -> return selection.copy(
                    engine = BrowserEngineType.GECKOVIEW, reason = "Explicit tab engine choice", usedFallback = false
                )
                BrowserEngineType.CEFRIUM -> if (compatibilityReason == null) return selection.copy(
                    engine = BrowserEngineType.CEFRIUM, reason = "Explicit tab engine choice", usedFallback = false
                )
                null -> Unit
            }
        }
        return selection
    }

    private fun resolveEngineForTab(tab: BrowserTab, url: String = tab.lastUrl): BrowserEngineType {
        val selection = engineSelectionForTab(tab, url)
        tab.engineFallbackReason = selection.reason.takeIf { selection.usedFallback }
            ?.let(NovaEnginePresentation::userFacingReason)
        return selection.engine
    }

    private fun cefriumCompatibilityReason(tab: BrowserTab, url: String = tab.lastUrl): String? {
        if (tab.isPrivate || tab.isGuest) return "Private and Guest modes always use GeckoView"
        if (NovaBrowserVpnController.isVpnEnabled()) return "1VPN currently protects GeckoView traffic only"
        if (NovaShield.fromStored(prefs.getString("shield_mode", "STANDARD")) != ShieldMode.OFF) {
            return "Nova Shield protections require GeckoView"
        }
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        if (url != HOME_URL && url != "about:blank" && scheme !in setOf("http", "https")) return "This page type is not supported by Cefrium"
        return null
    }

    private fun enforceCefriumCompatibility(tab: BrowserTab, url: String = tab.lastUrl) {
        if (tab.engineType != BrowserEngineType.CEFRIUM) return
        val selection = engineSelectionForTab(tab, url)
        if (selection.engine == BrowserEngineType.CEFRIUM) return
        closeTabRuntime(tab)
        tab.engineType = selection.engine
        tab.lastUrl = url
        tab.cefriumNavigationState = null
        tab.needsReload = tab.lastUrl != HOME_URL
        tab.engineFallbackReason = NovaEnginePresentation.userFacingReason(selection.reason)
    }

    private val pendingEngineFallbackTabIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Return true to cancel the Cefrium navigation and reopen the destination in GeckoView. */
    private fun routeCefriumNavigation(tab: BrowserTab, destination: String, method: String = "GET"): Boolean {
        when (NovaHttpsOnlyPolicy.decide(destination, httpsOnlyEnabled())) {
            NovaHttpsOnlyPolicy.Decision.BLOCK -> {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (currentTab()?.id == tab.id) toast("HTTPS-Only blocked a malformed or insecure HTTP request")
                }
                return true
            }
            NovaHttpsOnlyPolicy.Decision.UPGRADE_TO_HTTPS -> {
                if (!CrossEngineNavigationPolicy.canTransfer(method)) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (currentTab()?.id == tab.id) toast("HTTPS-Only blocked this HTTP form/request to avoid resending it as a different request")
                    }
                    return true
                }
                val upgraded = NovaHttpsOnlyPolicy.upgradeToHttps(destination) ?: return true
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (tabs.none { it.id == tab.id }) return@post
                    val wasActive = currentTab()?.id == tab.id
                    val wasSecondary = splitTabId == tab.id
                    tab.lastUrl = upgraded
                    tab.needsReload = true
                    tab.cefriumNavigationState = null
                    persistTabs()
                    if (wasActive) loadTabUrl(tab, upgraded) else if (wasSecondary) loadSecondaryTabUrl(tab, upgraded)
                }
                return true
            }
            NovaHttpsOnlyPolicy.Decision.ALLOW -> Unit
        }
        val selection = engineSelectionForTab(tab, destination)
        if (selection.engine == BrowserEngineType.CEFRIUM) return false
        // Reject non-GET engine handoffs before a routing pause can store only the URL;
        // replaying that URL later would silently turn a submitted POST into a GET.
        if (!CrossEngineNavigationPolicy.canTransfer(method)) {
            toast("This form cannot be transferred to the other browser engine without losing its submitted data. It was blocked.")
            return true
        }
        if (GuestTorIsolationPolicy.shouldBlockNonGuestSession(tab.isGuest, guestTorReservesGeckoRuntime()) ||
            BrowserVpnNavigationPolicy.shouldBlockNavigation(NovaBrowserVpnController.isVpnEnabled(), NovaBrowserVpnController.isVpnRoutingReady())) {
            tab.lastUrl = destination
            tab.needsReload = destination != HOME_URL
            tab.cefriumNavigationState = null
            persistTabs()
            toast("Navigation paused until Nova confirms protected routing")
            return true
        }
        if (pendingGeckoDataClearActive && !tab.isPrivate && !tab.isGuest) {
            toast("Wait for GeckoView data deletion to finish before opening this destination")
            return true
        }
        if (!pendingEngineFallbackTabIds.add(tab.id)) return true
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                if (tabs.none { it.id == tab.id }) return@post
                val wasActive = currentTab()?.id == tab.id
                val wasSecondary = splitTabId == tab.id
                if (GuestTorIsolationPolicy.shouldBlockNonGuestSession(tab.isGuest, guestTorReservesGeckoRuntime()) ||
                    BrowserVpnNavigationPolicy.shouldBlockNavigation(NovaBrowserVpnController.isVpnEnabled(), NovaBrowserVpnController.isVpnRoutingReady())) {
                    tab.lastUrl = destination
                    tab.needsReload = destination != HOME_URL
                    tab.cefriumNavigationState = null
                    persistTabs()
                    return@post
                }
                if (tab.engineType != BrowserEngineType.CEFRIUM) {
                    // Another queued operation may have converted this tab while the
                    // Cefrium handoff was waiting. Preserve and deliver the destination.
                    tab.lastUrl = destination
                    tab.needsReload = destination != HOME_URL
                    tab.cefriumNavigationState = null
                    persistTabs()
                    if (wasActive) loadTabUrl(tab, destination)
                    else if (wasSecondary) loadSecondaryTabUrl(tab, destination)
                    return@post
                }
                val latestSelection = engineSelectionForTab(tab, destination)
                if (latestSelection.engine == BrowserEngineType.CEFRIUM) {
                    // Engine preferences may change while this handoff is queued. Keep the
                    // requested destination and complete it in Cefrium rather than dropping it.
                    tab.engineType = BrowserEngineType.CEFRIUM
                    tab.lastUrl = destination
                    tab.cefriumNavigationState = null
                    tab.needsReload = destination != HOME_URL
                    tab.engineFallbackReason = null
                    persistTabs()
                    if (wasActive) activateTab(tab)
                    else if (wasSecondary) loadSecondaryTabUrl(tab, destination)
                    return@post
                }
                closeTabRuntime(tab)
                tab.engineType = latestSelection.engine
                tab.lastUrl = destination
                tab.cefriumNavigationState = null
                tab.needsReload = destination != HOME_URL
                tab.engineFallbackReason = NovaEnginePresentation.userFacingReason(latestSelection.reason)
                persistTabs()
                if (wasActive) {
                    val index = tabs.indexOfFirst { it.id == tab.id }
                    if (index >= 0) switchTab(index)
                } else if (wasSecondary) {
                    restoreSplitPane()
                }
            } finally {
                pendingEngineFallbackTabIds.remove(tab.id)
            }
        }
        return true
    }

    /** Returns the engine selected for this tab, creating its runtime lazily. */
    private fun ensureBrowserEngine(tab: BrowserTab): com.nova.browser.engine.BrowserEngine {
        check(!GuestTorIsolationPolicy.shouldBlockNonGuestSession(tab.isGuest, guestTorReservesGeckoRuntime())) {
            "Normal and Private browser engines are paused while Guest Tor owns the shared proxy"
        }
        check(!BrowserVpnNavigationPolicy.shouldBlockNavigation(
            NovaBrowserVpnController.isVpnEnabled(), NovaBrowserVpnController.isVpnRoutingReady()
        )) { "Browser engines are paused until 1VPN confirms protected routing" }
        enforceCefriumCompatibility(tab)
        return when (tab.engineType) {
            BrowserEngineType.CEFRIUM -> try {
                ensureCefriumEngine(tab)
            } catch (failure: Exception) {
                // ABI/runtime initialization can fail on a device even when the page is Cefrium-compatible.
                // Reopen only the URL in GeckoView; never copy Cefrium cookies or profile data.
                fallbackTabToGecko(tab, failure)
            } catch (failure: LinkageError) {
                // Missing native symbols/classes can escape Exception handling during runtime setup.
                fallbackTabToGecko(tab, failure)
            }
            BrowserEngineType.GECKOVIEW -> {
                ensureSession(tab)
                tab.browserEngine as? GeckoViewBrowserEngine
                    ?: error("Nova could not create the GeckoView engine for this tab")
            }
        }
    }

    /** Recreates only the page URL in GeckoView; Cefrium cookies/storage are never copied. */
    private fun fallbackTabToGecko(tab: BrowserTab, failure: Throwable): GeckoViewBrowserEngine {
        closeTabRuntime(tab)
        tab.engineType = BrowserEngineType.GECKOVIEW
        tab.cefriumNavigationState = null
        tab.needsReload = tab.lastUrl != HOME_URL
        tab.engineFallbackReason = "Nova reopened this page in its compatibility engine after a rendering problem. Site data was not transferred."
        ensureSession(tab)
        return tab.browserEngine as? GeckoViewBrowserEngine
            ?: error("Nova could not fall back to GeckoView")
    }

    private fun activateTab(tab: BrowserTab) {
        if (BrowserVpnNavigationPolicy.shouldBlockNavigation(
                NovaBrowserVpnController.isVpnEnabled(), NovaBrowserVpnController.isVpnRoutingReady()
            )) {
            tab.needsReload = tab.lastUrl != HOME_URL
            applyVpnRoutingReadiness()
            if (currentTab() === tab) setPageStatus("1VPN is connecting · this tab will resume when protected routing is ready", 1)
            return
        }
        if (GuestTorIsolationPolicy.shouldBlockNonGuestSession(tab.isGuest, guestTorReservesGeckoRuntime())) {
            tab.needsReload = tab.lastUrl != HOME_URL
            if (currentTab() === tab) setPageStatus("Normal and Private tabs are paused while Guest Tor owns the browser", 1)
            return
        }
        enforceCefriumCompatibility(tab)
        if (pendingGeckoDataClearActive && tab.engineType == BrowserEngineType.GECKOVIEW &&
            !tab.isPrivate && !tab.isGuest) {
            toast("Wait for GeckoView data deletion to finish before opening this tab")
            return
        }
        refreshDownloadsList(if (tab.isPrivate || tab.isGuest) emptyList() else downloadsFor(tab).items())
        if (tab.lastUrl == HOME_URL) {
            deactivateTabRuntime(tab)
            showHomeScreen()
            return
        }
        val engine = ensureBrowserEngine(tab)
        if (NovaSleepingTabPolicy.shouldShowRestoreHint(tab.wasSleepingForMemoryBudget, restoredFromBudget = false)) {
            tab.wasSleepingForMemoryBudget = false
            toast(NovaSleepingTabPolicy.restoreMessage())
        }
        if (engine.type == BrowserEngineType.GECKOVIEW) engine.attachTo(browserView)
        showPrimaryEngineView(engine.view)
        engine.setActive(true, true)
        if (tab.needsReload && tab.lastUrl != HOME_URL) {
            val policyUrl = applyHttpsOnlyPolicy(tab.lastUrl)
            if (policyUrl != null) {
                tab.lastUrl = policyUrl
                engine.loadUrl(policyUrl)
            }
            tab.needsReload = false
        }
        if (tab.lastUrl == HOME_URL) showHomeScreen() else showBrowserPage()
        tab.engineFallbackReason?.let { setPageStatus(it, 1) }
    }

    private fun deactivateTabRuntime(tab: BrowserTab?) {
        tab ?: return
        tab.browserEngine?.setActive(false, false)
        tab.browserEngine?.detach()
    }

    private fun closeTabRuntime(tab: BrowserTab) {
        pendingRendererPaintResetTabIds.remove(tab.id)
        aiPageText.remove(tab.id)
        aiPageUrl.remove(tab.id)
        if (tab.engineType == BrowserEngineType.CEFRIUM) {
            // A paused navigation has already replaced lastUrl and cleared the old
            // snapshot. Do not serialize the still-live old page over that destination.
            val pendingDestination = tab.needsReload && tab.cefriumNavigationState == null
            if (!pendingDestination) {
                (tab.browserEngine as? CefriumBrowserEngine)?.serializeNavigationState()?.let { rawState ->
                    tab.cefriumNavigationState = CefriumTabStateCipher.encrypt(rawState, enginePreferences.cefriumProfileName())
                }
            }
        }
        val engine = tab.browserEngine
        if (engine != null) {
            engine.close()
        } else {
            // Configuration changes detach the Gecko view but retain its session.
            // If no engine owns it, close it here before dropping the last reference.
            tab.session?.let { detachedSession -> runCatching { detachedSession.close() } }
        }
        tab.browserEngine = null
        tab.session = null
    }

    private fun showPrimaryEngineView(engineView: View) {
        NovaBrowserChromeStyle.applyEngineSurface(this, engineView)
        activePrimaryEngineView?.let { old -> if (old !== engineView && old.parent === browserPane) browserPane.removeView(old) }
        (engineView.parent as? ViewGroup)?.let { parent -> if (parent !== browserPane) parent.removeView(engineView) }
        val params = enginePaneLayoutParams(browserPane)
        if (engineView.parent === browserPane) browserPane.removeView(engineView)
        browserPane.addView(engineView, 0, params)
        activePrimaryEngineView = engineView
        browserView.visibility = if (engineView === browserView) View.VISIBLE else View.GONE
    }

    private fun showSecondaryEngineView(engineView: View) {
        NovaBrowserChromeStyle.applyEngineSurface(this, engineView)
        activeSecondaryEngineView?.let { old -> if (old !== engineView && old.parent === secondaryPane) secondaryPane.removeView(old) }
        (engineView.parent as? ViewGroup)?.let { parent -> if (parent !== secondaryPane) parent.removeView(engineView) }
        val params = enginePaneLayoutParams(secondaryPane)
        if (engineView.parent !== secondaryPane) secondaryPane.addView(engineView, params)
        else engineView.layoutParams = params
        activeSecondaryEngineView = engineView
        secondaryBrowserView.visibility = if (engineView === secondaryBrowserView) View.VISIBLE else View.GONE
    }

    private fun selectEngineForNavigation(tab: BrowserTab, url: String): Boolean {
        val desired = resolveEngineForTab(tab, url)
        if (pendingGeckoDataClearActive && desired == BrowserEngineType.GECKOVIEW && !tab.isPrivate && !tab.isGuest) {
            toast("Wait for GeckoView data deletion to finish before navigating this tab")
            return false
        }
        if (desired != tab.engineType) {
            closeTabRuntime(tab)
            if (desired == BrowserEngineType.CEFRIUM && tab.engineType != BrowserEngineType.CEFRIUM) tab.cefriumNavigationState = null
            tab.engineType = desired
            tab.needsReload = false
            if (currentTab() === tab) refreshDownloadsList(if (tab.isPrivate || tab.isGuest) emptyList() else downloadsFor(tab).items())
        }
        return true
    }

    private fun loadTabUrl(tab: BrowserTab, url: String) {
        val policyUrl = applyHttpsOnlyPolicy(url) ?: return
        if (policyUrl != url) { loadTabUrl(tab, policyUrl); return }
        if (BrowserVpnNavigationPolicy.shouldBlockNavigation(
                NovaBrowserVpnController.isVpnEnabled(), NovaBrowserVpnController.isVpnRoutingReady()
            )) {
            if (url != HOME_URL) {
                tab.lastUrl = url
                tab.needsReload = true
                tab.cefriumNavigationState = null
                // Persist the new destination only after the stale Cefrium snapshot
                // has been cleared, so a process death cannot restore the old page.
                if (!tab.isPrivate && !tab.isGuest) persistTabs()
            }
            applyVpnRoutingReadiness()
            if (currentTab() === tab) setPageStatus("1VPN is connecting · navigation will resume when protected routing is ready", 1)
            return
        }
        if (GuestTorIsolationPolicy.shouldBlockNonGuestSession(tab.isGuest, guestTorReservesGeckoRuntime())) {
            if (url != HOME_URL) {
                tab.lastUrl = url
                tab.needsReload = true
                tab.cefriumNavigationState = null
                if (!tab.isPrivate && !tab.isGuest) persistTabs()
            }
            toast("Normal browsing is paused while Guest Tor is connecting, active, or disconnecting")
            return
        }
        if (url == HOME_URL) {
            tab.browserEngine?.stop()
            deactivateTabRuntime(tab)
            tab.lastUrl = HOME_URL
            tab.title = "Nova Home"
            if (currentTab() === tab) showHomeScreen()
            return
        }
        if (!selectEngineForNavigation(tab, url)) return
        val engine = ensureBrowserEngine(tab)
        applyJavaScriptSetting(tab, url, reload = false)
        if (currentTab() === tab) {
            if (engine.type == BrowserEngineType.GECKOVIEW) engine.attachTo(browserView)
            showPrimaryEngineView(engine.view)
            engine.setActive(true, true)
            showBrowserPage()
        }
        val previousUrl = tab.lastUrl
        engine.loadUrl(url)
        tab.lastUrl = url
        tab.needsReload = false
        if (!tab.isPrivate && !tab.isGuest && previousUrl != url && url.startsWith("http", true)) {
            addHistory(url)
            persistTabs()
        }
    }

    private fun switchCurrentTabToGecko(reason: String) {
        val tab = currentTab() ?: return
        if (pendingGeckoDataClearActive) {
            toast("Wait for GeckoView data cleanup to finish before switching engines")
            return
        }
        if (BrowserVpnNavigationPolicy.shouldBlockNavigation(
                NovaBrowserVpnController.isVpnEnabled(), NovaBrowserVpnController.isVpnRoutingReady()
            )) return
        if (GuestTorIsolationPolicy.shouldBlockNonGuestSession(tab.isGuest, guestTorReservesGeckoRuntime())) return
        if (tab.engineType != BrowserEngineType.CEFRIUM) return
        closeTabRuntime(tab)
        tab.engineType = BrowserEngineType.GECKOVIEW
        tab.cefriumNavigationState = null
        tab.needsReload = tab.lastUrl != HOME_URL
        tab.engineFallbackReason = reason
        activateTab(tab)
        toast("Switched this tab to GeckoView: $reason")
    }

    /**
     * Enforce the requested 1VPN state at the tab/session boundary. If the extension
     * bridge or its blocking traffic gate is not ready, close live engines so existing
     * documents cannot keep issuing background requests. Restore the active tab only
     * after the protected route is verified, or when the user turns 1VPN off.
     */
    private fun applyVpnRoutingReadiness() {
        if (!::browserPane.isInitialized) return
        if (guestTorStartPending) {
            retryPendingExternalLink()
            return
        }
        if (TorGuestRoutingController.blocksNormalGeckoSessions()) {
            // If the combined 1VPN route drops while Guest owns GeckoView, close Guest's
            // live renderer too; the extension gate alone is not enough for existing pages.
            if (NovaBrowserVpnController.isVpnEnabled() && !NovaBrowserVpnController.isVpnRoutingReady()) {
                tabs.filter { it.isGuest && (it.browserEngine != null || it.session != null) }.forEach { tab ->
                    closeTabRuntime(tab)
                    tab.needsReload = tab.lastUrl != HOME_URL
                }
            }
            val activeGuestTab = currentTab()?.takeIf { it.isGuest }
            if (activeGuestTab != null && TorGuestRoutingController.isReady() &&
                (!NovaBrowserVpnController.isVpnEnabled() || NovaBrowserVpnController.isVpnRoutingReady()) &&
                activeGuestTab.needsReload) {
                loadTabUrl(activeGuestTab, activeGuestTab.lastUrl)
            }
            // Guest-owned links remain Guest-only; non-Guest tabs and engines stay paused.
            retryPendingExternalLink()
            return
        }
        if (GuestTorIsolationPolicy.shouldDeferTabRestore(
                guestStartPending = guestTorStartPending,
                activeTabIsGuest = currentTab()?.isGuest == true,
                torReady = TorGuestRoutingController.isReady()
            )) {
            retryPendingExternalLink()
            return
        }
        if (!NovaBrowserVpnController.isVpnEnabled()) {
            if (tabs.isNotEmpty() && (currentTab()?.needsReload == true || currentTab()?.browserEngine == null)) {
                switchTab(activeIndex.coerceIn(tabs.indices))
            }
            retryPendingExternalLink()
            return
        }
        if (!NovaBrowserVpnController.isVpnRoutingReady()) {
            if (splitTabId != null) exitSplitScreen()
            tabs.forEach { tab ->
                if (tab.browserEngine != null) {
                    if (currentTab() === tab) deactivateTabRuntime(tab)
                    closeTabRuntime(tab)
                    tab.needsReload = tab.lastUrl != HOME_URL
                }
            }
            currentTab()?.let { if (it.lastUrl != HOME_URL) setPageStatus("1VPN connecting · browser tabs paused until protected routing is ready", 1) }
            return
        }
        switchAllCefriumTabsToGecko("1VPN is enabled")
        if (tabs.isNotEmpty() && (currentTab()?.needsReload == true || currentTab()?.browserEngine == null)) {
            switchTab(activeIndex.coerceIn(tabs.indices))
        }
        retryPendingExternalLink()
    }

    private fun switchTabToGeckoForThreatProtection(tab: BrowserTab) {
        if (tab.isPrivate || tab.isGuest || tab.engineType != BrowserEngineType.CEFRIUM) return
        if (pendingGeckoDataClearActive) {
            toast("Wait for GeckoView data cleanup to finish before switching engines")
            return
        }
        if (BrowserVpnNavigationPolicy.shouldBlockNavigation(
                NovaBrowserVpnController.isVpnEnabled(), NovaBrowserVpnController.isVpnRoutingReady()
            )) {
            toast("Wait for protected routing to be ready before switching engines")
            return
        }
        if (GuestTorIsolationPolicy.shouldBlockNonGuestSession(tab.isGuest, guestTorReservesGeckoRuntime())) {
            toast("GeckoView is reserved for Guest Mode right now")
            return
        }
        closeTabRuntime(tab)
        tab.explicitEngineChoice = BrowserEngineType.GECKOVIEW
        tab.engineType = BrowserEngineType.GECKOVIEW
        tab.cefriumNavigationState = null
        tab.needsReload = tab.lastUrl != HOME_URL
        tab.engineFallbackReason = "Nova reopened this page in its protected compatibility engine. Website data was not transferred."
        persistTabs()
        if (currentTab() === tab) activateTab(tab)
        else if (splitTabId == tab.id) restoreSplitPane()
        toast("Switched this tab to GeckoView Safe Browsing")
    }

    private fun switchAllCefriumTabsToGecko(reason: String) {
        val changed = tabs.filter { !it.isPrivate && !it.isGuest && it.engineType == BrowserEngineType.CEFRIUM }
        if (changed.isEmpty()) return
        changed.forEach { tab ->
            closeTabRuntime(tab)
            tab.engineType = BrowserEngineType.GECKOVIEW
            tab.cefriumNavigationState = null
            tab.needsReload = tab.lastUrl != HOME_URL
            tab.engineFallbackReason = reason
        }
        currentTab()?.let { if (it in changed) activateTab(it) }
        if (splitTabId != null && changed.any { it.id == splitTabId }) restoreSplitPane()
        toast("Cefrium tabs switched to GeckoView: $reason")
    }

    private fun cefriumOriginForUrl(url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (scheme !in listOf("http", "https")) return null
        return "$scheme://$host${if (uri.port > 0) ":${uri.port}" else ""}"
    }

    private fun cefriumPermissionDefinitions(): List<Pair<String, String>> = listOf(
        "GEOLOCATION" to "Location",
        "CAMERA" to "Camera",
        "MICROPHONE" to "Microphone",
        "NOTIFICATIONS" to "Notifications",
        "CLIPBOARD_READ" to "Clipboard",
        "NFC" to "NFC"
    )

    private fun cefriumSitePermissionsKey(origin: String): String =
        "site_permissions_cefrium_${Uri.encode(origin)}"

    private fun cefriumPermissionDecision(origin: String, permission: String): String {
        val stored = runCatching {
            JSONObject(prefs.getString(cefriumSitePermissionsKey(origin), "{}") ?: "{}")
                .optString(permission, "")
                .uppercase()
        }.getOrDefault("")
        if (stored in setOf("ASK", "ALLOW", "BLOCK")) return stored
        return (prefs.getString("site_default_permission_cefrium_${permission.lowercase()}", "ASK") ?: "ASK")
            .uppercase().takeIf { it in setOf("ASK", "ALLOW", "BLOCK") } ?: "ASK"
    }

    private fun saveCefriumPermissionDecision(origin: String, permission: String, decision: String) {
        val normalized = decision.uppercase().takeIf { it in setOf("ASK", "ALLOW", "BLOCK") } ?: "ASK"
        val key = cefriumSitePermissionsKey(origin)
        val settings = runCatching { JSONObject(prefs.getString(key, "{}") ?: "{}") }.getOrDefault(JSONObject())
        settings.put(permission, normalized)
        prefs.edit().putString(key, settings.toString()).apply()
        tabs.filter {
            it.engineType == BrowserEngineType.CEFRIUM && !it.isGuest && !it.isPrivate &&
                cefriumOriginForUrl(it.lastUrl) == origin
        }.forEach { (it.browserEngine as? CefriumBrowserEngine)?.setSitePermission(origin, permission, normalized) }
    }

    private fun applyCefriumPermissionSettings(tab: BrowserTab, engine: CefriumBrowserEngine) {
        val origin = cefriumOriginForUrl(tab.lastUrl) ?: return
        cefriumPermissionDefinitions().forEach { (permission, _) ->
            engine.setSitePermission(origin, permission, cefriumPermissionDecision(origin, permission))
        }
    }

    private fun showCefriumSitePermissionSettings(origin: String) {
        val host = Uri.parse(origin).host ?: origin
        val definitions = cefriumPermissionDefinitions()
        val labels = definitions.map { (permission, label) ->
            val status = when (cefriumPermissionDecision(origin, permission)) {
                "ALLOW" -> "Allowed"
                "BLOCK" -> "Blocked"
                else -> "Ask"
            }
            "$label · $status"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Site permissions · $host")
            .setItems(labels) { _, which ->
                val (permission, label) = definitions[which]
                val options = arrayOf("Ask every time", "Block", "Allow")
                val current = when (cefriumPermissionDecision(origin, permission)) {
                    "BLOCK" -> 1
                    "ALLOW" -> 2
                    else -> 0
                }
                AlertDialog.Builder(this).setTitle("$label · $host")
                    .setSingleChoiceItems(options, current) { dialog, selected ->
                        val decision = arrayOf("ASK", "BLOCK", "ALLOW")[selected]
                        saveCefriumPermissionDecision(origin, permission, decision)
                        dialog.dismiss()
                        showCefriumSitePermissionSettings(origin)
                    }
                    .setNegativeButton(getString(R.string.close), null)
                    .show()
            }.setNegativeButton(getString(R.string.close), null).show()
    }

    private fun showDefaultCefriumPermissions() {
        val definitions = cefriumPermissionDefinitions()
        val labels = definitions.map { (permission, label) ->
            val status = (prefs.getString("site_default_permission_cefrium_${permission.lowercase()}", "ASK") ?: "ASK").uppercase()
            "$label · ${when (status) { "ALLOW" -> "Allowed"; "BLOCK" -> "Blocked"; else -> "Ask" }}"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Default Nova Core permissions")
            .setItems(labels) { _, which ->
                val (permission, label) = definitions[which]
                val prefKey = "site_default_permission_cefrium_${permission.lowercase()}"
                val currentValue = (prefs.getString(prefKey, "ASK") ?: "ASK").uppercase()
                val current = when (currentValue) { "BLOCK" -> 1; "ALLOW" -> 2; else -> 0 }
                AlertDialog.Builder(this).setTitle("$label default")
                    .setSingleChoiceItems(arrayOf("Ask every time", "Block", "Allow"), current) { dialog, selected ->
                        val decision = arrayOf("ASK", "BLOCK", "ALLOW")[selected]
                        prefs.edit().putString(prefKey, decision).apply()
                        tabs.filter { it.engineType == BrowserEngineType.CEFRIUM && !it.isPrivate && !it.isGuest }
                            .forEach { tab ->
                                val origin = cefriumOriginForUrl(tab.lastUrl) ?: return@forEach
                                val siteOverrides = runCatching {
                                    JSONObject(prefs.getString(cefriumSitePermissionsKey(origin), "{}") ?: "{}")
                                }.getOrDefault(JSONObject())
                                if (!siteOverrides.has(permission)) {
                                    (tab.browserEngine as? CefriumBrowserEngine)?.setSitePermission(origin, permission, decision)
                                }
                            }
                        dialog.dismiss()
                        showDefaultCefriumPermissions()
                    }
                    .setNegativeButton(getString(R.string.close), null)
                    .show()
            }.setNegativeButton(getString(R.string.close), null).show()
    }

    private fun geckoPermissionToken(permission: Int): String = when (permission) {
        GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> "location"
        GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> "notifications"
        GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> "autoplay_audible"
        GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> "autoplay_inaudible"
        GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> "persistent_storage"
        GeckoSession.PermissionDelegate.PERMISSION_XR -> "xr"
        GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> "protected_media"
        GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS -> "storage_access"
        GeckoSession.PermissionDelegate.PERMISSION_TRACKING -> "tracking"
        GeckoSession.PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS -> "local_device_access"
        GeckoSession.PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS -> "local_network_access"
        else -> "permission_$permission"
    }

    private fun geckoPermissionDecision(origin: String, token: String, allowSiteOverride: Boolean = true): String {
        val normalized = cefriumOriginForUrl(origin) ?: return "ASK"
        if (allowSiteOverride) {
            val site = prefs.getString("site_permission_geckoview_${token}_${Uri.encode(normalized)}", null)?.uppercase()
            if (site != null && site in setOf("ASK", "ALLOW", "BLOCK")) return site
        }
        return (prefs.getString("site_default_permission_geckoview_$token", "ASK") ?: "ASK")
            .uppercase().takeIf { it in setOf("ASK", "ALLOW", "BLOCK") } ?: "ASK"
    }

    private fun saveGeckoPermissionDecision(origin: String, token: String, decision: String) {
        val normalized = cefriumOriginForUrl(origin) ?: return
        val value = decision.uppercase().takeIf { it in setOf("ASK", "ALLOW", "BLOCK") } ?: "ASK"
        prefs.edit().putString("site_permission_geckoview_${token}_${Uri.encode(normalized)}", value).apply()
    }

    private fun showDefaultGeckoPermissions() {
        val definitions = listOf(
            "location" to "Location", "notifications" to "Notifications",
            "camera" to "Camera", "microphone" to "Microphone",
            "persistent_storage" to "Persistent storage", "storage_access" to "Third-party storage access",
            "xr" to "Virtual reality", "local_device_access" to "Local device access",
            "local_network_access" to "Local network access"
        )
        val labels = definitions.map { (token, label) ->
            val status = (prefs.getString("site_default_permission_geckoview_$token", "ASK") ?: "ASK").uppercase()
            "$label · ${when (status) { "ALLOW" -> "Allowed"; "BLOCK" -> "Blocked"; else -> "Ask" }}"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Default Nova Compatibility permissions")
            .setItems(labels) { _, which ->
                val (token, label) = definitions[which]
                val key = "site_default_permission_geckoview_$token"
                val current = when ((prefs.getString(key, "ASK") ?: "ASK").uppercase()) { "BLOCK" -> 1; "ALLOW" -> 2; else -> 0 }
                AlertDialog.Builder(this).setTitle("$label default")
                    .setSingleChoiceItems(arrayOf("Ask every time", "Block", "Allow"), current) { dialog, selected ->
                        prefs.edit().putString(key, arrayOf("ASK", "BLOCK", "ALLOW")[selected]).apply()
                        dialog.dismiss()
                        showDefaultGeckoPermissions()
                    }
                    .setNegativeButton(getString(R.string.close), null).show()
            }.setNegativeButton(getString(R.string.close), null).show()
    }

    private fun showDefaultGeckoCookieSettings() {
        val values = arrayOf("ALL", "FIRST_PARTY", "ISOLATE", "NONE")
        val labels = arrayOf("Allow all cookies", "Block third-party cookies", "Isolate third-party cookies · recommended", "Block all cookies")
        val current = prefs.getString("site_default_geckoview_cookies", "ISOLATE") ?: "ISOLATE"
        val selected = values.indexOf(current).takeIf { it >= 0 } ?: 2
        AlertDialog.Builder(this).setTitle("Nova Compatibility cookies and site data")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                prefs.edit().putString("site_default_geckoview_cookies", values[which]).apply()
                applyGeckoCookieBehavior()
                dialog.dismiss()
                toast("Nova Compatibility cookie policy · ${labels[which]}")
            }
            .setNegativeButton(getString(R.string.close), null).show()
    }

    private fun applyGeckoCookieBehavior() {
        if (!::runtime.isInitialized) return
        val behavior = when (prefs.getString("site_default_geckoview_cookies", "ISOLATE")) {
            "ALL" -> org.mozilla.geckoview.ContentBlocking.CookieBehavior.ACCEPT_ALL
            "FIRST_PARTY" -> org.mozilla.geckoview.ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY
            "NONE" -> org.mozilla.geckoview.ContentBlocking.CookieBehavior.ACCEPT_NONE
            else -> org.mozilla.geckoview.ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
        }
        runtime.settings.contentBlocking.setCookieBehavior(behavior)
    }

    private fun showDefaultGeckoImageSettings() {
        // Gecko 157 supports allow-all (1) and block-all (2); value 3 is not a third-party-only mode.
        val values = intArrayOf(1, 2)
        val labels = arrayOf("Load all images", "Block all images")
        val selected = if (prefs.getInt("site_default_geckoview_images", 1) == 2) 1 else 0
        AlertDialog.Builder(this).setTitle("Nova Compatibility images")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                val next = values[which]
                applyGeckoImageBehavior(next) { applied ->
                    if (applied) {
                        prefs.edit().putInt("site_default_geckoview_images", next).apply()
                        dialog.dismiss()
                        toast("Image loading · ${labels[which]}")
                    } else {
                        toast("Could not update Nova Compatibility image settings")
                    }
                }
            }
            .setNegativeButton(getString(R.string.close), null).show()
    }

    @androidx.annotation.OptIn(markerClass = [org.mozilla.geckoview.ExperimentalGeckoViewApi::class])
    private fun applyGeckoImageBehavior(
        requestedValue: Int = prefs.getInt("site_default_geckoview_images", 1),
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        if (!::runtime.isInitialized) { onComplete?.invoke(false); return }
        val value = NovaPhase2Policy.imagePermissionDefault(prefs.getBoolean("nova_data_saver_enabled", false), requestedValue)
        org.mozilla.geckoview.GeckoPreferenceController.setGeckoPref(
            "permissions.default.image",
            value,
            org.mozilla.geckoview.GeckoPreferenceController.PREF_BRANCH_USER
        ).accept(
            { onComplete?.invoke(true) },
            { _ -> runOnUiThread { onComplete?.invoke(false) } }
        )
    }

    private fun showGeckoSitePermissionHub(origin: String) {
        AlertDialog.Builder(this).setTitle("Permissions · ${Uri.parse(origin).host ?: origin}")
            .setItems(arrayOf("Content permissions", "Camera and microphone")) { _, which ->
                if (which == 0) showGeckoSitePermissions(SiteRuleEntry(BrowserEngineType.GECKOVIEW, origin))
                else showGeckoMediaPermissionSettings(origin)
            }
            .setNegativeButton(getString(R.string.close), null).show()
    }

    private fun showGeckoMediaPermissionSettings(origin: String) {
        val definitions = listOf("camera" to "Camera", "microphone" to "Microphone")
        val labels = definitions.map { (token, label) ->
            val decision = geckoPermissionDecision(origin, token)
            "$label · ${when (decision) { "ALLOW" -> "Allowed"; "BLOCK" -> "Blocked"; else -> "Ask" }}"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Camera and microphone · ${Uri.parse(origin).host ?: origin}")
            .setItems(labels) { _, which ->
                val (token, label) = definitions[which]
                val current = when (geckoPermissionDecision(origin, token)) { "BLOCK" -> 1; "ALLOW" -> 2; else -> 0 }
                AlertDialog.Builder(this).setTitle("$label permission")
                    .setSingleChoiceItems(arrayOf("Ask every time", "Block", "Allow"), current) { dialog, selected ->
                        saveGeckoPermissionDecision(origin, token, arrayOf("ASK", "BLOCK", "ALLOW")[selected])
                        dialog.dismiss()
                        showGeckoMediaPermissionSettings(origin)
                    }
                    .setNegativeButton(getString(R.string.close), null).show()
            }.setNegativeButton(getString(R.string.close), null).show()
    }

    private fun showCefriumPermissionPrompt(tabId: String, origin: String, permissions: Set<String>, respond: (Boolean) -> Unit) {
        val normalized = CefriumSitePermissionPolicy.normalize(origin, permissions)
        if (normalized == null) { respond(false); return }
        var completed = false
        var handedToAndroid = false
        var promptDialog: AlertDialog? = null
        lateinit var cancelAction: () -> Unit
        fun finish(granted: Boolean) {
            if (!completed) {
                completed = true
                cefriumPermissionCancelActions[tabId]?.let { actions ->
                    actions.remove(cancelAction)
                    if (actions.isEmpty()) cefriumPermissionCancelActions.remove(tabId)
                }
                respond(granted)
            }
        }
        cancelAction = {
            if (!handedToAndroid) promptDialog?.dismiss()
            finish(false)
        }
        cefriumPermissionCancelActions.getOrPut(tabId) { linkedSetOf() }.add(cancelAction)
        val labels = normalized.map { it.lowercase().replace('_', ' ') }
        val runtimePermissions = buildList {
            if ("CAMERA" in normalized) add(Manifest.permission.CAMERA)
            if ("MICROPHONE" in normalized) add(Manifest.permission.RECORD_AUDIO)
            if ("GEOLOCATION" in normalized) {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if ("NOTIFICATIONS" in normalized && Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val decisions = normalized.map { cefriumPermissionDecision(origin, it) }
        if (decisions.any { it == "BLOCK" }) { finish(false); return }
        val mustAskInNova = decisions.any { it == "ASK" }
        fun requestAndroidPermissions() {
            if (completed) return
            val missing = runtimePermissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) finish(true) else {
                handedToAndroid = true
                pendingCefriumAndroidPermissions.addLast(
                    PendingCefriumAndroidPermission(
                        tabId = tabId,
                        permissions = missing.toTypedArray(),
                        complete = { granted -> finish(granted) }
                    )
                )
                launchNextCefriumAndroidPermission()
            }
        }
        if (!mustAskInNova) {
            requestAndroidPermissions()
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Allow site permissions?")
            .setMessage("$origin requests: ${labels.joinToString(", ")}. Only allow permissions you trust.")
            .setNegativeButton(getString(R.string.block)) { _, _ -> finish(false) }
            .setPositiveButton(getString(R.string.allow)) { _, _ -> requestAndroidPermissions() }
            .create()
        promptDialog = dialog
        registerPromptDialog(dialog) { if (!handedToAndroid) finish(false) }
    }

    private fun setBrowserFullscreen(fullScreen: Boolean, tab: BrowserTab? = currentTab()) {
        isFullscreen = fullScreen
        fullscreenTabId = if (fullScreen) tab?.id else null
        if (fullScreen && tab != null) refreshFullscreenVideoPlayback(tab)
        else tab?.isFullscreenVideoPlaying = false
        topChrome.visibility = if (fullScreen) View.GONE else View.VISIBLE
        bottomToolbar.visibility = if (fullScreen) View.GONE else View.VISIBLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (fullScreen) {
                window.insetsController?.hide(WindowInsets.Type.systemBars())
                window.insetsController?.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else window.insetsController?.show(WindowInsets.Type.systemBars())
        } else {
            window.decorView.systemUiVisibility = if (fullScreen) (
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            ) else View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun configureSession(session: GeckoSession, tab: BrowserTab) {
        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onChoicePrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.ChoicePrompt): GeckoResult<PromptResponse> {
                val result = GeckoResult<PromptResponse>()
                val choices = prompt.choices.toList().flatMap { choice ->
                    val children = choice.items
                    if (children.isNullOrEmpty()) listOf(choice) else children.toList()
                }.filterNot { it.disabled }
                if (choices.isEmpty()) { result.complete(prompt.dismiss()); return result }
                val labels = choices.map { it.label }.toTypedArray()
                var selectedIndex = choices.indexOfFirst { it.selected }.coerceAtLeast(0)
                val selected = choices.filter { it.selected }.toMutableSet()
                var completed = false
                fun finish(response: PromptResponse) { if (!completed) { completed = true; result.complete(response) } }
                fun finishIfPending(response: () -> PromptResponse) { if (!completed) finish(response()) }
                val builder = AlertDialog.Builder(this@MainActivity).setTitle(prompt.title ?: getString(R.string.select_option))
                when (prompt.type) {
                    GeckoSession.PromptDelegate.ChoicePrompt.Type.MENU -> builder.setItems(labels) { _, which -> finish(prompt.confirm(choices[which])) }
                    GeckoSession.PromptDelegate.ChoicePrompt.Type.SINGLE -> builder
                        .setSingleChoiceItems(labels, selectedIndex) { _, which -> selectedIndex = which }
                        .setPositiveButton(android.R.string.ok) { _, _ -> finish(prompt.confirm(choices[selectedIndex])) }
                    GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE -> {
                        val checked = BooleanArray(choices.size) { choices[it].selected }
                        builder.setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                            if (isChecked) selected.add(choices[which]) else selected.remove(choices[which])
                        }.setPositiveButton(android.R.string.ok) { _, _ -> finish(prompt.confirm(selected.toTypedArray())) }
                    }
                    else -> { finishIfPending { prompt.dismiss() }; return result }
                }
                val dialog = builder.setNegativeButton(android.R.string.cancel) { _, _ -> finishIfPending { prompt.dismiss() } }.create()
                registerPromptDialog(dialog) { finishIfPending { prompt.dismiss() } }
                return result
            }

            override fun onAlertPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.AlertPrompt): GeckoResult<PromptResponse> {
                val result = GeckoResult<PromptResponse>()
                var completed = false
                fun finish() { if (!completed) { completed = true; result.complete(prompt.dismiss()) } }
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(prompt.title ?: getString(R.string.page_message))
                    .setMessage(prompt.message.orEmpty())
                    .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                    .create()
                registerPromptDialog(dialog) { finish() }
                return result
            }

            override fun onButtonPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.ButtonPrompt): GeckoResult<PromptResponse> {
                val result = GeckoResult<PromptResponse>()
                var completed = false
                fun finish(response: PromptResponse) { if (!completed) { completed = true; result.complete(response) } }
                fun finishIfPending(response: () -> PromptResponse) { if (!completed) finish(response()) }
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(prompt.title ?: getString(R.string.page_confirmation))
                    .setMessage(prompt.message.orEmpty())
                    .setPositiveButton(android.R.string.ok) { _, _ -> finish(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE)) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> finish(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE)) }
                    .create()
                registerPromptDialog(dialog) { finishIfPending { prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE) } }
                return result
            }

            override fun onTextPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.TextPrompt): GeckoResult<PromptResponse> {
                val result = GeckoResult<PromptResponse>()
                val input = EditText(this@MainActivity).apply { setText(prompt.defaultValue.orEmpty()); setSelectAllOnFocus(true) }
                var completed = false
                fun finish(response: PromptResponse) { if (!completed) { completed = true; result.complete(response) } }
                fun finishIfPending(response: () -> PromptResponse) { if (!completed) finish(response()) }
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(prompt.title ?: getString(R.string.page_input))
                    .setMessage(prompt.message.orEmpty())
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ -> finish(prompt.confirm(input.text.toString())) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> finishIfPending { prompt.dismiss() } }
                    .create()
                registerPromptDialog(dialog) { finishIfPending { prompt.dismiss() } }
                return result
            }

            override fun onAuthPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.AuthPrompt): GeckoResult<PromptResponse> {
                val result = GeckoResult<PromptResponse>()
                val passwordOnly = prompt.authOptions.flags and GeckoSession.PromptDelegate.AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD != 0
                val form = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(4)) }
                val username = EditText(this@MainActivity).apply { hint = getString(R.string.username); setText(prompt.authOptions.username.orEmpty()); visibility = if (passwordOnly) View.GONE else View.VISIBLE }
                val password = EditText(this@MainActivity).apply { hint = getString(R.string.password); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setText(prompt.authOptions.password.orEmpty()) }
                if (!passwordOnly) form.addView(username)
                form.addView(password)
                var completed = false
                fun finish(response: PromptResponse) { if (!completed) { completed = true; result.complete(response) } }
                fun finishIfPending(response: () -> PromptResponse) { if (!completed) finish(response()) }
                val host = prompt.authOptions.uri?.let { Uri.parse(it).host }.orEmpty()
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(prompt.title ?: getString(R.string.authentication_required))
                    .setMessage(listOf(host, prompt.message.orEmpty()).filter { it.isNotBlank() }.joinToString("\n"))
                    .setView(form)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val response = if (passwordOnly) prompt.confirm(password.text.toString()) else prompt.confirm(username.text.toString(), password.text.toString())
                        finish(response)
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> finishIfPending { prompt.dismiss() } }
                    .create()
                registerPromptDialog(dialog) { finishIfPending { prompt.dismiss() } }
                return result
            }

            override fun onDateTimePrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.DateTimePrompt): GeckoResult<PromptResponse> {
                val result = GeckoResult<PromptResponse>()
                val input = EditText(this@MainActivity).apply {
                    setText(prompt.defaultValue.orEmpty())
                    inputType = when (prompt.type) {
                        GeckoSession.PromptDelegate.DateTimePrompt.Type.DATE -> InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
                        GeckoSession.PromptDelegate.DateTimePrompt.Type.TIME -> InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
                        else -> InputType.TYPE_CLASS_TEXT
                    }
                }
                var completed = false
                fun finish(response: PromptResponse) { if (!completed) { completed = true; result.complete(response) } }
                fun finishIfPending(response: () -> PromptResponse) { if (!completed) finish(response()) }
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(prompt.title ?: getString(R.string.date_time_input))
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ -> finish(prompt.confirm(input.text.toString())) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> finishIfPending { prompt.dismiss() } }
                    .create()
                registerPromptDialog(dialog) { finishIfPending { prompt.dismiss() } }
                return result
            }

            override fun onColorPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.ColorPrompt): GeckoResult<PromptResponse> {
                val result = GeckoResult<PromptResponse>()
                val input = EditText(this@MainActivity).apply { hint = getString(R.string.color_hex_hint); setText(prompt.defaultValue.orEmpty()) }
                var completed = false
                fun finish(response: PromptResponse) { if (!completed) { completed = true; result.complete(response) } }
                fun finishIfPending(response: () -> PromptResponse) { if (!completed) finish(response()) }
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(prompt.title ?: getString(R.string.choose_color))
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ -> finish(prompt.confirm(input.text.toString())) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> finishIfPending { prompt.dismiss() } }
                    .create()
                registerPromptDialog(dialog) { finishIfPending { prompt.dismiss() } }
                return result
            }

            override fun onPopupPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.PopupPrompt): GeckoResult<PromptResponse> {
                val result = GeckoResult<PromptResponse>()
                var completed = false
                fun finish(response: PromptResponse) { if (!completed) { completed = true; result.complete(response) } }
                fun finishIfPending(response: () -> PromptResponse) { if (!completed) finish(response()) }
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.popup_request))
                    .setMessage(prompt.targetUri.orEmpty())
                    .setPositiveButton(getString(R.string.allow)) { _, _ -> finish(prompt.confirm(AllowOrDeny.ALLOW)) }
                    .setNegativeButton(getString(R.string.block)) { _, _ -> finish(prompt.confirm(AllowOrDeny.DENY)) }
                    .create()
                registerPromptDialog(dialog) { finishIfPending { prompt.confirm(AllowOrDeny.DENY) } }
                return result
            }

            override fun onBeforeUnloadPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt): GeckoResult<PromptResponse> {
                val result = GeckoResult<PromptResponse>()
                var completed = false
                fun finish(response: PromptResponse) { if (!completed) { completed = true; result.complete(response) } }
                fun finishIfPending(response: () -> PromptResponse) { if (!completed) finish(response()) }
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.leave_page_title))
                    .setMessage(getString(R.string.leave_page_message))
                    .setPositiveButton(getString(R.string.leave_page)) { _, _ -> finish(prompt.confirm(AllowOrDeny.ALLOW)) }
                    .setNegativeButton(getString(R.string.stay_page)) { _, _ -> finish(prompt.confirm(AllowOrDeny.DENY)) }
                    .create()
                registerPromptDialog(dialog) { finishIfPending { prompt.confirm(AllowOrDeny.DENY) } }
                return result
            }

            override fun onFilePrompt(session: GeckoSession, prompt: FilePrompt): GeckoResult<PromptResponse> {
                pendingFilePrompt = prompt
                val result = GeckoResult<PromptResponse>()
                pendingFileResult = result
                val picker = if (prompt.type == FilePrompt.Type.FOLDER) {
                    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                } else {
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = prompt.mimeTypes?.firstOrNull() ?: "*/*"
                        if (!prompt.mimeTypes.isNullOrEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, prompt.mimeTypes)
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, prompt.type == FilePrompt.Type.MULTIPLE)
                    }
                }
                try { startActivityForResult(picker, uploadRequestCode) }
                catch (error: Exception) { pendingFilePrompt = null; pendingFileResult = null; result.complete(prompt.dismiss()); toast("Could not open file picker") }
                return result
            }

            override fun onLoginSave(session: GeckoSession, request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.LoginSaveOption>): GeckoResult<PromptResponse> {
                val tab = tabs.firstOrNull { it.session === session }
                val option = request.options.firstOrNull()
                val result = GeckoResult<PromptResponse>()
                if (tab == null || tab.isPrivate || tab.isGuest || option == null) {
                    result.complete(request.dismiss())
                    return result
                }
                val login = option.value
                var completed = false
                fun finish(response: PromptResponse) { if (!completed) { completed = true; result.complete(response) } }
                fun finishIfPending(response: () -> PromptResponse) { if (!completed) finish(response()) }
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.save_password_title))
                    .setMessage(getString(R.string.save_password_message, Uri.parse(login.origin).host ?: login.origin))
                    .setPositiveButton(getString(R.string.save_securely)) { _, _ -> finish(request.confirm(option)) }
                    .setNegativeButton(getString(R.string.not_now)) { _, _ -> finishIfPending { request.dismiss() } }
                    .create()
                registerPromptDialog(dialog) { finishIfPending { request.dismiss() } }
                return result
            }

            override fun onLoginSelect(session: GeckoSession, request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.LoginSelectOption>): GeckoResult<PromptResponse> {
                val tab = tabs.firstOrNull { it.session === session }
                val result = GeckoResult<PromptResponse>()
                if (tab == null || tab.isPrivate || tab.isGuest || request.options.isEmpty()) {
                    result.complete(request.dismiss())
                    return result
                }
                val choices = request.options.map { it.value.username.ifBlank { Uri.parse(it.value.origin).host ?: "Saved login" } }.toTypedArray()
                var completed = false
                fun finish(response: PromptResponse) { if (!completed) { completed = true; result.complete(response) } }
                fun finishIfPending(response: () -> PromptResponse) { if (!completed) finish(response()) }
                val dialog = AlertDialog.Builder(this@MainActivity).setTitle(getString(R.string.choose_saved_login))
                    .setItems(choices) { _, which -> finish(request.confirm(request.options[which])) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> finishIfPending { request.dismiss() } }
                    .create()
                registerPromptDialog(dialog) { finishIfPending { request.dismiss() } }
                return result
            }

            override fun onFolderUploadPrompt(session: GeckoSession, prompt: FolderUploadPrompt): GeckoResult<PromptResponse> {
                val result = GeckoResult<PromptResponse>()
                var completed = false
                fun finish(response: PromptResponse) { if (!completed) { completed = true; result.complete(response) } }
                fun finishIfPending(response: () -> PromptResponse) { if (!completed) finish(response()) }
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.upload_folder_title))
                    .setMessage(getString(R.string.upload_folder_message, prompt.directoryName ?: getString(R.string.selected_folder)))
                    .setPositiveButton(getString(R.string.allow)) { _, _ -> finish(prompt.confirm(AllowOrDeny.ALLOW)) }
                    .setNegativeButton(getString(R.string.deny)) { _, _ -> finish(prompt.confirm(AllowOrDeny.DENY)) }
                    .create()
                registerPromptDialog(dialog) { finishIfPending { prompt.confirm(AllowOrDeny.DENY) } }
                return result
            }
        }
        session.setTranslationsSessionDelegate(object : TranslationsController.SessionTranslation.Delegate {
            override fun onOfferTranslate(session: GeckoSession) {
                val tab = tabs.firstOrNull { it.session === session } ?: return
                if (currentTab() === tab) runOnUiThread { toast(getString(R.string.translation_available_notice)) }
            }
            override fun onExpectedTranslate(session: GeckoSession) {
                val tab = tabs.firstOrNull { it.session === session } ?: return
                if (currentTab() === tab) runOnUiThread { setPageStatus(getString(R.string.translation_available), 2) }
            }
            override fun onTranslationStateChange(session: GeckoSession, translationState: TranslationsController.SessionTranslation.TranslationState?) {
                val language = translationState?.detectedLanguages?.docLangTag
                if (!language.isNullOrBlank()) detectedPageLanguages[tabKey(session)] = language
            }
        })
        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onAndroidPermissionsRequest(session: GeckoSession, permissions: Array<String>?, callback: GeckoSession.PermissionDelegate.Callback) {
                val tab = tabs.firstOrNull { it.session === session }
                val origin = tab?.lastUrl.orEmpty()
                val allowSiteOverride = tab != null && !tab.isPrivate && !tab.isGuest
                val blockedBySite = permissions.orEmpty().any { permission ->
                    val token = when {
                        permission == Manifest.permission.CAMERA -> "camera"
                        permission == Manifest.permission.RECORD_AUDIO -> "microphone"
                        permission == Manifest.permission.ACCESS_FINE_LOCATION || permission == Manifest.permission.ACCESS_COARSE_LOCATION -> "location"
                        Build.VERSION.SDK_INT >= 33 && permission == Manifest.permission.POST_NOTIFICATIONS -> "notifications"
                        Build.VERSION.SDK_INT >= 37 && permission == Manifest.permission.ACCESS_LOCAL_NETWORK -> "local_network_access"
                        else -> null
                    }
                    token != null && geckoPermissionDecision(origin, token, allowSiteOverride) == "BLOCK"
                }
                if (blockedBySite) { callback.reject(); return }
                val missing = permissions?.distinct().orEmpty().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) { callback.grant(); return }
                if (pendingAndroidPermissionCallback != null) { callback.reject(); return }
                pendingAndroidPermissionCallback = callback
                requestPermissions(missing.toTypedArray(), REQUEST_SITE_PERMISSIONS)
            }

            @OptIn(org.mozilla.geckoview.ExperimentalGeckoViewApi::class)
            @androidx.annotation.OptIn(markerClass = [org.mozilla.geckoview.ExperimentalGeckoViewApi::class])
            override fun onContentPermissionRequest(session: GeckoSession, perm: GeckoSession.PermissionDelegate.ContentPermission): GeckoResult<Int> {
                if (perm.value == GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW) return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                if (perm.value == GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY) return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                if (perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE || perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE) {
                    val allowed = siteSettingAllowed(BrowserEngineType.GECKOVIEW, perm.uri, "autoplay", false)
                    return GeckoResult.fromValue(if (allowed) GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                }
                val token = geckoPermissionToken(perm.permission)
                val decision = geckoPermissionDecision(perm.uri, token, allowSiteOverride = !perm.privateMode && perm.contextId.isNullOrBlank())
                if (decision == "ALLOW") return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                if (decision == "BLOCK") return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                val label = sitePermissionLabel(perm.permission)
                val host = Uri.parse(perm.uri).host ?: perm.uri
                val result = GeckoResult<Int>()
                var completed = false
                fun finish(value: Int) { if (!completed) { completed = true; result.complete(value) } }
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.site_permission_request, label))
                    .setMessage(getString(R.string.site_permission_message, host, label))
                    .setPositiveButton(getString(R.string.allow)) { _, _ -> finish(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW) }
                    .setNegativeButton(getString(R.string.block)) { _, _ -> finish(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY) }
                    .create()
                registerPromptDialog(dialog) { finish(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY) }
                perm.notifyShown()
                return result
            }

            override fun onMediaPermissionRequest(session: GeckoSession, uri: String, video: Array<GeckoSession.PermissionDelegate.MediaSource>?, audio: Array<GeckoSession.PermissionDelegate.MediaSource>?, callback: GeckoSession.PermissionDelegate.MediaCallback) {
                val host = Uri.parse(uri).host ?: uri
                val tab = tabs.firstOrNull { it.session === session }
                val allowSiteOverride = tab != null && !tab.isPrivate && !tab.isGuest
                val requestedTokens = buildList {
                    if (!video.isNullOrEmpty()) add("camera")
                    if (!audio.isNullOrEmpty()) add("microphone")
                }
                if (requestedTokens.isEmpty()) { callback.reject(); return }
                val decisions = requestedTokens.map { geckoPermissionDecision(uri, it, allowSiteOverride) }
                if (decisions.any { it == "BLOCK" }) { callback.reject(); return }
                if (decisions.all { it == "ALLOW" }) {
                    callback.grant(video?.firstOrNull(), audio?.firstOrNull())
                    return
                }
                val tokensToAsk = requestedTokens.filterIndexed { index, _ -> decisions[index] == "ASK" }
                val requested = buildList {
                    if ("camera" in tokensToAsk) add(getString(R.string.camera))
                    if ("microphone" in tokensToAsk) add(getString(R.string.microphone))
                }.joinToString(getString(R.string.and_separator))
                val normalizedOrigin = cefriumOriginForUrl(uri)
                val explicitlyAskEveryTime = tokensToAsk.filter { token ->
                    val key = normalizedOrigin?.let { "site_permission_geckoview_${token}_${Uri.encode(it)}" }
                    key != null && prefs.getString(key, null)?.equals("ASK", ignoreCase = true) == true
                }.toSet()
                var completed = false
                fun finish(allow: Boolean, explicitDecision: String? = null) {
                    if (completed) return
                    completed = true
                    if (explicitDecision != null && allowSiteOverride) {
                        tokensToAsk.filterNot { it in explicitlyAskEveryTime }
                            .forEach { saveGeckoPermissionDecision(uri, it, explicitDecision) }
                    }
                    if (allow) callback.grant(video?.firstOrNull(), audio?.firstOrNull()) else callback.reject()
                }
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.media_permission_title))
                    .setMessage(getString(R.string.media_permission_message, host, requested))
                    .setPositiveButton(getString(R.string.allow)) { _, _ -> finish(true, "ALLOW") }
                    .setNegativeButton(getString(R.string.block)) { _, _ -> finish(false, "BLOCK") }
                    .create()
                // Dismissal (Back, outside tap, or Activity teardown) rejects only this request.
                registerPromptDialog(dialog) { finish(false) }
            }
        }

        session.mediaSessionDelegate = object : org.mozilla.geckoview.MediaSession.Delegate {
            override fun onActivated(session: GeckoSession, mediaSession: org.mozilla.geckoview.MediaSession) {
                tab.mediaSession = mediaSession
                mediaSession.muteAudio(!siteSettingAllowed(BrowserEngineType.GECKOVIEW, tab.lastUrl, "sound", true))
            }
            override fun onDeactivated(session: GeckoSession, mediaSession: org.mozilla.geckoview.MediaSession) {
                if (tab.mediaSession === mediaSession) tab.mediaSession = null
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            @Suppress("DEPRECATION")
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                runOnUiThread { setBrowserFullscreen(fullScreen, tab) }
            }

            override fun onCrash(session: GeckoSession) { recoverGeckoRenderer(tab, session, "crashed") }
            override fun onKill(session: GeckoSession) { recoverGeckoRenderer(tab, session, "was stopped by Android") }
            override fun onFirstContentfulPaint(session: GeckoSession) {
                tab.rendererRecoveryAttempts = BrowserRendererRecoveryPolicy.resetAfterSuccessfulPaint()
            }

            override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: GeckoSession.ContentDelegate.ContextElement) {
                val tab = tabs.firstOrNull { it.session === session } ?: return
                val link = element.linkUri?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                val image = element.srcUri?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                val actions = mutableListOf<Pair<String, () -> Unit>>()
                val openTarget = link ?: image
                if (openTarget != null) actions += getString(R.string.open_in_new_tab) to { createTab(tab.isPrivate, openTarget, tab.isGuest) }
                if (link != null && !tab.isGuest) actions += getString(R.string.open_in_private_tab) to { createTab(true, link) }
                if (link != null) actions += getString(R.string.copy_link) to {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.link), link))
                    toast(getString(R.string.link_copied))
                }
                if (image != null && !tab.isPrivate && !tab.isGuest && element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE) actions += getString(R.string.save_image) to {
                    runCatching {
                        val fileName = "Nova-${Uri.parse(image).lastPathSegment ?: "image"}".replace(Regex("[^A-Za-z0-9._-]"), "_")
                        downloadsFor(tab).enqueue(image, fileName, "image/*")
                        toast(getString(R.string.image_download_started))
                    }.onFailure { toast(getString(R.string.image_save_failed)) }
                }
                if (openTarget != null) actions += getString(R.string.share_link) to {
                    val share = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, openTarget) }
                    startActivity(Intent.createChooser(share, getString(R.string.share_link)))
                }
                if (actions.isNotEmpty()) showBottomSheetMenu(
                    element.title?.takeIf { it.isNotBlank() } ?: element.altText?.takeIf { it.isNotBlank() } ?: getString(R.string.link_actions),
                    actions
                )
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                val tab = tabs.firstOrNull { it.session === session }
                val body = response.body
                if (tab == null || tab.isPrivate || tab.isGuest) {
                    runCatching { body?.close() }
                    toast("Downloads are disabled in private and Guest Mode")
                    return
                }
                if (body == null) { toast("This download has no readable response body"); return }
                val rawName = DownloadProtocol.fileNameFromResponse(response.uri, response.headers["Content-Disposition"] ?: response.headers["content-disposition"])
                    .ifBlank { "nova-download-${System.currentTimeMillis()}" }
                val mime = response.headers["Content-Type"] ?: response.headers["content-type"] ?: "application/octet-stream"
                requestDownloadNotificationPermission()
                downloadsFor(tab).saveExternalStream(body, rawName, mime.substringBefore(';'))
                toast("Webpage download received · saving to Downloads/Nova/${tab.engineType.name.lowercase(Locale.ROOT)}")
            }
            override fun onTitleChange(session: GeckoSession, title: String?) {
                if (!title.isNullOrBlank()) {
                    val tab = tabs.firstOrNull { it.session === session } ?: return
                    if (tab.renamedTitle.isNullOrBlank()) tab.title = title
                    syncGeckoEngineState(tab)
                    if (currentTab() === tab) updateTabChrome(tab)
                }
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                val previousReaderUrl = aiPageUrl[tab.id]
                val fragmentOnlyNavigation = previousReaderUrl != null && previousReaderUrl != url &&
                    sameDocumentUrl(previousReaderUrl, url)
                if (!fragmentOnlyNavigation) {
                    aiPageText.remove(tab.id)
                    aiPageUrl.remove(tab.id)
                }
                if (!(tab.isErrorPage && url.startsWith("data:"))) tab.isErrorPage = false
                tab.isLoading = true
                tab.progress = 0
                syncGeckoEngineState(tab)
                if (!tab.isErrorPage) tab.securityStatus = ""
                if (currentTab() === tab) runOnUiThread {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = 0
                    if (!tab.isErrorPage) resetPageStatus(getString(R.string.loading_page), 3)
                }
            }
            override fun onProgressChange(session: GeckoSession, progress: Int) {
                tab.progress = progress.coerceIn(0, 100)
                syncGeckoEngineState(tab)
                if (currentTab() === tab) runOnUiThread { progressBar.progress = tab.progress }
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                tab.isLoading = false
                if (success) tab.progress = 100
                syncGeckoEngineState(tab)
                runOnUiThread {
                    if (pendingTranslationTabId == tab.id) {
                        pendingTranslationTabId = null
                        if (success && !tab.isErrorPage && currentTab() === tab && tab.engineType == BrowserEngineType.GECKOVIEW) {
                            showTranslationLanguagePicker(tab)
                        } else if (!success && currentTab() === tab) {
                            toast("The page could not finish loading for translation")
                        }
                    }
                    if (currentTab() === tab) {
                        progressBar.visibility = View.GONE
                        if (success && !tab.isErrorPage) captureTabThumbnail(tab)
                        setPageStatus(if (tab.isErrorPage) tab.securityStatus else if (success) tab.securityStatus.ifBlank { getString(R.string.ready_for_host, Uri.parse(tab.lastUrl).host ?: "Nova") } else getString(R.string.page_failed_to_load), if (tab.isErrorPage || !success) 5 else 4)
                    }
                }
            }
            override fun onSecurityChange(session: GeckoSession, securityInfo: GeckoSession.ProgressDelegate.SecurityInformation) {
                if (tab.isErrorPage) return
                if (currentTab() === tab) runOnUiThread {
                    val host = securityInfo.host.ifBlank { Uri.parse(tab.lastUrl).host ?: "" }
                    tab.securityStatus = when (BrowserUiState.securityState(securityInfo.isSecure, securityInfo.isException)) {
                        BrowserSecurityState.CERTIFICATE_WARNING -> getString(R.string.certificate_warning, host)
                        BrowserSecurityState.SECURE -> getString(R.string.secure_connection, host)
                        BrowserSecurityState.NOT_SECURE -> getString(R.string.not_secure, host)
                    }
                    setPageStatus(tab.securityStatus, 4)
                    if (host.isNotBlank()) {
                        val styled = SpannableString(tab.securityStatus)
                        val start = tab.securityStatus.lastIndexOf(host)
                        if (start >= 0) styled.setSpan(StyleSpan(Typeface.BOLD), start, start + host.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        pageStatus.text = styled
                    }
                }
            }
        }
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
                val source = tabs.firstOrNull { it.session === session } ?: return GeckoResult.fromValue(null)
                if (!siteSettingAllowed(BrowserEngineType.GECKOVIEW, source.lastUrl, "popups", false)) {
                    if (currentTab() === source) toast("Popup blocked for ${Uri.parse(source.lastUrl).host ?: "this site"}")
                    return GeckoResult.fromValue(null)
                }
                val newSettings = GeckoSessionSettings.Builder()
                    .usePrivateMode(source.isPrivate || source.isGuest)
                    .contextId(if (source.isGuest) guestContextId else null)
                    .userAgentMode(if (source.desktopSite) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                    .viewportMode(if (source.desktopSite) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                    .allowJavascript(isJavaScriptAllowed(BrowserEngineType.GECKOVIEW, uri))
                    .build()
                val newSession = GeckoSession(newSettings)
                val newTab = BrowserTab(
                    session = newSession,
                    isPrivate = source.isPrivate,
                    isGuest = source.isGuest,
                    lastUrl = uri,
                    group = source.group,
                    title = getString(R.string.new_tab),
                    desktopSite = source.desktopSite
                )
                newTab.engineType = BrowserEngineType.GECKOVIEW
                val popupEngine = GeckoViewBrowserEngine(newSession, browserView)
                newTab.browserEngine = popupEngine
                popupEngine.installDelegates { configureSession(it, newTab) }
                if (!newTab.isPrivate && !newTab.isGuest) attachAiPageReader(newSession, newTab)
                (currentTab()?.browserEngine as? GeckoViewBrowserEngine)?.detach()
                tabs.add(newTab)
                activeIndex = tabs.lastIndex
                popupEngine.attachTo(browserView)
                showPrimaryEngineView(popupEngine.view)
                popupEngine.setActive(true, true)
                if (!addressBar.hasFocus()) addressBar.setText(uri)
                updateTabChrome(newTab)
                if (!newTab.isPrivate && !newTab.isGuest) persistTabs()
                showBrowserPage()
                enforceSessionBudget(setOf(source.id))
                return GeckoResult.fromValue(newSession)
            }
            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
                when (NovaHttpsOnlyPolicy.decide(request.uri, httpsOnlyEnabled())) {
                    NovaHttpsOnlyPolicy.Decision.UPGRADE_TO_HTTPS, NovaHttpsOnlyPolicy.Decision.BLOCK -> {
                        runOnUiThread {
                            if (currentTab()?.id == tab.id) toast("HTTPS-Only blocked an HTTP page request. Open the HTTPS version to continue.")
                        }
                        return GeckoResult.deny()
                    }
                    NovaHttpsOnlyPolicy.Decision.ALLOW -> Unit
                }
                if (BrowserVpnNavigationPolicy.shouldBlockNavigation(
                        NovaBrowserVpnController.isVpnEnabled(), NovaBrowserVpnController.isVpnRoutingReady()
                    )) {
                    // GeckoView's LoadRequest does not expose the HTTP method or body.
                    // Do not replay an unknown request as a URL-only GET: that could silently
                    // change a blocked form submission into a different request.
                    runOnUiThread {
                        applyVpnRoutingReadiness()
                        if (currentTab()?.id == tab.id) setPageStatus("1VPN is not ready · this request was blocked; retry after protected routing is restored", 1)
                    }
                    return GeckoResult.deny()
                }
                session.settings.setAllowJavascript(isJavaScriptAllowed(BrowserEngineType.GECKOVIEW, request.uri))
                return null
            }
            override fun onLoadError(session: GeckoSession, uri: String?, error: org.mozilla.geckoview.WebRequestError): GeckoResult<String> {
                val failed = uri?.takeIf { it.isNotBlank() } ?: tab.lastUrl
                val safeUrl = failed.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;")
                val certificateError = error.category == org.mozilla.geckoview.WebRequestError.ERROR_CATEGORY_SECURITY
                val title = if (certificateError) getString(R.string.certificate_error_title) else getString(R.string.page_unavailable)
                val message = if (certificateError) getString(R.string.certificate_error_message) else getString(R.string.page_unavailable_message)
                fun cssColor(color: Int) = String.format(Locale.ROOT, "#%06X", 0xFFFFFF and color)
                val background = cssColor(getColor(R.color.nova_background))
                val foreground = cssColor(getColor(R.color.nova_text_primary))
                val muted = cssColor(getColor(R.color.nova_text_secondary))
                val html = "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'><meta name=color-scheme content=dark></head><body style='margin:0;padding:24px;background:$background;color:$foreground;font:16px sans-serif'><h2>$title</h2><p>$message</p><p style='color:$muted;overflow-wrap:anywhere'>$safeUrl</p><a style='color:$foreground' href='$safeUrl'>${getString(R.string.try_again)}</a></body></html>"
                tab.isLoading = false
                tab.isErrorPage = true
                tab.securityStatus = if (certificateError) getString(R.string.certificate_warning, Uri.parse(failed).host ?: failed) else getString(R.string.navigation_error)
                if (currentTab() === tab) runOnUiThread {
                    progressBar.visibility = View.GONE
                    setPageStatus(tab.securityStatus, 5)
                }
                return GeckoResult.fromValue("data:text/html;charset=utf-8,${Uri.encode(html)}")
            }
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                tab.canGoBack = canGoBack
                syncGeckoEngineState(tab)
            }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                tab.canGoForward = canGoForward
                syncGeckoEngineState(tab)
            }
            override fun onLocationChange(session: GeckoSession, url: String?, perms: List<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                if (url.isNullOrBlank()) return
                if (tab.isErrorPage && url.startsWith("data:")) return
                tab.lastUrl = url
                syncGeckoEngineState(tab)
                tab.mediaSession?.muteAudio(!siteSettingAllowed(BrowserEngineType.GECKOVIEW, url, "sound", true))
                tab.contentPermissions = perms
                tab.trackingPermission = perms.firstOrNull { it.permission == GeckoSession.PermissionDelegate.PERMISSION_TRACKING }
                if (!tab.isPrivate && !tab.isGuest && !url.startsWith("about:reader")) { addHistory(url); persistTabs() }
                if (tab.id == splitTabId && ::secondaryAddressBar.isInitialized && !secondaryAddressBar.hasFocus()) secondaryAddressBar.setText(url)
                if (currentTab() === tab) {
                    if (!addressBar.hasFocus()) addressBar.setText(url)
                    refreshBookmarkButton()
                    updateTabChrome(tab)
                    if (url.startsWith("about:")) resetPageStatus(getString(R.string.nova_internal_page), 0) else resetPageStatus(getString(R.string.loading_page), 3)
                }
            }
        }
    }



    /** Keep the engine-neutral state snapshot in sync with Gecko's delegates. */
    private fun syncGeckoEngineState(tab: BrowserTab) {
        val engine = tab.browserEngine as? GeckoViewBrowserEngine ?: return
        engine.updateState(
            BrowserEngineState(
                url = tab.lastUrl,
                title = tab.title,
                isLoading = tab.isLoading,
                progress = tab.progress.coerceIn(0, 100),
                canGoBack = tab.canGoBack,
                canGoForward = tab.canGoForward,
                error = if (tab.isErrorPage) tab.securityStatus.ifBlank { "Page failed to load" } else null
            )
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && ::pageStatus.isInitialized) {
            // Release background renderers promptly when Android signals memory pressure.
            enforceSessionBudget(memoryPressure = true)
        }
    }

    private fun enforceSessionBudget(
        preserveTabIds: Set<String> = emptySet(),
        memoryPressure: Boolean = false
    ) {
        val activeSessions = tabs.filter { it.browserEngine != null || it.session != null }
        val candidates = activeSessions.map { tab ->
            SessionBudgetPolicy.Candidate(tab.id, tab.lastAccess, tab.isPrivate, tab.isGuest)
        }
        val visibleIds = buildSet {
            currentTab()?.id?.let(::add)
            splitTabId?.let(::add)
            addAll(preserveTabIds)
        }
        val activityManager = getSystemService(android.app.ActivityManager::class.java)
        val memoryInfo = android.app.ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val limit = SessionBudgetPolicy.maxRetainedSessions(
            activityManager.memoryClass,
            memoryPressure = memoryPressure || memoryInfo.lowMemory
        )
        val evictedIds = SessionBudgetPolicy.evictionCandidates(candidates, limit, visibleIds).toSet()
        activeSessions.filter { it.id in evictedIds }.forEach { tab ->
            closeTabRuntime(tab)
            tab.needsReload = tab.lastUrl != HOME_URL
            tab.wasSleepingForMemoryBudget = true
        }

        val activeTab = currentTab()
        if (activeTab?.isGuest == true) resetPageStatus(getString(R.string.guest_session_status), 0)
        else if (activeTab?.isPrivate == true) resetPageStatus(getString(R.string.private_session_status), 0)
        else if (evictedIds.isNotEmpty()) setPageStatus(getString(R.string.memory_saver_status), 1)
    }

    private fun showFindBar() {
        val tab = currentTab() ?: return
        if (homeScroll.visibility == View.VISIBLE) { toast(getString(R.string.open_page_to_find)); return }
        val existing = findBar
        if (existing == null) {
            val input = EditText(this).apply {
                hint = getString(R.string.find_in_page)
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                minHeight = dp(48)
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val query = s?.toString().orEmpty()
                        if (query.isBlank()) tab.browserEngine?.stopFinding() else findInPage(query, GeckoSession.FINDER_FIND_FORWARD)
                    }
                    override fun afterTextChanged(s: android.text.Editable?) = Unit
                })
            }
            findInput = input
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(4))
                addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(actionButton(getString(R.string.find_previous)) { findInPage(null, GeckoSession.FINDER_FIND_BACKWARDS) })
                addView(actionButton(getString(R.string.find_next)) { findInPage(null, GeckoSession.FINDER_FIND_FORWARD) })
                addView(actionButton(getString(R.string.close)) { closeFindBar() })
            }
            findBar = bar
        }
        findBar?.let { bar -> if (bar.parent == null) topChrome.addView(bar, 1); bar.visibility = View.VISIBLE }
        findInput?.requestFocus()
        val keyboard = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        findInput?.let { keyboard.showSoftInput(it, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun findInPage(query: String?, flags: Int) {
        val tab = currentTab() ?: return
        if (!canUseTabEngine(tab, "Find in page")) return
        if (tab.engineType == BrowserEngineType.CEFRIUM) {
            val text = query ?: findInput?.text?.toString().orEmpty()
            if (text.isBlank()) tab.browserEngine?.stopFinding()
            else {
                ensureBrowserEngine(tab).findInPage(text, flags == GeckoSession.FINDER_FIND_FORWARD, query == null)
                setPageStatus("Searching page…", 2)
            }
            return
        }
        val session = tab.session ?: return
        session.getFinder().setDisplayFlags(GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL)
        session.getFinder().find(query, flags).accept({ result ->
            runOnUiThread {
                val finderResult = result
                if (finderResult == null) {
                    setPageStatus(getString(R.string.find_no_matches), 2)
                } else {
                    setPageStatus(if (finderResult.found) {
                        if (finderResult.total > 0) getString(R.string.find_result_count, finderResult.current, finderResult.total) else getString(R.string.find_match_number, finderResult.current)
                    } else getString(R.string.find_no_matches), 2)
                }
            }
        }, { runOnUiThread { setPageStatus(getString(R.string.find_failed), 2) } })
    }

    private fun closeFindBar() {
        currentTab()?.browserEngine?.stopFinding()
        findBar?.let { topChrome.removeView(it) }
        findBar = null
        findInput = null
    }

    private fun sitePermissionLabel(permission: Int): String = when (permission) {
        GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> getString(R.string.permission_location)
        GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> getString(R.string.permission_notifications)
        GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> getString(R.string.permission_autoplay_audio)
        GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> getString(R.string.permission_autoplay_media)
        GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> getString(R.string.permission_persistent_storage)
        GeckoSession.PermissionDelegate.PERMISSION_XR -> getString(R.string.permission_xr)
        GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> getString(R.string.permission_protected_media)
        GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS -> getString(R.string.permission_storage_access)
        GeckoSession.PermissionDelegate.PERMISSION_TRACKING -> getString(R.string.permission_tracking)
        GeckoSession.PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS -> getString(R.string.permission_local_device)
        GeckoSession.PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS -> getString(R.string.permission_local_network)
        else -> getString(R.string.permission_unknown)
    }

    private fun recoverGeckoRenderer(tab: BrowserTab, crashedSession: GeckoSession, reason: String) {
        if (tab.session !== crashedSession) return
        runOnUiThread {
            if (tab.session !== crashedSession) return@runOnUiThread
            val active = currentTab() === tab
            val secondary = tab.id == splitTabId
            val shouldRecover = BrowserRendererRecoveryPolicy.shouldAutomaticallyRecover(tab.rendererRecoveryAttempts)
            tab.rendererRecoveryAttempts = BrowserRendererRecoveryPolicy.nextAttempt(tab.rendererRecoveryAttempts)
            (tab.browserEngine as? GeckoViewBrowserEngine)?.markRendererTerminated()
            closeTabRuntime(tab)
            tab.engineType = BrowserEngineType.GECKOVIEW
            tab.cefriumNavigationState = null
            tab.needsReload = tab.lastUrl != HOME_URL
            tab.hasCrashed = true
            if (shouldRecover) {
                if (active || secondary) {
                    val engine = runCatching { ensureBrowserEngine(tab) as? GeckoViewBrowserEngine }.getOrNull()
                    if (engine == null) {
                        tab.hasCrashed = true
                        if (active) showCrashedTabDialog(tab)
                        else toast("GeckoView recovery could not start; select the tab and retry")
                        return@runOnUiThread
                    }
                    val host = if (secondary) secondaryBrowserView else browserView
                    runCatching {
                        engine.attachTo(host)
                        if (secondary) showSecondaryEngineView(engine.view) else showPrimaryEngineView(engine.view)
                        engine.setActive(true, !secondary)
                        tab.hasCrashed = false
                    }.onFailure {
                        android.util.Log.w("NovaBrowser", "GeckoView renderer reattachment failed", it)
                        tab.hasCrashed = true
                        if (active) showCrashedTabDialog(tab)
                    }
                }
                toast("GeckoView renderer $reason · restoring the last page")
            } else {
                if (active) {
                    showPrimaryEngineView(browserView)
                    setPageStatus("Renderer stopped again · tap Reload to retry", 5)
                    showCrashedTabDialog(tab)
                } else if (secondary) {
                    showSecondaryEngineView(secondaryBrowserView)
                    toast("Split-pane renderer stopped again · select the tab to reload")
                } else {
                    toast("Background tab renderer stopped · it will reload when opened")
                }
            }
        }
    }

    private fun handleCefriumRendererTermination(tab: BrowserTab, reason: String) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            runOnUiThread { handleCefriumRendererTermination(tab, reason) }
            return
        }
        if (tab.browserEngine !is CefriumBrowserEngine) return
        val active = currentTab() === tab
        val secondary = tab.id == splitTabId
        tab.hasCrashed = true
        val shouldRecover = BrowserRendererRecoveryPolicy.shouldAutomaticallyRecover(tab.rendererRecoveryAttempts)
        tab.rendererRecoveryAttempts = BrowserRendererRecoveryPolicy.nextAttempt(tab.rendererRecoveryAttempts)
        closeTabRuntime(tab)
        tab.needsReload = tab.lastUrl != HOME_URL
        if (shouldRecover) {
            if (active || secondary) {
                val engine = runCatching { ensureBrowserEngine(tab) }.getOrNull()
                if (engine == null) {
                    if (active) showCrashedTabDialog(tab) else toast("Cefrium recovery could not start; select the tab and retry")
                    return
                }
                runCatching {
                    if (secondary) showSecondaryEngineView(engine.view) else showPrimaryEngineView(engine.view)
                    engine.setActive(true, !secondary)
                    tab.hasCrashed = false
                }.onFailure {
                    android.util.Log.w("NovaBrowser", "Cefrium renderer reattachment failed", it)
                    if (active) showCrashedTabDialog(tab)
                }
            }
            toast("Cefrium renderer stopped · restoring the last page")
        } else {
            if (active) {
                showPrimaryEngineView(browserView)
                setPageStatus("Cefrium renderer stopped again · tap Reload to retry", 5)
                showCrashedTabDialog(tab)
            } else if (secondary) {
                showSecondaryEngineView(secondaryBrowserView)
                toast("Split-pane Cefrium renderer stopped again · select the tab to reload")
            } else {
                toast("Background Cefrium renderer stopped · it will reload when opened")
            }
        }
    }

    private fun showCrashedTabDialog(tab: BrowserTab) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.tab_crashed_title))
            .setMessage(getString(R.string.tab_crashed_message))
            .setPositiveButton(getString(R.string.reload_page)) { _, _ ->
                tab.rendererRecoveryAttempts = BrowserRendererRecoveryPolicy.resetAfterSuccessfulPaint()
                tab.hasCrashed = false
                tab.engineType = BrowserEngineType.GECKOVIEW
                tab.cefriumNavigationState = null
                tab.needsReload = true
                if (currentTab() === tab) activateTab(tab)
            }
            .setNegativeButton(getString(R.string.close_tab)) { _, _ ->
                val index = tabs.indexOfFirst { it.id == tab.id }
                if (index >= 0) closeTabAt(index, confirmed = true)
            }
            .setOnCancelListener { setPageStatus(getString(R.string.tab_crashed_status), 5) }
            .show()
    }

    private fun setPageStatus(message: String, priority: Int = 0) {
        statusState = BrowserUiState.updateStatus(statusState, message, priority)
        if (::pageStatus.isInitialized) pageStatus.text = statusState.text
    }

    private fun resetPageStatus(message: String, priority: Int = 0) {
        statusState = BrowserStatusState(message, priority)
        if (::pageStatus.isInitialized) pageStatus.text = message
    }

    private fun displayTabTitle(tab: BrowserTab): String = tab.renamedTitle?.takeIf { it.isNotBlank() } ?: tab.title.ifBlank { Uri.parse(tab.lastUrl).host ?: "New tab" }

    private fun updateTabChrome(tab: BrowserTab) {
        val modes = tabs.map { BrowserUiState.modeFor(it.isPrivate, it.isGuest) }
        val activePosition = tabs.indexOfFirst { it.id == tab.id }
        val mode = BrowserUiState.modeFor(tab.isPrivate, tab.isGuest)
        val ordinal = BrowserUiState.modeOrdinal(modes, activePosition)
        val modeName = getString(when (mode) {
            BrowserMode.NORMAL -> R.string.mode_normal
            BrowserMode.PRIVATE -> R.string.mode_private
            BrowserMode.GUEST -> R.string.mode_guest
        })
        val modeCount = BrowserUiState.modeCount(modes, mode)
        tabLabel.text = getString(R.string.tab_mode_label, modeName, ordinal, modeCount, displayTabTitle(tab).take(20))
        val accent = when { tab.isGuest -> getColor(R.color.nova_guest_accent); tab.isPrivate -> getColor(R.color.nova_private_accent); else -> getColor(R.color.nova_accent) }
        tabLabel.background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(14).toFloat() }
        tabLabel.setTextColor(getColor(R.color.nova_on_accent))
        if (::bottomToolbar.isInitialized) bottomToolbar.background = GradientDrawable().apply {
            setColor(getColor(R.color.nova_surface))
            setStroke(dp(2), accent)
            cornerRadius = dp(12).toFloat()
        }
        if (::tabsButton.isInitialized) {
            tabsButton.text = getString(R.string.tabs_button_count, tabs.size)
            tabsButton.contentDescription = getString(R.string.tabs_count, tabs.size)
        }
        refreshBookmarkButton()
    }

    private fun refreshBookmarkButton() {
        val tab = currentTab() ?: run { currentPageBookmarked = false; return }
        currentPageBookmarked = !tab.isPrivate && !tab.isGuest && readBookmarks().any { it.url == tab.lastUrl }
        if (::bookmarkButton.isInitialized) {
            bookmarkButton.setImageResource(if (currentPageBookmarked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_outline)
            bookmarkButton.contentDescription = getString(if (currentPageBookmarked) R.string.bookmark_remove else R.string.bookmark_page)
            bookmarkButton.isEnabled = !tab.isPrivate && !tab.isGuest
            bookmarkButton.alpha = if (bookmarkButton.isEnabled) 1f else 0.45f
        }
    }

    private fun currentTab(): BrowserTab? = tabs.getOrNull(activeIndex)

    private fun createDownloadManager(engine: BrowserEngineType): NovaDownloadManager =
        NovaDownloadManager(this, engine.name.lowercase(Locale.ROOT)) { items ->
            runOnUiThread {
                val active = currentTab()
                if (active != null && !active.isPrivate && !active.isGuest && active.engineType == engine) {
                    updateDownloadSummary(items)
                }
            }
        }.also { it.resumeInterrupted() }

    private fun downloadsFor(tab: BrowserTab?): NovaDownloadManager =
        if (tab?.engineType == BrowserEngineType.CEFRIUM) cefriumDownloads else geckoDownloads

    private fun denyGuestAccess(feature: String): Boolean {
        if (currentTab()?.isGuest != true) return false
        resetPageStatus(getString(R.string.guest_session_status), 0)
        toast(getString(R.string.guest_feature_unavailable, feature))
        return true
    }

    private fun switchTab(index: Int) {
        if (index !in tabs.indices) return
        val requestedTab = tabs[index]
        if (GuestTorIsolationPolicy.shouldBlockNonGuestSession(requestedTab.isGuest, guestTorReservesGeckoRuntime())) {
            toast("Normal and Private tabs are paused until Guest Tor has fully disconnected")
            return
        }
        if (currentTab()?.isGuest == true && !requestedTab.isGuest) {
            toast("Normal tabs are hidden in Guest Mode")
            return
        }
        if (BrowserVpnNavigationPolicy.shouldBlockNavigation(
                NovaBrowserVpnController.isVpnEnabled(), NovaBrowserVpnController.isVpnRoutingReady()
            )) {
            applyVpnRoutingReadiness()
            cancelPendingRemoteSuggestions()
            activeIndex = index
            requestedTab.needsReload = requestedTab.lastUrl != HOME_URL
            if (!addressBar.hasFocus()) addressBar.setText(requestedTab.lastUrl)
            updateTabChrome(requestedTab)
            showHomeScreen()
            setPageStatus("1VPN is connecting · tabs are paused until protected routing is ready", 1)
            return
        }
        if (pendingGeckoDataClearActive && engineSelectionForTab(requestedTab).engine == BrowserEngineType.GECKOVIEW &&
            !requestedTab.isPrivate && !requestedTab.isGuest) {
            toast("Wait for GeckoView data deletion to finish before opening this tab")
            return
        }
        cancelPendingRemoteSuggestions()
        if (!tabs[index].isPrivate && !tabs[index].isGuest && tabs[index].engineType == BrowserEngineType.GECKOVIEW && pendingGeckoDataClearActive) {
            toast("This tab is temporarily unavailable while browsing data is being deleted")
            return
        }
        if (currentTab()?.isGuest == true && !tabs[index].isGuest) {
            toast("Normal tabs are hidden in Guest Mode")
            return
        }
        currentTab()?.let { previous ->
            if (homeScroll.visibility != View.VISIBLE) captureTabThumbnail(previous)
            deactivateTabRuntime(previous)
        }
        activeIndex = index
        val tab = tabs[index]
        tab.lastAccess = System.nanoTime()
        if (splitTabId == tab.id) exitSplitScreen()
        activateTab(tab)
        if (!addressBar.hasFocus()) addressBar.setText(tab.lastUrl)
        updateTabChrome(tab)
        resetPageStatus(when { tab.isGuest -> getString(R.string.guest_session_status); tab.isPrivate -> getString(R.string.private_session_status); else -> getString(R.string.normal_session_status) }, 0)
        if (tab.lastUrl != HOME_URL) {
            when {
                tab.isErrorPage -> setPageStatus(tab.securityStatus.ifBlank { getString(R.string.navigation_error) }, 5)
                tab.isLoading -> setPageStatus(getString(R.string.loading_page), 3)
                tab.securityStatus.isNotBlank() -> setPageStatus(tab.securityStatus, 4)
            }
        }
        if (tab.lastUrl == HOME_URL) showHomeScreen() else showBrowserPage()
        enforceSessionBudget()
        persistTabs()
    }

    private fun chooseSplitTab() {
        if (denyGuestAccess("Split screen")) return
        val activeTab = currentTab() ?: return
        if (!canUseTabEngine(activeTab, "Split screen")) return
        val candidates = tabs.filter {
            !it.isPrivate && !it.isGuest && it !== currentTab() &&
                (!pendingGeckoDataClearActive || engineSelectionForTab(it).engine == BrowserEngineType.CEFRIUM) &&
                !guestTorReservesGeckoRuntime()
        }
        if (candidates.isEmpty()) { toast("Open another normal tab before using split screen"); return }
        AlertDialog.Builder(this).setTitle("Open second pane")
            .setItems(candidates.map { it.title.ifBlank { it.lastUrl } }.toTypedArray()) { _, which ->
                // Guest Tor may finish connecting while this chooser is open.
                if (denyGuestAccess("Split screen")) return@setItems
                val tab = candidates[which]
                if (!canUseTabEngine(tab, "Split screen")) return@setItems
                if (splitTabId != null) exitSplitScreen()
                enforceCefriumCompatibility(tab)
                if (pendingGeckoDataClearActive && tab.engineType == BrowserEngineType.GECKOVIEW) {
                    toast("Wait for GeckoView data deletion to finish before opening this tab in split screen")
                    return@setItems
                }
                splitTabId = tab.id
                secondaryPane.visibility = View.VISIBLE
                browserPane.visibility = View.VISIBLE
                homeScroll.visibility = View.GONE
                applyFoldAwareLayout(separatingFold)
                val engine = ensureBrowserEngine(tab)
                if (engine.type == BrowserEngineType.GECKOVIEW) engine.attachTo(secondaryBrowserView)
                showSecondaryEngineView(engine.view)
                engine.setActive(true, false)
                secondaryAddressBar.setText(tab.lastUrl)
                toast("Split screen enabled")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun exitSplitScreen() {
        val second = tabs.firstOrNull { it.id == splitTabId }
        deactivateTabRuntime(second)
        secondaryPane.visibility = View.GONE
        activeSecondaryEngineView = null
        splitTabId = null
        browserView.visibility = if (activePrimaryEngineView == null || activePrimaryEngineView === browserView) View.VISIBLE else View.GONE
        if (homeScroll.visibility == View.VISIBLE) browserPane.visibility = View.GONE
        applyFoldAwareLayout(separatingFold)
        enforceSessionBudget()
    }

    private fun restoreSplitPane() {
        if (guestTorReservesGeckoRuntime() || BrowserVpnNavigationPolicy.shouldBlockNavigation(NovaBrowserVpnController.isVpnEnabled(), NovaBrowserVpnController.isVpnRoutingReady())) {
            if (splitTabId != null) exitSplitScreen()
            return
        }
        if (currentTab()?.isGuest == true) {
            if (splitTabId != null) exitSplitScreen()
            return
        }
        val second = tabs.firstOrNull { it.id == splitTabId } ?: run {
            splitTabId = null
            secondaryPane.visibility = View.GONE
            applyFoldAwareLayout(separatingFold)
            return
        }
        enforceCefriumCompatibility(second)
        if (pendingGeckoDataClearActive && engineSelectionForTab(second).engine == BrowserEngineType.GECKOVIEW &&
            !second.isPrivate && !second.isGuest) {
            splitTabId = null
            secondaryPane.visibility = View.GONE
            applyFoldAwareLayout(separatingFold)
            toast("Split screen will be available after GeckoView data deletion finishes")
            return
        }
        secondaryPane.visibility = View.VISIBLE
        browserPane.visibility = View.VISIBLE
        applyFoldAwareLayout(separatingFold)
        val engine = ensureBrowserEngine(second)
        if (engine.type == BrowserEngineType.GECKOVIEW) engine.attachTo(secondaryBrowserView)
        showSecondaryEngineView(engine.view)
        if (::secondaryAddressBar.isInitialized) secondaryAddressBar.setText(second.lastUrl)
        if (currentTab()?.lastUrl == HOME_URL) {
            homeScroll.visibility = View.VISIBLE
            browserView.visibility = View.GONE
        } else {
            homeScroll.visibility = View.GONE
            browserView.visibility = if (activePrimaryEngineView == null || activePrimaryEngineView === browserView) View.VISIBLE else View.GONE
        }
        engine.setActive(true, false)
    }

    private fun navigateSecondary(input: String) {
        val tab = tabs.firstOrNull { it.id == splitTabId } ?: return
        val resolved = BrowserUrlResolver.resolve(input, searchEngine) ?: return
        val target = applyHttpsOnlyPolicy(resolved) ?: return
        if (target != HOME_URL && !selectEngineForNavigation(tab, target)) return
        prepareDesktopModeForNavigation(tab, target)
        loadSecondaryTabUrl(tab, target)
        if (!secondaryAddressBar.hasFocus()) secondaryAddressBar.setText(target)
        if (!tab.isPrivate && !tab.isGuest) persistTabs()
    }


    private fun loadSecondaryTabUrl(tab: BrowserTab, url: String) {
        val policyUrl = applyHttpsOnlyPolicy(url) ?: return
        if (policyUrl != url) { loadSecondaryTabUrl(tab, policyUrl); return }
        if (!canUseTabEngine(tab, "Split-screen navigation")) {
            if (url != HOME_URL) {
                tab.lastUrl = url
                tab.needsReload = true
                tab.cefriumNavigationState = null
                if (!tab.isPrivate && !tab.isGuest) persistTabs()
            }
            return
        }
        if (!selectEngineForNavigation(tab, url)) return
        val engine = ensureBrowserEngine(tab)
        if (engine.type == BrowserEngineType.GECKOVIEW) engine.attachTo(secondaryBrowserView)
        showSecondaryEngineView(engine.view)
        engine.setActive(true, false)
        val previousUrl = tab.lastUrl
        engine.loadUrl(url)
        tab.lastUrl = url
        tab.needsReload = false
        if (!tab.isPrivate && !tab.isGuest && previousUrl != url && url.startsWith("http", true)) {
            addHistory(url)
            persistTabs()
        }
    }

    private fun updateAddressSuggestions(query: String) {
        if (!::addressBar.isInitialized || !::addressSuggestionsAdapter.isInitialized || !addressBar.hasFocus()) return
        val typed = query.trim()
        if (typed.isBlank()) {
            cancelPendingRemoteSuggestions()
            addressSuggestionItems = emptyList()
            addressSuggestionsAdapter.clear()
            addressBar.dismissDropDown()
            return
        }
        val candidates = mutableListOf<BrowserSuggestion>()
        val tab = currentTab()
        if (tab?.isPrivate != true && tab?.isGuest != true) {
            readHistory().asSequence().take(80).forEach { entry ->
                candidates += BrowserSuggestion(entry.title.ifBlank { entry.url }, entry.url)
            }
            readBookmarks().asSequence().take(80).forEach { entry ->
                candidates += BrowserSuggestion(entry.title.ifBlank { entry.url }, entry.url)
            }
        }
        val ranked = BrowserUiState.filterSuggestions(typed, candidates, 6)
        val searchUrl = BrowserUrlResolver.resolve(typed, searchEngine)
        val searchSuggestion = searchUrl?.let { BrowserSuggestion(getString(R.string.search_for_query, typed), it) }
        addressSuggestionItems = (ranked + listOfNotNull(searchSuggestion)).distinctBy { it.url }.take(7)
        addressSuggestionsAdapter.clear()
        addressSuggestionsAdapter.addAll(addressSuggestionItems)
        if (addressSuggestionItems.isNotEmpty()) addressBar.showDropDown() else addressBar.dismissDropDown()
        scheduleRemoteSuggestions(typed, ranked, searchSuggestion)
    }

    private fun cancelPendingRemoteSuggestions() {
        pendingRemoteSuggestion?.let(addressSuggestionHandler::removeCallbacks)
        pendingRemoteSuggestion = null
        remoteSuggestionsAllowedForActiveTab = false
        remoteSuggestionTabId = null
        activeRemoteSuggestionCancellation?.cancel()
        activeRemoteSuggestionCancellation = null
        activeRemoteSuggestionFuture?.cancel(true)
        activeRemoteSuggestionFuture = null
    }

    private fun scheduleRemoteSuggestions(query: String, local: List<BrowserSuggestion>, searchSuggestion: BrowserSuggestion?) {
        cancelPendingRemoteSuggestions()
        val tab = currentTab() ?: return
        val enabled = prefs.getBoolean("remote_search_suggestions", false)
        if (!RemoteSearchSuggestionPolicy.shouldFetch(enabled, tab.isPrivate, tab.isGuest, query)) return
        val requestedSearchEngine = searchEngine
        val cancellation = RemoteSearchSuggestions.Cancellation()
        activeRemoteSuggestionCancellation = cancellation
        remoteSuggestionTabId = tab.id
        remoteSuggestionsAllowedForActiveTab = enabled && !tab.isPrivate && !tab.isGuest
        val request = Runnable {
            // The user may switch tabs/modes during the debounce interval. Re-check before
            // making any network request, not only after the response returns.
            val active = currentTab()
            if (isDestroyed || active !== tab || !::addressBar.isInitialized || !addressBar.hasFocus() ||
                addressBar.text?.toString()?.trim() != query || searchEngine != requestedSearchEngine ||
                !RemoteSearchSuggestionPolicy.shouldFetch(
                    prefs.getBoolean("remote_search_suggestions", false),
                    active?.isPrivate ?: true,
                    active?.isGuest ?: true,
                    query
                )) return@Runnable
            activeRemoteSuggestionFuture = remoteSuggestionExecutor.submit {
                if (Thread.currentThread().isInterrupted || cancellation.isCancelled()) return@submit
                val remote = RemoteSearchSuggestions.fetch(query, requestedSearchEngine, cancellation) {
                    !isFinishing && !isDestroyed &&
                        remoteSuggestionsAllowedForActiveTab &&
                        remoteSuggestionTabId == tab.id &&
                        prefs.getBoolean("remote_search_suggestions", false)
                }
                if (Thread.currentThread().isInterrupted || cancellation.isCancelled()) return@submit
                runOnUiThread {
                    if (cancellation.isCancelled() || activeRemoteSuggestionCancellation !== cancellation) return@runOnUiThread
                    val current = currentTab()
                    if (isDestroyed || current !== tab || !::addressBar.isInitialized || !addressBar.hasFocus() ||
                        addressBar.text?.toString()?.trim() != query || searchEngine != requestedSearchEngine ||
                        !RemoteSearchSuggestionPolicy.shouldFetch(
                            prefs.getBoolean("remote_search_suggestions", false),
                            current?.isPrivate ?: true,
                            current?.isGuest ?: true,
                            query
                        )) return@runOnUiThread
                    val remoteItems = remote.mapNotNull { label ->
                        BrowserUrlResolver.resolve(label, requestedSearchEngine)?.let { BrowserSuggestion(label, it) }
                    }
                    addressSuggestionItems = (local + remoteItems + listOfNotNull(searchSuggestion)).distinctBy { it.url }.take(7)
                    addressSuggestionsAdapter.clear()
                    addressSuggestionsAdapter.addAll(addressSuggestionItems)
                    if (addressSuggestionItems.isNotEmpty()) addressBar.showDropDown()
                }
            }
        }
        pendingRemoteSuggestion = request
        addressSuggestionHandler.postDelayed(request, 350L)
    }



    private fun cycleSiteDarkMode() {
        val tab = currentTab() ?: return
        if (tab.engineType != BrowserEngineType.CEFRIUM || tab.browserEngine !is CefriumBrowserEngine) {
            toast("Per-site page appearance is currently supported on Nova Core tabs only")
            return
        }
        val host = Uri.parse(tab.lastUrl).host?.lowercase(Locale.ROOT)
        if (host.isNullOrBlank()) { toast("No site host"); return }
        val key = "site_dark_mode_$host"
        val current = NovaSiteDarkMode.fromStored(prefs.getString(key, NovaSiteDarkMode.DEFAULT.stored))
        val next = when (current) {
            NovaSiteDarkMode.DEFAULT -> NovaSiteDarkMode.FORCE_DARK
            NovaSiteDarkMode.FORCE_DARK -> NovaSiteDarkMode.FORCE_LIGHT
            NovaSiteDarkMode.FORCE_LIGHT -> NovaSiteDarkMode.DEFAULT
        }
        prefs.edit().putString(key, next.stored).apply()
        tab.appliedSiteDarkMode = null
        applyStoredSiteDarkMode(tab, force = true)
        toast("Site appearance: ${next.name.lowercase().replace('_', ' ')}")
    }

    private fun applyStoredSiteDarkMode(tab: BrowserTab, force: Boolean = false) {
        if (tab.engineType != BrowserEngineType.CEFRIUM) return
        val engine = tab.browserEngine as? CefriumBrowserEngine ?: return
        val host = Uri.parse(tab.lastUrl).host?.lowercase(Locale.ROOT) ?: return
        val mode = NovaSiteDarkMode.fromStored(prefs.getString("site_dark_mode_$host", NovaSiteDarkMode.DEFAULT.stored))
        val appliedKey = "$host:${mode.stored}"
        if (!force && tab.appliedSiteDarkMode == appliedKey) return
        val script = NovaSiteDarkModePolicy.cssFor(mode)
        runCatching {
            engine.evaluateJavaScript(script) { }
            tab.appliedSiteDarkMode = appliedKey
        }.onFailure { android.util.Log.w("NovaBrowser", "Could not apply per-site appearance", it) }
    }

    private fun httpsOnlyEnabled(): Boolean =
        prefs.getBoolean("https_only_mode", false)

    private fun applyHttpsOnlyPolicy(url: String): String? {
        return when (NovaHttpsOnlyPolicy.decide(url, httpsOnlyEnabled())) {
            NovaHttpsOnlyPolicy.Decision.ALLOW -> url
            NovaHttpsOnlyPolicy.Decision.UPGRADE_TO_HTTPS -> {
                val upgraded = NovaHttpsOnlyPolicy.upgradeToHttps(url)
                if (upgraded != null) {
                    toast("HTTPS-Only: upgraded to HTTPS")
                    upgraded
                } else null
            }
            NovaHttpsOnlyPolicy.Decision.BLOCK -> {
                toast("HTTPS-Only blocked insecure navigation")
                null
            }
        }
    }

    private fun submitAddressBar() {
        val query = addressBar.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) return
        navigate(query)
        dismissAddressBarInput()
    }

    private fun navigate(input: String) {
        val resolved = BrowserUrlResolver.resolve(input, searchEngine) ?: return
        val target = applyHttpsOnlyPolicy(resolved) ?: return
        val tab = currentTab() ?: return
        if (target != HOME_URL && !selectEngineForNavigation(tab, target)) return
        prepareDesktopModeForNavigation(tab, target)
        tab.lastUrl = target
        if (!tab.isPrivate && !tab.isGuest) persistTabs()
        showBrowserPage()
        if (!addressBar.hasFocus()) addressBar.setText(target)
        updateTabChrome(tab)
        resetPageStatus(when { tab.isGuest -> getString(R.string.guest_history_disabled); tab.isPrivate -> getString(R.string.private_history_disabled); else -> getString(R.string.loading_page) }, if (tab.isGuest || tab.isPrivate) 0 else 3)
        try {
            loadTabUrl(tab, target)
        } catch (error: Exception) {
            setPageStatus(getString(R.string.navigation_error), 5)
            toast("Could not open page: ${error?.localizedMessage ?: "Unknown error"}")
        }
    }

    private fun goBack() {
        val tab = currentTab() ?: return
        if (homeScroll.visibility == View.VISIBLE) { toast("Already on the home screen"); return }
        if (tab.canGoBack) tab.browserEngine?.goBack() else if (tab.lastUrl == HOME_URL) toast("Already on the home screen") else toast("No previous page")
    }

    private fun goForward() {
        val tab = currentTab() ?: return
        if (homeScroll.visibility == View.VISIBLE) { toast("Open a page before going forward"); return }
        if (tab.canGoForward) tab.browserEngine?.goForward() else toast("No next page")
    }

    private fun reloadPage() {
        currentTab()?.let { tab ->
            if (!canUseTabEngine(tab, "Reload")) return@let
            val policyUrl = applyHttpsOnlyPolicy(tab.lastUrl) ?: return@let
            if (policyUrl != tab.lastUrl) {
                loadTabUrl(tab, policyUrl)
                return@let
            }
            if (tab.engineType == BrowserEngineType.GECKOVIEW && pendingGeckoDataClearActive) toast("Wait for browsing-data deletion to finish before reloading")
            else ensureBrowserEngine(tab).reload()
        }
    }

    private fun toggleStopReload() {
        val tab = currentTab() ?: return
        if (tab.isLoading) tab.browserEngine?.stop() else reloadPage()
    }

    private fun trimTabThumbnails() {
        val keepIds = tabs.sortedByDescending { it.lastAccess }.take(12).map { it.id }.toSet()
        tabs.filter { it.id !in keepIds }.forEach { tab ->
            tab.thumbnail?.recycle()
            tab.thumbnail = null
        }
    }

    private fun captureTabThumbnail(tab: BrowserTab) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val engineView = tab.browserEngine?.view ?: return
        val isPrimaryView = currentTab()?.id == tab.id && engineView.parent === browserPane
        val isSecondaryView = splitTabId == tab.id && engineView.parent === secondaryPane
        if (!isPrimaryView && !isSecondaryView) return // Never attach the visible tab's pixels to a background tab card.
        val width = engineView.width
        val height = engineView.height
        if (width <= 0 || height <= 0) return
        val location = IntArray(2)
        engineView.getLocationInWindow(location)
        val sourceRect = android.graphics.Rect(location[0], location[1], location[0] + width, location[1] + height)
        val scale = minOf(1f, 360f / width, 640f / height)
        val previewWidth = (width * scale).toInt().coerceAtLeast(1)
        val previewHeight = (height * scale).toInt().coerceAtLeast(1)
        val preview = runCatching { Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return
        PixelCopy.request(window, sourceRect, preview, { result ->
            if (result == PixelCopy.SUCCESS && tabs.any { it.id == tab.id }) {
                // Do not recycle the old bitmap: an open tab-picker ImageView may still draw it.
                tab.thumbnail = preview
                trimTabThumbnails()
            } else preview.recycle()
        }, android.os.Handler(android.os.Looper.getMainLooper()))
    }

    private fun showTabSearch() {
        val input = EditText(this).apply {
            hint = "Search title, URL or group"
            setSingleLine(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val searchDialog = AlertDialog.Builder(this)
            .setTitle("Search tabs")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Search", null)
            .create()
        tabSearchInputDialog = searchDialog
        searchDialog.setOnDismissListener { if (tabSearchInputDialog === searchDialog) tabSearchInputDialog = null }
        searchDialog.setOnShowListener {
            searchDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val query = input.text.toString().trim()
                if (query.isBlank()) { input.error = "Enter a title, URL or group"; return@setOnClickListener }
                val guestOnly = currentTab()?.isGuest == true
                val matches = tabs.filter { tab ->
                    (!guestOnly || tab.isGuest) &&
                        listOf(tab.title, tab.renamedTitle.orEmpty(), tab.lastUrl, tab.group)
                            .any { it.contains(query, ignoreCase = true) }
                }.take(100)
                if (matches.isEmpty()) { toast("No matching tabs"); return@setOnClickListener }
                val ids = matches.map { it.id }
                searchDialog.dismiss()
                val resultsDialog = AlertDialog.Builder(this).setTitle("${matches.size} matching tabs")
                    .setItems(matches.map { "${it.renamedTitle ?: it.title}\n${it.lastUrl}" }.toTypedArray()) { _, which ->
                        val index = tabs.indexOfFirst { it.id == ids[which] }
                        if (index >= 0) switchTab(index)
                    }.setNegativeButton(getString(R.string.close), null).create()
                tabSearchResultsDialog = resultsDialog
                resultsDialog.setOnDismissListener { if (tabSearchResultsDialog === resultsDialog) tabSearchResultsDialog = null }
                resultsDialog.show()
            }
        }
        searchDialog.show()
    }

    private fun showTabs() {
        if (tabs.isEmpty()) return
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        var tabDialog: AlertDialog? = null
        val guestMode = currentTab()?.isGuest == true
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        if (guestMode) {
            actions.addView(actionButton("＋ Guest") { createGuestTab() })
            actions.addView(actionButton("Exit Guest") { confirmExitGuestMode() })
        } else {
            actions.addView(actionButton("＋ Normal") { createTab(false, HOME_URL) })
            actions.addView(actionButton("＋ Private") { createTab(true, HOME_URL) })
            actions.addView(actionButton("＋ Guest") { createGuestTab() })
            actions.addView(actionButton("Save session") { saveNamedSession() })
            actions.addView(actionButton("Workspaces") { showSavedWorkspaces() })
            actions.addView(actionButton("Expand all") { collapsedGroups.clear(); tabDialog?.dismiss(); showTabs() })
            actions.addView(actionButton("Collapse all") { tabs.map { it.group }.distinct().forEach { collapsedGroups.add("NORMAL TABS::$it"); collapsedGroups.add("PRIVATE TABS::$it") }; tabDialog?.dismiss(); showTabs() })
        }
        val actionScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(actions, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        content.addView(actionScroll, LinearLayout.LayoutParams(-1, -2))
        val scroll = ScrollView(this)
        val grid = GridLayout(this).apply { columnCount = 2; useDefaultMargins = true }
        val normal = tabs.indices.filter { !tabs[it].isPrivate && !tabs[it].isGuest }
        val privateIndices = tabs.indices.filter { tabs[it].isPrivate && !tabs[it].isGuest }
        val guestIndices = tabs.indices.filter { tabs[it].isGuest }
        fun addSection(label: String, indices: List<Int>) {
            if (indices.isEmpty()) return
            val heading = TextView(this).apply { text = getString(R.string.tab_group_heading, label, indices.size); textSize = 13f; setTextColor(getColor(R.color.nova_text_secondary)); setPadding(dp(8), dp(14), dp(8), dp(6)) }
            grid.addView(heading, GridLayout.LayoutParams().apply { columnSpec = GridLayout.spec(0, 2); width = -1 })
            val grouped = indices.groupBy { tabs[it].group.ifBlank { "Ungrouped" } }.toSortedMap()
            grouped.forEach { (groupName, groupIndices) ->
                val groupKey = "$label::$groupName"
                val groupHeading = TextView(this).apply {
                    text = (if (groupKey in collapsedGroups) "▸ " else "▾ ") + "$groupName (${groupIndices.size})"
                    textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(getColor(R.color.nova_text_primary)); setPadding(dp(12), dp(12), dp(8), dp(10))
                    setOnClickListener { if (!collapsedGroups.add(groupKey)) collapsedGroups.remove(groupKey); tabDialog?.dismiss(); showTabs() }
                }
                grid.addView(groupHeading, GridLayout.LayoutParams().apply { columnSpec = GridLayout.spec(0, 2); width = -1 })
                if (groupKey in collapsedGroups) return@forEach
                groupIndices.forEach { index ->
                    val tab = tabs[index]
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12))
                        background = GradientDrawable().apply { setColor(getColor(R.color.nova_surface_elevated)); cornerRadius = dp(18).toFloat(); setStroke(dp(1), getColor(R.color.nova_outline)) }
                    }
                    val preview = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        contentDescription = getString(R.string.tab_thumbnail, displayTabTitle(tab))
                        minimumHeight = dp(76)
                        setImageBitmap(tab.thumbnail)
                        if (tab.thumbnail == null) setImageResource(R.drawable.ic_nova_dragon)
                    }
                    val headingText = TextView(this).apply { text = displayTabTitle(tab); textSize = 14f; setTextColor(getColor(R.color.nova_text_primary)); maxLines = 2 }
                    val urlText = TextView(this).apply { text = tab.lastUrl; textSize = 10f; setTextColor(getColor(R.color.nova_text_secondary)); maxLines = 2; setPadding(0, dp(5), 0, dp(5)) }
                    card.addView(preview, LinearLayout.LayoutParams(-1, dp(76)))
                    card.addView(headingText); card.addView(urlText)
                    val tabId = tab.id
                    card.addView(actionButton("Open") {
                        val currentIndex = tabs.indexOfFirst { it.id == tabId }
                        tabDialog?.dismiss()
                        if (currentIndex >= 0) switchTab(currentIndex) else showTabs()
                    })
                    card.addView(actionButton("Group / rename") {
                        val currentIndex = tabs.indexOfFirst { it.id == tabId }
                        tabDialog?.dismiss()
                        if (currentIndex >= 0) editTabGroup(currentIndex) else showTabs()
                    })
                    card.addView(actionButton("Close") {
                        val currentIndex = tabs.indexOfFirst { it.id == tabId }
                        tabDialog?.dismiss()
                        if (currentIndex >= 0) closeTabAt(currentIndex) else showTabs()
                    })
                    grid.addView(card, GridLayout.LayoutParams().apply { width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(dp(5), dp(5), dp(5), dp(5)) })
                }
            }
        }
        if (!guestMode) addSection("NORMAL TABS", normal)
        if (!guestMode) addSection("PRIVATE TABS", privateIndices)
        addSection("GUEST TABS", guestIndices)
        scroll.addView(grid)
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        tabDialog = AlertDialog.Builder(this).setTitle(if (guestMode) "Guest tabs · ${guestIndices.size}" else "Tabs · ${tabs.size}")
            .setView(content).setNegativeButton("Done", null).create()
        tabDialog?.show()
    }

    private fun editTabGroup(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs[index]
        val input = EditText(this).apply { setText(tab.group); hint = "Group or workspace name"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Tab group / workspace")
            .setMessage("Give this tab a group name. Tabs with the same name stay together.")
            .setView(input).setPositiveButton("Save") { _, _ ->
                tab.group = input.text.toString().trim().ifBlank { "Ungrouped" }
                persistTabs(); toast("Tab group updated")
            }.setNeutralButton("Rename tab") { _, _ -> renameTab(index) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun renameTab(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs[index]
        val input = EditText(this).apply { setText(tab.renamedTitle ?: tab.title); hint = getString(R.string.tab_name); setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Rename tab").setView(input)
            .setPositiveButton("Save") { _, _ -> tab.renamedTitle = input.text.toString().trim().ifBlank { null }; persistTabs(); if (currentTab() === tab) updateTabChrome(tab); toast("Tab renamed") }
            .setNegativeButton("Cancel", null).show()
    }

    private fun closeTabAt(index: Int, confirmed: Boolean = false) {
        if (index !in tabs.indices) return
        if (!confirmed) {
            val closingId = tabs[index].id
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.close_tab_title))
                .setMessage(getString(R.string.close_tab_message))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Close") { _, _ ->
                    val currentIndex = tabs.indexOfFirst { it.id == closingId }
                    if (currentIndex >= 0) closeTabAt(currentIndex, confirmed = true)
                }.show()
            return
        }
        val wasActive = index == activeIndex
        val wasGuestActive = currentTab()?.isGuest == true
        val removed = tabs.removeAt(index)
        if (!removed.isPrivate && !removed.isGuest) {
            writeRecentlyClosedTabs(
                RecentlyClosedTabsPolicy.add(
                    existing = readRecentlyClosedTabs(),
                    title = removed.title,
                    rawUrl = removed.lastUrl,
                    isPrivate = false,
                    isGuest = false,
                    closedAt = System.currentTimeMillis()
                )
            )
        }
        aiPageText.remove(removed.id)
        aiPageUrl.remove(removed.id)
        if (removed.id == splitTabId) exitSplitScreen()
        closeTabRuntime(removed)
        removed.thumbnail?.recycle(); removed.thumbnail = null
        if (index < activeIndex) activeIndex--
        else if (wasActive) activeIndex = index.coerceAtMost(tabs.lastIndex)
        cleanupGuestContextIfIdle()
        if (removed.isGuest && wasGuestActive && tabs.none { it.isGuest }) {
            if (tabs.isNotEmpty()) persistTabs() else ensureHomeTabRecord()
            toast("The last Guest tab was closed · Guest Mode is exiting")
            return
        }
        if (tabs.isEmpty()) {
            activeIndex = 0
            // VPN or data-clear gates may reject engine creation. Keep a runtime-free
            // home tab so the browser remains recoverable when the gate is lifted.
            if (!createTab(false, HOME_URL)) {
                ensureHomeTabRecord()
                persistTabs()
            }
            return
        }
        persistTabs()
        if (wasActive) switchTab(activeIndex.coerceIn(tabs.indices)) else currentTab()?.let { updateTabChrome(it) }
    }

    private fun saveNamedSession() {
        if (denyGuestAccess("Saving workspaces")) return
        val input = EditText(this).apply {
            hint = "Session name"
            setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(NovaWorkspaceStoragePolicy.MAX_NAME_LENGTH))
        }
        AlertDialog.Builder(this).setTitle("Save workspace").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().take(NovaWorkspaceStoragePolicy.MAX_NAME_LENGTH).ifBlank { "Saved workspace" }
                val normalTabs = tabs.filter { !it.isPrivate && !it.isGuest }
                if (normalTabs.any { it.lastUrl.length > NovaWorkspaceStoragePolicy.MAX_URL_LENGTH }) {
                    toast("A tab URL is too long to save in a workspace")
                    return@setPositiveButton
                }
                val urls = JSONArray()
                normalTabs.forEach { urls.put(JSONObject()
                    .put("url", it.lastUrl)
                    .put("group", it.group.take(NovaWorkspaceStoragePolicy.MAX_NAME_LENGTH))
                    .put("title", it.title.take(NovaWorkspaceStoragePolicy.MAX_TITLE_LENGTH))
                    .put("renamedTitle", (it.renamedTitle ?: "").take(NovaWorkspaceStoragePolicy.MAX_TITLE_LENGTH))) }
                val snapshots = prefs.getStringSet("saved_workspaces", emptySet())?.toMutableSet() ?: mutableSetOf()
                snapshots.removeAll { raw -> runCatching { JSONObject(raw).optString("name") == name }.getOrDefault(false) }
                val workspaceTombstones = runCatching { JSONObject(prefs.getString("workspace_tombstones", "{}") ?: "{}") }.getOrDefault(JSONObject())
                val createdAt = maxOf(System.currentTimeMillis(), workspaceTombstones.optLong(name) + 1L)
                snapshots.add(JSONObject().put("name", name).put("createdAt", createdAt).put("tabs", urls).toString())
                runCatching {
                    NovaWorkspaceStoragePolicy.serialize(snapshots)
                    NovaWorkspaceStoragePolicy.validateTombstones(workspaceTombstones.toString())
                }.onFailure {
                    toast("Workspace data is too large or invalid")
                    return@setPositiveButton
                }
                prefs.edit().putStringSet("saved_workspaces", snapshots).apply()
                toast("Workspace saved")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun persistTabs() {
        val normalTabs = tabs.filter { !it.isPrivate && !it.isGuest }
        normalTabs.forEach { tab ->
            // A blocked navigation may have set lastUrl to its requested destination.
            // Never replace that pending destination with a snapshot of the old live page.
            val pendingDestination = tab.needsReload && tab.cefriumNavigationState == null
            if (!pendingDestination) {
                (tab.browserEngine as? CefriumBrowserEngine)?.serializeNavigationState()?.let { rawState ->
                    tab.cefriumNavigationState = CefriumTabStateCipher.encrypt(rawState, enginePreferences.cefriumProfileName())
                }
            }
        }
        val saved = JSONArray()
        normalTabs.forEach { tab ->
            val encryptedState = tab.cefriumNavigationState?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) } ?: ""
            saved.put(JSONObject().put("url", tab.lastUrl).put("group", tab.group).put("title", tab.title)
                .put("readerOriginalUrl", tab.readerOriginalUrl ?: "").put("renamedTitle", tab.renamedTitle ?: "")
                .put("engineType", tab.engineType.name)
                .put("explicitEngineChoice", tab.explicitEngineChoice?.name ?: "")
                .put("cefriumNavigationState", encryptedState))
        }
        val active = currentTab()
        val selectedNormalIndex = if (active != null && !active.isPrivate && !active.isGuest) {
            normalTabs.indexOf(active).coerceAtLeast(0)
        } else {
            prefs.getInt("active_normal_tab", 0).coerceIn(0, (normalTabs.size - 1).coerceAtLeast(0))
        }
        prefs.edit().putString("open_normal_tabs", saved.toString())
            .putInt("active_normal_tab", selectedNormalIndex).apply()
    }

    private fun restoreTabs() {
        val raw = prefs.getString("open_normal_tabs", null) ?: return
        try {
            val saved = JSONArray(raw)
            val savedActiveIndex = prefs.getInt("active_normal_tab", 0)
            for (i in 0 until saved.length()) {
                val item = saved.getJSONObject(i)
                val url = item.optString("url", HOME_URL).ifBlank { HOME_URL }
                val tab = BrowserTab(
                    session = null,
                    isPrivate = false,
                    isGuest = false,
                    lastUrl = url,
                    group = item.optString("group", "Ungrouped"),
                    title = item.optString("title", "New tab"),
                    renamedTitle = item.optString("renamedTitle").takeIf { it.isNotBlank() },
                    cefriumNavigationState = item.optString("cefriumNavigationState").takeIf { it.isNotBlank() }
                        ?.let { encoded -> runCatching { android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP) }.getOrNull() },
                    desktopSite = isDesktopUrl(url),
                    readerOriginalUrl = item.optString("readerOriginalUrl")
                        .takeIf { it.startsWith("http://") || it.startsWith("https://") }
                )
                tab.explicitEngineChoice = runCatching { BrowserEngineType.valueOf(item.optString("explicitEngineChoice")) }.getOrNull()
                tab.engineType = runCatching { BrowserEngineType.valueOf(item.optString("engineType")) }.getOrNull()
                    ?: resolveEngineForTab(tab)
                if (tab.engineType != BrowserEngineType.CEFRIUM) tab.cefriumNavigationState = null
                tab.needsReload = url != HOME_URL
                tabs.add(tab)
            }
            if (tabs.isNotEmpty()) {
                activeIndex = savedActiveIndex.coerceIn(tabs.indices)
                // Only the selected tab gets a GeckoSession on startup. Other tabs
                // remain lightweight records until the user opens them.
                switchTab(activeIndex)
            }
        } catch (_: Exception) {
            prefs.edit().remove("open_normal_tabs").apply()
        }
    }

    private fun showSavedWorkspaces() {
        if (denyGuestAccess("Saved workspaces")) return
        val saved = prefs.getStringSet("saved_workspaces", emptySet())?.toList().orEmpty()
        if (saved.isEmpty()) { toast("No saved workspaces yet"); return }
        if (runCatching { NovaWorkspaceStoragePolicy.serialize(saved.toSet()) }.isFailure) {
            toast("Saved workspace data is damaged")
            return
        }
        val labels = saved.map { runCatching { JSONObject(it).optString("name", "Workspace") }.getOrDefault("Workspace") + " · long-press to delete" }
        val list = ListView(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, labels) }
        val dialog = AlertDialog.Builder(this).setTitle("Saved workspaces").setView(list).setNegativeButton("Close", null).create()
        list.setOnItemClickListener { _, _, which, _ ->
            dialog.dismiss()
            restoreWorkspace(saved[which])
        }
        list.setOnItemLongClickListener { _, _, which, _ ->
            val raw = saved[which]
            val name = runCatching { JSONObject(raw).optString("name", "Workspace") }.getOrDefault("Workspace")
            AlertDialog.Builder(this).setTitle("Delete workspace?")
                .setMessage("Delete '$name' from this device and synced devices?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ -> deleteWorkspace(raw, name) }
                .show()
            true
        }
        dialog.show()
    }

    private fun restoreWorkspace(raw: String) {
        if (denyGuestAccess("Restoring workspaces")) return
        runCatching {
            NovaWorkspaceStoragePolicy.serialize(setOf(raw))
            val data = JSONObject(raw).getJSONArray("tabs")
            val firstNewIndex = tabs.size
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val url = item.optString("url", HOME_URL).ifBlank { HOME_URL }
                tabs.add(BrowserTab(
                    session = null,
                    isPrivate = false,
                    isGuest = false,
                    lastUrl = url,
                    group = item.optString("group", "Ungrouped"),
                    title = item.optString("title", "New tab"),
                    renamedTitle = item.optString("renamedTitle").takeIf { it.isNotBlank() },
                    needsReload = url != HOME_URL,
                    readerOriginalUrl = item.optString("readerOriginalUrl").takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ))
            }
            if (tabs.size > firstNewIndex) switchTab(tabs.lastIndex)
            persistTabs(); toast("Workspace restored")
        }.onFailure { toast("Could not restore workspace") }
    }

    private fun deleteWorkspace(raw: String, name: String) {
        val tombstones = runCatching { JSONObject(prefs.getString("workspace_tombstones", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val createdAt = runCatching { JSONObject(raw).optLong("createdAt") }.getOrDefault(0L)
        tombstones.put(name, maxOf(System.currentTimeMillis(), createdAt + 1L))
        val remaining = prefs.getStringSet("saved_workspaces", emptySet())?.toMutableSet() ?: mutableSetOf()
        remaining.removeAll { candidate -> runCatching { JSONObject(candidate).optString("name") == name }.getOrDefault(false) }
        prefs.edit().putStringSet("saved_workspaces", remaining).putString("workspace_tombstones", tombstones.toString()).apply()
        toast("Workspace deleted and queued for cloud sync")
    }

    private fun closeCurrentTab() = closeTabAt(activeIndex)

    private fun readBookmarks(): MutableList<BookmarkEntry> {
        val raw = prefs.getString("bookmarks_v2", null)
        if (raw != null) return runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { i ->
                val item = array.getJSONObject(i)
                BookmarkEntry(item.optString("url"), item.optString("title"), item.optString("folder", "General"), item.optLong("addedAt", 0L))
            }
        }.getOrDefault(mutableListOf())
        return (prefs.getStringSet("bookmarks", emptySet()) ?: emptySet()).map {
            BookmarkEntry(it, Uri.parse(it).host ?: it, "General", System.currentTimeMillis())
        }.toMutableList()
    }

    private fun saveBookmarks(
        items: List<BookmarkEntry>,
        trackDeletes: Boolean = true,
        tombstonesOverride: JSONObject? = null
    ): Boolean {
        val previous = readBookmarks()
        val previousJson = prefs.getString("bookmarks_v2", null)
        val previousLegacy = prefs.getStringSet("bookmarks", emptySet())?.toSet() ?: emptySet()
        val previousTombstones = prefs.getString("bookmark_tombstones", "{}") ?: "{}"
        val tombstones = tombstonesOverride?.let { JSONObject(it.toString()) }
            ?: runCatching { JSONObject(previousTombstones) }.getOrDefault(JSONObject())
        if (trackDeletes && tombstonesOverride == null) {
            val retained = items.map { it.url }.toSet()
            val removed = previous.filterNot { it.url in retained }
            val deletedAt = System.currentTimeMillis()
            removed.forEach { tombstones.put(it.url, maxOf(deletedAt, it.addedAt + 1L)) }
        }
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("url", it.url).put("title", it.title).put("folder", it.folder).put("addedAt", it.addedAt)) }
        val saved = prefs.edit()
            .putString("bookmarks_v2", array.toString())
            .putStringSet("bookmarks", items.map { it.url }.toSet())
            .putString("bookmark_tombstones", tombstones.toString())
            .commit()
        if (saved) return true

        // Android updates memory before commit() reports a disk error; restore the previous logical state.
        val rollback = prefs.edit()
        if (previousJson == null) rollback.remove("bookmarks_v2") else rollback.putString("bookmarks_v2", previousJson)
        val rollbackSaved = rollback.putStringSet("bookmarks", previousLegacy)
            .putString("bookmark_tombstones", previousTombstones).commit()
        if (!rollbackSaved) android.util.Log.e("NovaBrowser", "Bookmark rollback could not be durably committed")
        toast("Could not save bookmarks. Try again.")
        return false
    }

    private fun toggleBookmark() {
        val tab = currentTab() ?: return
        if (tab.isPrivate || tab.isGuest) { toast("Private and Guest tabs cannot save bookmarks"); return }
        if (tab.lastUrl == HOME_URL) { toast("Open a page before bookmarking"); return }
        val items = readBookmarks()
        val existing = items.indexOfFirst { it.url == tab.lastUrl }
        if (existing >= 0) {
            items.removeAt(existing)
            if (!saveBookmarks(items)) return
            refreshBookmarkButton(); toast("Bookmark removed")
        } else {
            val priorDelete = runCatching { JSONObject(prefs.getString("bookmark_tombstones", "{}") ?: "{}").optLong(tab.lastUrl) }.getOrDefault(0L)
            items.add(BookmarkEntry(tab.lastUrl, tab.title, "General", maxOf(System.currentTimeMillis(), priorDelete + 1L)))
            if (!saveBookmarks(items)) return
            refreshBookmarkButton(); toast("Bookmark saved")
        }
    }

    private fun showBookmarks() {
        val items = readBookmarks().toMutableList()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val search = EditText(this).apply { hint = getString(R.string.search_bookmarks); isSingleLine = true }
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf())
        val listView = ListView(this).apply { this.adapter = adapter; minimumHeight = dp(240) }
        content.addView(search)
        content.addView(listView, LinearLayout.LayoutParams(-1, 0, 1f))
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.bookmarks))
            .setView(content)
            .setPositiveButton(getString(R.string.new_folder), null)
            .setNeutralButton(getString(R.string.import_export), null)
            .setNegativeButton(getString(R.string.close), null).create()
        var rowEntries = mutableListOf<BookmarkEntry?>()
        fun render(query: String) {
            val savedFolders = (prefs.getStringSet("bookmark_folders", emptySet()) ?: emptySet()).toList()
            val folders = (savedFolders + items.map { it.folder } + "General").distinct().sortedBy { it.lowercase(Locale.ROOT) }
            val labels = mutableListOf<String>()
            rowEntries = mutableListOf()
            folders.forEach { folder ->
                val entries = items.filter { it.folder.equals(folder, true) && (query.isBlank() || it.title.contains(query, true) || it.url.contains(query, true) || folder.contains(query, true)) }
                    .sortedBy { it.title.lowercase(Locale.ROOT) }
                if (query.isNotBlank() && entries.isEmpty() && !folder.contains(query, true)) return@forEach
                labels.add("$folder · ${entries.size}")
                rowEntries.add(null)
                if (entries.isEmpty()) {
                    if (query.isBlank() || folder.contains(query, true)) { labels.add(getString(R.string.empty_folder)); rowEntries.add(null) }
                } else entries.forEach { entry ->
                    labels.add("${entry.title}\n${entry.url}")
                    rowEntries.add(entry)
                }
            }
            adapter.clear(); adapter.addAll(labels); adapter.notifyDataSetChanged()
            dialog.setTitle(getString(R.string.bookmarks_count, items.size, folders.size))
        }
        fun refresh() { items.clear(); items.addAll(readBookmarks()); render(search.text?.toString().orEmpty()) }
        listView.setOnItemClickListener { _, _, position, _ ->
            rowEntries.getOrNull(position)?.let { entry -> dialog.dismiss(); navigate(entry.url) }
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            rowEntries.getOrNull(position)?.let { entry -> showBookmarkActions(entry) { refresh() } }
            true
        }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var pending: Runnable? = null
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                pending?.let(handler::removeCallbacks)
                val query = s?.toString().orEmpty()
                pending = Runnable { render(query) }.also { handler.postDelayed(it, 250) }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        render("")
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { createBookmarkFolder { refresh() } }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { showBookmarkTransfer() }
        }
        dialog.setOnDismissListener { pending?.let(handler::removeCallbacks) }
        dialog.show()
    }

    private fun restoreBookmarkAfterUndo(deleted: BookmarkEntry) {
        val current = readBookmarks()
        // A re-added bookmark wins; never overwrite its newer title, folder, or timestamp.
        if (current.any { it.url == deleted.url }) return
        val tombstones = runCatching {
            JSONObject(prefs.getString("bookmark_tombstones", "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        val deletedAt = tombstones.optLong(deleted.url)
        tombstones.remove(deleted.url)
        val restored = deleted.copy(addedAt = maxOf(System.currentTimeMillis(), deletedAt + 1L))
        if (!saveBookmarks(current + restored, trackDeletes = false, tombstonesOverride = tombstones)) {
            showUndoSnackbar("Bookmark restore failed", "Retry") { restoreBookmarkAfterUndo(deleted) }
        }
    }

    private fun showBookmarkActions(entry: BookmarkEntry, onChanged: () -> Unit = {}) =
        showBottomSheetMenu(entry.title, listOf(
            getString(R.string.edit_bookmark) to { editBookmark(entry, onChanged) },
            getString(R.string.delete_bookmark) to {
                AlertDialog.Builder(this).setTitle(getString(R.string.delete_bookmark)).setMessage(getString(R.string.delete_bookmark_confirm))
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton(getString(R.string.delete)) { _, _ ->
                        val previous = readBookmarks()
                        val deletedEntry = previous.firstOrNull { it.url == entry.url } ?: run {
                            toast("This bookmark no longer exists")
                            return@setPositiveButton
                        }
                        if (!saveBookmarks(previous.filterNot { it.url == entry.url })) return@setPositiveButton
                        onChanged(); refreshBookmarkButton()
                        showUndoSnackbar(getString(R.string.bookmark_deleted), getString(R.string.undo)) {
                            restoreBookmarkAfterUndo(deletedEntry)
                            onChanged()
                            refreshBookmarkButton()
                        }
                    }.show()
            },
            getString(R.string.open) to { navigate(entry.url) }
        ))

    private fun editBookmark(entry: BookmarkEntry, onChanged: () -> Unit = {}) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val title = EditText(this).apply { setText(entry.title); hint = getString(R.string.title) }
        val folders = ((prefs.getStringSet("bookmark_folders", emptySet()) ?: emptySet()) + entry.folder + "General").distinct().sorted()
        val folderPicker = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, folders)
            setSelection(folders.indexOf(entry.folder).coerceAtLeast(0))
            contentDescription = getString(R.string.bookmark_folder)
        }
        form.addView(title); form.addView(folderPicker)
        AlertDialog.Builder(this).setTitle(getString(R.string.edit_bookmark)).setView(form)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val selectedFolder = folderPicker.selectedItem?.toString()?.ifBlank { "General" } ?: "General"
                val items = readBookmarks().map { if (it.url == entry.url) it.copy(title = title.text.toString().trim().ifBlank { entry.title }, folder = selectedFolder) else it }
                if (!saveBookmarks(items)) return@setPositiveButton
                onChanged(); toast(getString(R.string.bookmark_updated))
            }.setNegativeButton("Cancel", null).show()
    }

    private fun createBookmarkFolder(onCreated: () -> Unit = {}) {
        val input = EditText(this).apply { hint = getString(R.string.folder_name); isSingleLine = true }
        AlertDialog.Builder(this).setTitle(getString(R.string.create_bookmark_folder)).setView(input)
            .setPositiveButton(getString(R.string.create)) { _, _ ->
                val folder = input.text.toString().trim()
                if (folder.isNotBlank()) {
                    prefs.edit().putStringSet("bookmark_folders", (prefs.getStringSet("bookmark_folders", emptySet()) ?: emptySet()) + folder).apply()
                    onCreated(); toast(getString(R.string.folder_created))
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showBookmarkTransfer() {
        AlertDialog.Builder(this).setTitle("Bookmark backup")
            .setItems(arrayOf("Export bookmarks JSON", "Import bookmarks JSON")) { _, which ->
                if (which == 0) exportBookmarks() else importBookmarks()
            }.show()
    }

    private fun exportBookmarks() {
        if (denyGuestAccess("Bookmark export")) return
        bookmarkTransferMode = "export"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"; putExtra(Intent.EXTRA_TITLE, "nova-bookmarks.json")
        }
        startActivityForResult(intent, bookmarkRequestCode)
    }

    private fun importBookmarks() {
        if (denyGuestAccess("Bookmark import")) return
        bookmarkTransferMode = "import"
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/json" }
        startActivityForResult(intent, bookmarkRequestCode)
    }

    private fun clearBrowsingHistory(onCleared: () -> Unit = {}) {
        if (denyGuestAccess("Clearing owner history")) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.clear_history_title))
            .setMessage(getString(R.string.clear_history_message))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                prefs.edit().putLong("history_cleared_at", System.currentTimeMillis())
                    .remove("history").remove("history_v2").remove("nova_recently_closed_tabs").apply()
                toast(getString(R.string.history_cleared))
                onCleared()
            }.show()
    }

    private fun addHistory(url: String) {
        val safeUrl = HistoryPolicy.sanitizeForStorage(url) ?: return
        if (safeUrl == HOME_URL) return
        val items = readHistory().sortedBy { it.visitedAt }.toMutableList()
        val visitedAt = maxOf(System.currentTimeMillis(), prefs.getLong("history_cleared_at", 0L) + 1L)
        val last = items.lastOrNull()
        if (!HistoryPolicy.shouldAppendVisit(last?.url, last?.visitedAt ?: 0L, safeUrl, visitedAt)) return
        items.add(HistoryEntry(safeUrl, Uri.parse(safeUrl).host ?: safeUrl, visitedAt))
        val array = JSONArray()
        items.takeLast(500).forEach { array.put(JSONObject().put("url", it.url).put("title", it.title).put("visitedAt", it.visitedAt)) }
        prefs.edit().putString("history_v2", array.toString()).apply()
    }

    private fun readHistory(): List<HistoryEntry> {
        val raw = prefs.getString("history_v2", null)
        val entries = if (raw != null) runCatching {
            val array = JSONArray(raw)
            List(array.length()) { i -> val item = array.getJSONObject(i); HistoryEntry(item.optString("url"), item.optString("title"), item.optLong("visitedAt")) }
        }.getOrDefault(emptyList()) else {
            (prefs.getStringSet("history", emptySet()) ?: emptySet()).map { HistoryEntry(it, Uri.parse(it).host ?: it, 0L) }
        }
        return entries.mapNotNull { entry ->
            HistoryPolicy.sanitizeForStorage(entry.url)?.let { safeUrl ->
                if (safeUrl == entry.url) entry else entry.copy(url = safeUrl)
            }
        }
    }

    private fun readRecentlyClosedTabs(): List<RecentlyClosedTab> =
        RecentlyClosedTabsPolicy.decode(prefs.getString("nova_recently_closed_tabs", "[]"))

    private fun writeRecentlyClosedTabs(entries: List<RecentlyClosedTab>) {
        prefs.edit().putString("nova_recently_closed_tabs", RecentlyClosedTabsPolicy.encode(entries)).apply()
    }

    private fun showRecentlyClosedTabs() {
        val entries = readRecentlyClosedTabs()
        if (entries.isEmpty()) {
            toast("No recently closed tabs")
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Recently closed · ${entries.size}")
            .setItems(entries.map { "${it.title}\n${it.url}" }.toTypedArray()) { _, position ->
                val entry = entries.getOrNull(position) ?: return@setItems
                if (createTab(false, entry.url)) {
                    // Re-read the current list: another close/clear action may have changed it
                    // since this dialog was opened.
                    writeRecentlyClosedTabs(RecentlyClosedTabsPolicy.remove(readRecentlyClosedTabs(), entry.id))
                    toast("Tab restored")
                } else {
                    toast("Tab could not be restored while protected routing or Guest Mode is active")
                }
            }
            .setNeutralButton("Clear list", null)
            .setNegativeButton(getString(R.string.close), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                writeRecentlyClosedTabs(emptyList())
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showHistory() {
        if (currentTab()?.let { it.isPrivate || it.isGuest } == true) {
            toast(getString(R.string.history_unavailable_private))
            return
        }
        val items = readHistory().sortedByDescending { it.visitedAt }.toMutableList()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val search = EditText(this).apply {
            hint = getString(R.string.search_history)
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val clearSearch = Button(this).apply {
            text = "Clear"
            contentDescription = "Clear history search"
            isAllCaps = false
            visibility = View.GONE
        }
        searchRow.addView(search, LinearLayout.LayoutParams(0, -2, 1f))
        searchRow.addView(clearSearch, LinearLayout.LayoutParams(-2, -2))
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf())
        val listView = ListView(this).apply {
            this.adapter = adapter
            minimumHeight = dp(240)
        }
        val emptyView = TextView(this).apply {
            text = "No matching history"
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(24), dp(16), dp(24))
            visibility = View.GONE
        }
        val listContainer = FrameLayout(this).apply {
            addView(listView, FrameLayout.LayoutParams(-1, -1))
            addView(emptyView, FrameLayout.LayoutParams(-1, -1))
        }
        val recentlyClosedButton = Button(this).apply {
            text = "Recently closed tabs"
            isAllCaps = false
        }
        content.addView(recentlyClosedButton)
        content.addView(searchRow)
        content.addView(listContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.history_count, items.size))
            .setView(content)
            .setNeutralButton(getString(R.string.clear_history), null)
            .setNegativeButton(getString(R.string.close), null)
            .create()
        recentlyClosedButton.setOnClickListener {
            dialog.dismiss()
            showRecentlyClosedTabs()
        }
        var filteredItems: List<HistoryEntry> = items
        val dateFormat = SimpleDateFormat("EEEE, dd MMM yyyy", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        fun render(rawQuery: String) {
            val query = rawQuery.trim()
            filteredItems = if (query.isBlank()) items else items.filter { entry ->
                entry.url.contains(query, ignoreCase = true) ||
                    entry.title.contains(query, ignoreCase = true) ||
                    (Uri.parse(entry.url).host ?: "").contains(query, ignoreCase = true)
            }
            adapter.clear()
            adapter.addAll(filteredItems.map { entry ->
                val day = if (entry.visitedAt > 0) dateFormat.format(Date(entry.visitedAt)) else getString(R.string.earlier_history)
                val time = if (entry.visitedAt > 0) timeFormat.format(Date(entry.visitedAt)) else ""
                "$day · $time · ${entry.title}\n${entry.url}"
            })
            adapter.notifyDataSetChanged()
            emptyView.visibility = if (filteredItems.isEmpty()) View.VISIBLE else View.GONE
            listView.visibility = if (filteredItems.isEmpty()) View.GONE else View.VISIBLE
            clearSearch.visibility = if (query.isBlank()) View.GONE else View.VISIBLE
            dialog.setTitle("History · ${filteredItems.size} / ${items.size}")
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            filteredItems.getOrNull(position)?.let { dialog.dismiss(); navigate(it.url) }
        }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var pending: Runnable? = null
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                pending?.let(handler::removeCallbacks)
                val query = s?.toString().orEmpty()
                pending = Runnable { render(query) }.also { handler.postDelayed(it, 120) }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        clearSearch.setOnClickListener { search.text.clear(); search.requestFocus() }
        render("")
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                clearBrowsingHistory {
                    pending?.let(handler::removeCallbacks)
                    pending = null
                    items.clear()
                    filteredItems = emptyList()
                    search.text.clear()
                    adapter.clear()
                    adapter.notifyDataSetChanged()
                    emptyView.visibility = View.VISIBLE
                    listView.visibility = View.GONE
                    dialog.setTitle("History · 0 / 0")
                }
            }
        }
        dialog.setOnDismissListener { pending?.let(handler::removeCallbacks) }
        dialog.show()
    }

    private fun requestDownloadNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !notificationPermissionRequestPending) {
            notificationPermissionRequestPending = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startDownload(input: String) {
        if (currentTab()?.let { it.isPrivate || it.isGuest } == true) {
            toast("Downloads are disabled in private and Guest Mode because files remain on the device")
            return
        }
        val url = BrowserUrlResolver.resolve(input, searchEngine) ?: return
        if (!url.startsWith("https://") && !url.startsWith("http://")) { toast("Only HTTP and HTTPS downloads are supported"); return }
        requestDownloadNotificationPermission()
        runCatching { downloadsFor(currentTab()).enqueue(url); toast("Download started · resumable") }
            .onFailure { toast("Download could not start: ${it?.localizedMessage ?: "Unknown error"}") }
    }

    private fun updateDownloadSummary(items: List<NovaDownloadManager.Item>) {
        runOnUiThread {
            refreshDownloadsList(items)
            if (!::pageStatus.isInitialized) return@runOnUiThread
            val active = items.count { it.state == "RUNNING" || it.state == "QUEUED" || it.state == "FINALIZING" }
            if (active > 0 && currentTab()?.isPrivate != true && currentTab()?.isGuest != true) setPageStatus(getString(R.string.downloads_active, active), 1)
            else if (statusState.priority <= 1 && statusState.text.startsWith(getString(R.string.downloads_label))) setPageStatus(getString(R.string.ready), 1)
        }
    }

    private fun refreshDownloadsList(items: List<NovaDownloadManager.Item>) {
        downloadsItems = items
        downloadsAdapter?.notifyDataSetChanged()
        downloadsDialog?.setTitle(getString(R.string.downloads_count, items.size))
    }

    private fun showDownloads() {
        val tab = currentTab()
        if (tab == null || tab.isPrivate || tab.isGuest) {
            toast("Downloads are unavailable in Private and Guest Mode")
            return
        }
        val manager = downloadsFor(tab)
        downloadsItems = manager.items()
        val listView = ListView(this)
        val emptyView = TextView(this).apply { text = getString(R.string.no_downloads); gravity = Gravity.CENTER; setPadding(dp(16), dp(24), dp(16), dp(24)) }
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount(): Int = downloadsItems.size
            override fun getItem(position: Int): NovaDownloadManager.Item = downloadsItems[position]
            override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    addView(TextView(this@MainActivity).apply { textSize = 14f; setTextColor(getColor(R.color.nova_text_primary)) })
                    addView(TextView(this@MainActivity).apply { textSize = 12f; setTextColor(getColor(R.color.nova_text_secondary)) })
                    addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; minimumHeight = dp(4) })
                }
                val item = getItem(position)
                (row.getChildAt(0) as TextView).text = item.fileName
                val stateLabel = when (item.state) {
                    "RUNNING" -> getString(R.string.downloading_status, item.progressLabel())
                    "QUEUED" -> getString(R.string.queued_status)
                    "PAUSED" -> getString(R.string.paused_status, item.progressLabel())
                    "FINALIZING", "SAVING" -> getString(R.string.saving_status, item.progressLabel())
                    "INTERRUPTED" -> getString(R.string.interrupted_status, item.error)
                    "COMPLETE" -> getString(R.string.complete_status, item.progressLabel())
                    "FAILED" -> getString(R.string.failed_status, item.error)
                    else -> item.state
                }
                (row.getChildAt(1) as TextView).text = stateLabel
                val progress = row.getChildAt(2) as ProgressBar
                progress.visibility = if (item.total > 0 && item.state in setOf("RUNNING", "QUEUED", "PAUSED", "INTERRUPTED", "FINALIZING", "SAVING")) View.VISIBLE else View.GONE
                progress.progress = if (item.total > 0) ((item.downloaded * 100 / item.total).coerceIn(0, 100)).toInt() else 0
                row.minimumHeight = dp(64)
                return row
            }
        }
        downloadsAdapter = adapter
        listView.adapter = adapter
        listView.emptyView = emptyView
        val downloadActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(Button(this@MainActivity).apply {
                text = "Browse Nova folder"
                setOnClickListener { openNovaDownloadsFolder(manager, tab) }
            })
            addView(Button(this@MainActivity).apply {
                text = "Clear completed"
                setOnClickListener { clearCompletedDownloads(manager) }
            })
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(downloadActions, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(emptyView, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(listView, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        listView.setOnItemClickListener { _, _, which, _ -> downloadsItems.getOrNull(which)?.let { showDownloadActions(it, manager) } }
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.downloads_count, downloadsItems.size)).setView(container)
            .setPositiveButton(getString(R.string.new_download), null)
            .setNeutralButton(getString(R.string.refresh), null)
            .setNegativeButton(getString(R.string.close), null).create()
        downloadsDialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { dialog.dismiss(); downloadPrompt() }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { refreshDownloadsList(manager.items()) }
        }
        dialog.setOnDismissListener { if (downloadsDialog === dialog) { downloadsDialog = null; downloadsAdapter = null } }
        dialog.show()
    }

    private fun showDownloadActions(item: NovaDownloadManager.Item, manager: NovaDownloadManager) {
        val actions: List<Pair<String, () -> Unit>> = when (item.state) {
            "RUNNING" -> listOf(getString(R.string.pause) to { manager.pause(item.id) }, getString(R.string.cancel_download) to { confirmRemoveDownload(item, manager, cancel = true) })
            "PAUSED" -> listOf(getString(R.string.resume) to { manager.resume(item.id) }, getString(R.string.cancel_download) to { confirmRemoveDownload(item, manager, cancel = true) })
            "QUEUED" -> listOf(getString(R.string.cancel_download) to { confirmRemoveDownload(item, manager, cancel = true) })
            "FINALIZING", "SAVING" -> listOf(getString(R.string.close) to { })
            "COMPLETE" -> listOf(
                getString(R.string.open_download) to { openDownloadedFile(item) },
                "Share file" to { shareDownloadedFile(item) },
                getString(R.string.remove_from_list) to { confirmRemoveDownload(item, manager) },
                "Delete file" to { deleteDownloadedFile(item, manager) }
            )
            "INTERRUPTED" -> if (item.url.isNotBlank()) listOf(getString(R.string.resume) to { manager.resume(item.id) }, getString(R.string.remove_from_list) to { confirmRemoveDownload(item, manager) }) else listOf(getString(R.string.remove_from_list) to { confirmRemoveDownload(item, manager) })
            else -> if (item.url.isNotBlank()) listOf(getString(R.string.retry_download) to { manager.resume(item.id) }, getString(R.string.remove_from_list) to { confirmRemoveDownload(item, manager) }) else listOf(getString(R.string.remove_from_list) to { confirmRemoveDownload(item, manager) })
        }
        showBottomSheetMenu(item.fileName, actions)
    }

    private fun confirmRemoveDownload(item: NovaDownloadManager.Item, manager: NovaDownloadManager, cancel: Boolean = false) {
        AlertDialog.Builder(this)
            .setTitle(getString(if (cancel) R.string.cancel_download else R.string.remove_download_title))
            .setMessage(getString(if (cancel) R.string.cancel_download_message else R.string.remove_download_message))
            .setNegativeButton(getString(R.string.keep), null)
            .setPositiveButton(getString(if (cancel) R.string.cancel_download else R.string.remove_from_list)) { _, _ ->
                manager.cancel(item.id)
                toast(getString(if (cancel) R.string.download_cancelled else R.string.download_removed_from_list))
            }.show()
    }

    private fun openDownloadedFile(item: NovaDownloadManager.Item) {
        if (item.savedUri.isBlank()) { toast("Downloaded file location is unavailable"); return }
        runCatching {
            val uri = Uri.parse(item.savedUri)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        }.onFailure { toast("No app can open this file: ${it?.localizedMessage ?: "Unknown error"}") }
    }

    private fun downloadPrompt() {
        if (currentTab()?.let { it.isPrivate || it.isGuest } == true) { toast("Downloads are disabled in private and Guest Mode"); return }
        val input = EditText(this).apply { hint = "https://example.com/file.zip"; setText(currentTab()?.lastUrl?.takeIf { it.startsWith("http") } ?: "") }
        AlertDialog.Builder(this).setTitle("New download").setView(input)
            .setPositiveButton("Download") { _, _ -> startDownload(input.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun toggleDesktopSite() {
        val tab = currentTab() ?: return
        if (!canUseTabEngine(tab, "Desktop site")) return
        if (tab.engineType == BrowserEngineType.GECKOVIEW && pendingGeckoDataClearActive) { toast("Wait for browsing-data deletion to finish before changing this tab"); return }
        if (tab.isPrivate || tab.isGuest) { toast("Desktop preference is not saved in private or Guest Mode"); return }
        val host = Uri.parse(tab.lastUrl).host ?: run { toast("Open a website first"); return }
        val originalUrl = tab.lastUrl
        val policyUrl = applyHttpsOnlyPolicy(originalUrl) ?: return
        val urlWasUpgraded = policyUrl != originalUrl
        tab.lastUrl = policyUrl
        if (urlWasUpgraded) persistTabs()
        val sites = desktopSites
        tab.desktopSite = if (host in sites) { sites.remove(host); false } else { sites.add(host); true }
        prefs.edit().putStringSet("desktop_sites", sites).apply()
        val activeEngine = ensureBrowserEngine(tab)
        if (activeEngine is CefriumBrowserEngine) {
            runCatching {
                activeEngine.setDesktopMode(tab.desktopSite)
                if (urlWasUpgraded) activeEngine.loadUrl(policyUrl) else activeEngine.reload()
            }.onFailure { failure ->
                    val fallback = fallbackTabToGecko(tab, failure)
                    fallback.attachTo(browserView)
                    showPrimaryEngineView(fallback.view)
                    fallback.setActive(true, true)
                }
        } else {
            closeTabRuntime(tab)
            tab.needsReload = false
            ensureSession(tab)
            val engine = ensureBrowserEngine(tab)
            engine.attachTo(browserView)
            showPrimaryEngineView(engine.view)
            engine.setActive(true, true)
            engine.loadUrl(policyUrl)
            tab.needsReload = false
        }
        toast(if (tab.desktopSite) "Desktop site enabled for $host" else "Mobile site restored for $host")
    }

    private fun openReaderMode() {
        val tab = currentTab() ?: return
        if (!canUseTabEngine(tab, "Reader Mode")) return
        if (tab.engineType == BrowserEngineType.GECKOVIEW && pendingGeckoDataClearActive) { toast("Wait for browsing-data deletion to finish before opening Reader Mode"); return }
        val url = tab.lastUrl
        if (!url.startsWith("http")) { toast("Open an article first"); return }
        tab.readerOriginalUrl = url
        val readerEngine = ensureBrowserEngine(tab)
        if (readerEngine is CefriumBrowserEngine) {
            readerEngine.loadReaderView(url) { success, title -> runOnUiThread {
                if (success) {
                    tab.title = title
                    tab.lastUrl = url
                    if (!addressBar.hasFocus()) addressBar.setText(url)
                    updateTabChrome(tab)
                    setPageStatus(getString(R.string.reader_view_active), 2)
                    showBrowserPage()
                } else {
                    tab.readerOriginalUrl = null
                    toast("Reader view could not extract this article")
                }
            } }
        } else {
            val readerUrl = "about:reader?url=${Uri.encode(url)}"
            tab.lastUrl = readerUrl
            if (!addressBar.hasFocus()) addressBar.setText(readerUrl)
            updateTabChrome(tab)
            setPageStatus(getString(R.string.reader_view_active), 2)
            showBrowserPage()
            ensureSession(tab).loadUri(readerUrl)
        }
        if (!prefs.getBoolean("reader_mode_explained", false)) {
            prefs.edit().putBoolean("reader_mode_explained", true).apply()
            AlertDialog.Builder(this).setTitle(getString(R.string.reader_mode))
                .setMessage(getString(R.string.reader_mode_explanation))
                .setPositiveButton(android.R.string.ok, null).show()
        }
    }

    private fun exitReaderMode() {
        val tab = currentTab() ?: return
        val original = tab.readerOriginalUrl ?: return
        tab.readerOriginalUrl = null
        tab.lastUrl = original
        navigate(original)
    }

    private fun translatePage() {
        val tab = currentTab() ?: return
        if (!tab.lastUrl.startsWith("http")) { toast("Open a webpage first"); return }
        val route = capabilityRouteForTab(tab, BrowserEngineCapability.TRANSLATION)
        if (!route.available) { toast(route.reason); return }
        if (route.requiresSwitch) {
            AlertDialog.Builder(this).setTitle("Switch to GeckoView for translation?")
                .setMessage("Nova on-device translation requires GeckoView. Switching reloads this page in GeckoView's separate profile. Cookies, site storage and login sessions are not transferred, and Cefrium's per-site cookie rules will not apply after the switch. GeckoView's own tracking and cookie protections will apply. Continue?")
                .setPositiveButton("Switch and continue") { _, _ ->
                    if (currentTab()?.id != tab.id) {
                        toast("The active tab changed; no engine was switched")
                        return@setPositiveButton
                    }
                    val previousEngineChoice = tab.explicitEngineChoice
                    val wasCefrium = tab.engineType == BrowserEngineType.CEFRIUM
                    tab.explicitEngineChoice = BrowserEngineType.GECKOVIEW
                    pendingTranslationTabId = tab.id
                    switchCurrentTabToGecko(route.reason)
                    if (!wasCefrium || tab.engineType != BrowserEngineType.GECKOVIEW) {
                        tab.explicitEngineChoice = previousEngineChoice
                        pendingTranslationTabId = null
                        toast("Engine switch is blocked or no longer needed; no translation switch was saved")
                    } else {
                        persistTabs()
                        toast("Switching to GeckoView; translation options will open after the page reloads")
                    }
                }.setNegativeButton("Cancel", null).show()
            return
        }
        showTranslationLanguagePicker(tab)
    }

    private fun showTranslationLanguagePicker(tab: BrowserTab) {
        if (currentTab() !== tab || tab.engineType != BrowserEngineType.GECKOVIEW) return
        val languages = arrayOf(
            "English" to "en", "Hindi" to "hi", "Bengali" to "bn",
            "Japanese" to "ja", "Chinese" to "zh", "Spanish" to "es", "French" to "fr",
            "German" to "de", "Arabic" to "ar", "Portuguese" to "pt", "Russian" to "ru"
        )
        var source = detectedPageLanguages[tab.id] ?: "en"
        AlertDialog.Builder(this).setTitle("Translate from · detected ${source}")
            .setItems((listOf("Use detected ($source)") + languages.map { it.first }).toTypedArray()) { _, fromIndex ->
                if (fromIndex > 0) source = languages[fromIndex - 1].second
                AlertDialog.Builder(this).setTitle("Translate to")
                    .setItems(languages.map { it.first }.toTypedArray()) { _, toIndex ->
                        val target = languages[toIndex].second
                        if (source == target) { toast("Choose two different languages"); return@setItems }
                        val translation = tab.session?.sessionTranslation
                        if (translation == null) { toast("GeckoView translation is unavailable for this session"); return@setItems }
                        toast("Preparing translation models…")
                        translation.translate(source, target,
                            TranslationsController.SessionTranslation.TranslationOptions.Builder().downloadModel(true).build()
                        ).accept(
                            { runOnUiThread { toast("Translation started · $source → $target") } },
                            { error -> runOnUiThread { toast("Translation unavailable: ${error?.localizedMessage ?: "language model not supported"}") } }
                        )
                    }.setNegativeButton("Cancel", null).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun restoreAfterGuestTorStop() {
        if (isFinishing || isDestroyed) return
        if (tabs.isEmpty()) {
            if (!createTab(false, HOME_URL)) {
                ensureHomeTabRecord()
                persistTabs()
            }
            retryPendingExternalLink()
            return
        }
        applyVpnRoutingReadiness()
        if ((!NovaBrowserVpnController.isVpnEnabled() || NovaBrowserVpnController.isVpnRoutingReady()) &&
            (currentTab()?.browserEngine == null || currentTab()?.needsReload == true)) {
            switchTab(activeIndex.coerceIn(tabs.indices))
        }
        retryPendingExternalLink()
    }

    private fun cleanupGuestContextIfIdle() {
        val guestTabCount = tabs.count { it.isGuest }
        if (GuestTorRoutingPolicy.shouldStopTorAfterGuestCreationFailure(guestTabCount) &&
            !guestTorStartPending && TorGuestRoutingController.blocksNormalGeckoSessions()) {
            TorGuestRoutingController.stop { runOnUiThread { restoreAfterGuestTorStop() } }
        }
        if (GuestTorRoutingPolicy.shouldClearGuestContextMarker(guestTabCount) && guestContextId.isNotBlank()) {
            if (prefs.getBoolean("guest_context_used", false)) queueGuestContextCleanup(guestContextId)
            guestContextId = "nova-guest-${UUID.randomUUID()}"
            prefs.edit().putString("guest_context_id", guestContextId).putBoolean("guest_context_used", false).commit()
        }
    }

    private fun prepareDesktopModeForNavigation(tab: BrowserTab, target: String) {
        // Resolve the destination preference before the routing guard. If 1VPN is still
        // connecting, the tab record must remember the destination's mode so ensureSession()
        // restores the correct desktop/mobile GeckoSession after routing becomes ready.
        val desired = DesktopSiteNavigationPolicy.resolve(
            isPrivate = tab.isPrivate,
            isGuest = tab.isGuest,
            currentTabPreference = tab.desktopSite,
            destinationHostSavedForDesktop = isDesktopUrl(target)
        )
        if (desired == tab.desktopSite) return
        tab.desktopSite = desired
        if (!canUseTabEngine(tab, "Desktop site")) return
        if (tab.engineType == BrowserEngineType.GECKOVIEW && pendingGeckoDataClearActive) return
        if (tab.engineType == BrowserEngineType.CEFRIUM) {
            runCatching { (tab.browserEngine as? CefriumBrowserEngine)?.setDesktopMode(desired) }
                .onFailure { failure ->
                    val fallback = fallbackTabToGecko(tab, failure)
                    if (currentTab() === tab) {
                        fallback.attachTo(browserView)
                        showPrimaryEngineView(fallback.view)
                        fallback.setActive(true, true)
                    }
                    if (tab.id == splitTabId) {
                        fallback.attachTo(secondaryBrowserView)
                        showSecondaryEngineView(fallback.view)
                        fallback.setActive(true, false)
                    }
                }
        } else {
            closeTabRuntime(tab)
            tab.needsReload = false
            ensureSession(tab)
            val engine = ensureBrowserEngine(tab)
            if (currentTab() === tab) {
                engine.attachTo(browserView)
                showPrimaryEngineView(engine.view)
                engine.setActive(true, true)
            }
            if (tab.id == splitTabId) {
                engine.attachTo(secondaryBrowserView)
                showSecondaryEngineView(engine.view)
                engine.setActive(true, false)
            }
        }
    }

    private fun confirmExitGuestMode() {
        if (currentTab()?.isGuest != true) {
            closeGuestTabs()
            return
        }
        AlertDialog.Builder(this).setTitle("Exit Guest Mode?")
            .setMessage("Exiting Guest Mode returns to the owner's normal tabs and saved data. Only the device owner should continue.")
            .setPositiveButton("Exit Guest") { _, _ -> closeGuestTabs() }
            .setNegativeButton("Stay in Guest Mode", null).show()
    }

    private fun closeGuestTabs() {
        val guestIndices = tabs.indices.filter { tabs[it].isGuest }.reversed()
        if (guestIndices.isEmpty()) { toast("Guest Mode is not active"); return }
        val activeIdBeforeClose = currentTab()?.id
        guestIndices.forEach { index ->
            val removed = tabs.removeAt(index)
            aiPageText.remove(removed.id)
            aiPageUrl.remove(removed.id)
            removed.thumbnail?.recycle()
            removed.thumbnail = null
            if (removed.id == splitTabId) exitSplitScreen()
            closeTabRuntime(removed)
        }
        activeIndex = tabs.indexOfFirst { it.id == activeIdBeforeClose }.takeIf { it >= 0 } ?: (activeIndex.coerceAtMost(tabs.lastIndex).coerceAtLeast(0))
        cleanupGuestContextIfIdle()
        if (tabs.isEmpty()) createTab(false, HOME_URL) else switchTab(activeIndex.coerceIn(tabs.indices))
        toast("Guest session closed · cookies and site data cleared")
    }

    private fun chooseSearchEngine() {
        if (denyGuestAccess("Search engine settings")) return
        val engines = arrayOf("Google", "Bing", "DuckDuckGo", "Brave Search", "Perplexity")
        AlertDialog.Builder(this).setTitle("Default search engine")
            .setSingleChoiceItems(engines, engines.indexOf(searchEngine)) { dialog, which ->
                searchEngine = engines[which]
                searchEngineButton.text = getString(R.string.search_engine_button, searchEngine)
                searchEngineButton.contentDescription = getString(R.string.search_engine_accessibility, searchEngine)
                prefs.edit().putString("search_engine", searchEngine).apply()
                dialog.dismiss(); toast("$searchEngine selected")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun addCurrentSiteToHomeScreen() {
        val tab = currentTab() ?: return
        if (tab.isPrivate || tab.isGuest) {
            toast("Home-screen shortcuts are unavailable in Private and Guest Mode")
            return
        }
        val url = if (tab.lastUrl.startsWith("about:reader")) tab.readerOriginalUrl ?: tab.lastUrl else tab.lastUrl
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        val host = uri?.host?.takeIf { !it.isBlank() }
        if (scheme !in setOf("http", "https") || host == null) {
            toast("Open a website before adding a shortcut")
            return
        }
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            toast("This launcher does not support pinned shortcuts")
            return
        }

        // PWA manifest metadata is an enhancement only: unsupported engines or pages without
        // a valid same-origin manifest still get a regular Nova website shortcut.
        val fallbackTitle = tab.title.trim().ifBlank { host }.take(40)
        // PWA metadata uses HttpURLConnection, outside the browser engine's proxy.
        // Do not start those direct requests while Guest Tor or 1VPN is requested.
        if (!pwaMetadataNetworkAllowed(tab, url)) {
            pinWebsiteShortcut(uri, host, url, fallbackTitle, null)
            return
        }
        val engine = tab.browserEngine as? CefriumBrowserEngine
        if (tab.engineType == BrowserEngineType.CEFRIUM && engine != null) {
            runCatching { engine.evaluateJavaScript(PWA_MANIFEST_LINK_SCRIPT) { raw ->
                if (!pwaMetadataNetworkAllowed(tab, url)) {
                    runOnUiThread { pinWebsiteShortcut(uri, host, url, fallbackTitle, null) }
                    return@evaluateJavaScript
                }
                val manifestUrl = parsePwaManifestUrl(raw, uri)
                if (manifestUrl == null) {
                    runOnUiThread { pinWebsiteShortcut(uri, host, url, fallbackTitle, null) }
                } else {
                    Thread {
                        val metadata = fetchPwaShortcutMetadata(manifestUrl, uri) { pwaMetadataNetworkAllowed(tab, url) }
                        runOnUiThread {
                            val launchUri = metadata.first
                            val title = metadata.second.ifBlank { fallbackTitle }
                            pinWebsiteShortcut(launchUri, host, url, title, metadata.third)
                        }
                    }.start()
                }
            } }.onFailure { pinWebsiteShortcut(uri, host, url, fallbackTitle, null) }
        } else {
            pinWebsiteShortcut(uri, host, url, fallbackTitle, null)
        }
    }

    private fun pwaMetadataNetworkAllowed(tab: BrowserTab, expectedUrl: String): Boolean {
        val currentPageUrl = if (tab.lastUrl.startsWith("about:reader")) tab.readerOriginalUrl ?: tab.lastUrl else tab.lastUrl
        return !isFinishing && !isDestroyed &&
            currentPageUrl == expectedUrl && tab.engineType == BrowserEngineType.CEFRIUM &&
            tab.browserEngine is CefriumBrowserEngine && !tab.isPrivate && !tab.isGuest &&
            !guestTorReservesGeckoRuntime() && !NovaBrowserVpnController.isVpnEnabled()
    }

    private fun isSamePwaOrigin(candidate: java.net.URL, page: java.net.URL): Boolean {
        fun effectivePort(url: java.net.URL): Int = if (url.port == -1) url.defaultPort else url.port
        return candidate.protocol.equals(page.protocol, ignoreCase = true) &&
            candidate.host.equals(page.host, ignoreCase = true) &&
            effectivePort(candidate) == effectivePort(page)
    }

    private fun parsePwaManifestUrl(raw: String?, pageUri: Uri): java.net.URL? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val decoded = org.json.JSONTokener(raw).nextValue()
            val payload = if (decoded is JSONObject) decoded else JSONObject(decoded.toString())
            val href = payload.optString("manifestUrl")
            if (href.isBlank()) return null
            val page = java.net.URL(pageUri.toString())
            val manifest = java.net.URL(page, href)
            manifest.takeIf { it.protocol in setOf("http", "https") && isSamePwaOrigin(it, page) }
        }.getOrNull()
    }

    private fun readPwaResource(
        url: java.net.URL,
        maxBytes: Int,
        acceptPrefix: String? = null,
        networkAllowed: () -> Boolean
    ): ByteArray? {
        if (!networkAllowed()) return null
        val connection = (url.openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            instanceFollowRedirects = false
        }
        return try {
            if (connection.responseCode !in 200..299 || !networkAllowed()) return null
            if (acceptPrefix != null && !connection.contentType.orEmpty().startsWith(acceptPrefix)) return null
            val declared = connection.getHeaderFieldLong("Content-Length", -1L)
            if (declared > maxBytes) return null
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    if (!networkAllowed()) return null
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchPwaShortcutMetadata(
        manifestUrl: java.net.URL,
        pageUri: Uri,
        networkAllowed: () -> Boolean
    ): Triple<Uri, String, Bitmap?> {
        val fallback = Triple(Uri.parse(pageUri.toString()), "", null)
        return runCatching {
            val deadlineNanos = System.nanoTime() + 12_000_000_000L
            val canContinue = { networkAllowed() && System.nanoTime() < deadlineNanos }
            if (!canContinue()) return@runCatching fallback
            val bytes = readPwaResource(manifestUrl, 256 * 1024, networkAllowed = canContinue)
                ?: return@runCatching fallback
            val manifest = JSONObject(String(bytes, Charsets.UTF_8))
            val page = java.net.URL(pageUri.toString())
            fun sameOrigin(value: String): java.net.URL? = runCatching {
                java.net.URL(manifestUrl, value).takeIf {
                    it.protocol in setOf("http", "https") && isSamePwaOrigin(it, page)
                }
            }.getOrNull()
            val requestedStart = manifest.optString("start_url").takeIf { it.isNotBlank() }?.let(::sameOrigin) ?: page
            val scope = manifest.optString("scope").takeIf { it.isNotBlank() }?.let(::sameOrigin)
                ?: runCatching { java.net.URL(requestedStart, ".") }.getOrNull()
            val start = requestedStart.takeIf { scope == null || it.path.startsWith(scope.path) } ?: page
            val icons = manifest.optJSONArray("icons")
            var bitmap: Bitmap? = null
            if (icons != null) {
                val candidates = (0 until icons.length()).mapNotNull { index ->
                    icons.optJSONObject(index)?.let { it.optString("src") to it.optString("sizes") }
                }.sortedByDescending { (_, sizes) -> if (sizes.contains("192")) 2 else if (sizes == "any") 1 else 0 }
                for ((src, _) in candidates.take(4)) {
                    if (!canContinue()) break
                    val iconUrl = sameOrigin(src) ?: continue
                    val iconBytes = readPwaResource(iconUrl, 512 * 1024, "image/", canContinue) ?: continue
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 8192 || bounds.outHeight > 8192) continue
                    val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 512)
                    bitmap = BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    if (bitmap != null) break
                }
            }
            val title = manifest.optString("short_name").ifBlank { manifest.optString("name") }.take(40)
            Triple(Uri.parse(start.toString()), title, bitmap)
        }.getOrDefault(fallback)
    }

    private fun pinWebsiteShortcut(
        launchUri: Uri,
        host: String,
        originalUrl: String,
        title: String,
        icon: Bitmap?
    ) {
        val shortcut = ShortcutInfoCompat.Builder(this, "nova-site-${Uri.encode(host).take(32)}-${originalUrl.hashCode()}")
            .setShortLabel(title.take(25))
            .setLongLabel(title)
            .setIcon(if (icon != null) IconCompat.createWithBitmap(icon) else IconCompat.createWithResource(this, R.mipmap.ic_nova_launcher))
            .setIntent(Intent(Intent.ACTION_VIEW, launchUri, this, MainActivity::class.java).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
            .build()
        val requested = runCatching { ShortcutManagerCompat.requestPinShortcut(this, shortcut, null) }.getOrDefault(false)
        toast(if (requested) "Home-screen shortcut request sent" else "Could not create the home-screen shortcut")
    }

    private fun openNovaDownloadsFolder(manager: NovaDownloadManager, sourceTab: BrowserTab) {
        val requestEpoch = guestModeEpoch
        val sourceTabId = sourceTab.id
        val sourceEngine = sourceTab.engineType
        fun sourceContextStillEligible(): Boolean =
            guestModeEpoch == requestEpoch && !guestTorStartPending &&
                !TorGuestRoutingController.blocksNormalGeckoSessions() &&
                tabs.any { it.id == sourceTabId && !it.isPrivate && !it.isGuest && it.engineType == sourceEngine } &&
                currentTab()?.isGuest != true

        Thread({
            val files = manager.publishedFiles()
            val labels = files.map { file -> "${file.name}\n${NovaDownloadManager.formatBytes(file.size)}" }.toTypedArray()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (!sourceContextStillEligible()) {
                    toast("Downloads are hidden while Guest Mode is active or the original tab is no longer available")
                    return@runOnUiThread
                }
                if (files.isEmpty()) {
                    AlertDialog.Builder(this).setTitle("Nova Downloads folder")
                        .setMessage("This engine's Nova Downloads folder is empty. Completed files saved by this engine will appear here.")
                        .setPositiveButton(getString(R.string.close), null).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(this).setTitle("Nova Downloads folder")
                    .setItems(labels) { _, which ->
                        if (!sourceContextStillEligible()) {
                            toast("Downloads are unavailable in Guest Mode")
                            return@setItems
                        }
                        val file = files.getOrNull(which) ?: return@setItems
                        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(file.uri, file.mimeType).apply {
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { startActivity(intent) }
                            .onFailure { toast("No app can open this file: ${it.localizedMessage ?: "Unknown error"}") }
                    }
                    .setNegativeButton(getString(R.string.close), null).show()
            }
        }, "NovaDownloadsBrowse").start()
    }


    private fun printCurrentPage() {
        val tab = currentTab() ?: return
        if (tab.isPrivate || tab.isGuest) {
            toast("Printing is disabled in Private and Guest Mode to avoid saving session content outside the profile")
            return
        }
        if (!NovaPrintPolicy.canRequestPrint(tab.lastUrl)) {
            toast("Open a supported webpage before printing")
            return
        }
        if (!canUseTabEngine(tab, "Print")) return
        val title = NovaPrintPolicy.jobName(tab.title, tab.lastUrl)
        val printManager = getSystemService(Context.PRINT_SERVICE) as? android.print.PrintManager
        if (printManager == null) {
            toast("Android printing is unavailable on this device")
            return
        }
        if (tab.engineType == BrowserEngineType.CEFRIUM) {
            val engine = runCatching { ensureBrowserEngine(tab) as? CefriumBrowserEngine }.getOrNull()
            if (engine == null) {
                toast("This Cefrium page cannot be printed right now")
                return
            }
            runCatching {
                checkNotNull(printManager.print(
                    title,
                    engine.createPrintDocumentAdapter(title),
                    android.print.PrintAttributes.Builder().build()
                )) { "Android could not start the print dialog" }
            }.onFailure { toast("Could not open the print dialog: ${it.localizedMessage ?: "Unknown error"}") }
            return
        }
        val session = tab.session
        if (session == null) {
            toast("This GeckoView page is not ready to print")
            return
        }
        session.saveAsPdf().accept({ input ->
            val pdf = File(cacheDir, "nova-print-${UUID.randomUUID()}.pdf")
            Thread({
                val saved = runCatching {
                    val source = input ?: throw IllegalStateException("PDF stream is empty")
                    source.use { stream -> pdf.outputStream().use { output -> stream.copyTo(output) } }
                    pdf.isFile && pdf.length() > 0L
                }.getOrDefault(false)
                runOnUiThread {
                    if (!saved) {
                        pdf.delete()
                        toast("Nova could not create a PDF for this page")
                        return@runOnUiThread
                    }
                    runCatching {
                        checkNotNull(printManager.print(title, NovaPdfPrintAdapter(pdf), android.print.PrintAttributes.Builder().build())) {
                            "Android could not start the print dialog"
                        }
                    }.onFailure {
                        pdf.delete()
                        toast("Could not open the print dialog: ${it.localizedMessage ?: "Unknown error"}")
                    }
                }
            }, "NovaPdfCopy").start()
        }, { error ->
            runOnUiThread { toast("This page could not be exported as PDF: ${error?.localizedMessage ?: "Unsupported page"}") }
        })
    }

    private fun shareDownloadedFile(item: NovaDownloadManager.Item) {
        if (item.savedUri.isBlank()) {
            toast("Downloaded file is not available to share")
            return
        }
        runCatching {
            val uri = Uri.parse(item.savedUri)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = item.mimeType.ifBlank { "application/octet-stream" }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(contentResolver, item.fileName, uri)
            }
            startActivity(Intent.createChooser(send, "Share download"))
        }.onFailure { toast("Could not share this download: ${it.localizedMessage ?: "Unknown error"}") }
    }

    private fun deleteDownloadedFile(item: NovaDownloadManager.Item, manager: NovaDownloadManager) {
        if (item.savedUri.isBlank()) {
            toast("Downloaded file location is unavailable")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete downloaded file?")
            .setMessage("This permanently deletes ${item.fileName} from Android Downloads.")
            .setNegativeButton(getString(R.string.keep), null)
            .setPositiveButton("Delete file") { _, _ ->
                val deleted = runCatching {
                    contentResolver.delete(Uri.parse(item.savedUri), null, null) > 0
                }.getOrDefault(false)
                if (deleted) {
                    manager.cancel(item.id)
                    toast("Downloaded file deleted")
                } else {
                    toast("Android could not delete this file; it remains in Downloads")
                }
            }
            .show()
    }

    private fun clearCompletedDownloads(manager: NovaDownloadManager) {
        val completed = manager.items().filter { it.state == "COMPLETE" }
        if (completed.isEmpty()) {
            toast("No completed downloads to clear")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Clear completed downloads?")
            .setMessage("Nova will delete ${completed.size} completed file(s) from Android Downloads.")
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton("Clear completed") { _, _ ->
                var removed = 0
                completed.forEach { item ->
                    val deleted = item.savedUri.isNotBlank() && runCatching {
                        contentResolver.delete(Uri.parse(item.savedUri), null, null) > 0
                    }.getOrDefault(false)
                    if (deleted) {
                        manager.cancel(item.id)
                        removed++
                    }
                }
                refreshDownloadsList(manager.items())
                toast("Deleted $removed of ${completed.size} completed file(s)")
            }
            .show()
    }

    private fun sharePage() {
        val url = currentTab()?.lastUrl ?: return
        if (url == HOME_URL) { toast("Nothing to share on the home screen"); return }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url)
        }, "Share page"))
    }

    private fun showShieldSettings() {
        if (denyGuestAccess("Nova Shield settings")) return
        val modes = ShieldMode.values()
        val current = NovaShield.fromStored(prefs.getString("shield_mode", "STANDARD"))
        val tab = currentTab()
        val host = tab?.lastUrl?.let { runCatching { Uri.parse(it).host }.getOrNull() }
        val items = arrayOf("Off", "Standard · recommended", "Aggressive · may break sites", "Allow trackers on this site", "Show this page's protection log", "Phishing & malware protection status")
        AlertDialog.Builder(this).setTitle("Nova Shield · ${current.name}")
            .setItems(items) { _, which ->
                when (which) {
                    0, 1, 2 -> {
                        val mode = modes[which]
                        prefs.edit().putString("shield_mode", mode.name).apply()
                        NovaShield.apply(runtime, mode)
                        applyGeckoCookieBehavior()
                        if (mode != ShieldMode.OFF) switchAllCefriumTabsToGecko("Nova Shield enabled")
                        toast("Nova Shield: ${mode.name.lowercase().replaceFirstChar { it.uppercase() }}")
                    }
                    3 -> {
                        if (tab?.engineType == BrowserEngineType.CEFRIUM) { toast("Per-site tracker controls require GeckoView; enable Nova Shield to switch this tab safely"); return@setItems }
                        val permission = tab?.trackingPermission
                        if (permission == null) { toast("Open a website first; site tracking controls are not available on this page"); return@setItems }
                        val allow = permission.value != GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                        runtime.storageController.setPermission(permission, if (allow) GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                        toast(if (allow) "Trackers allowed for $host · reload the page" else "Tracking protection restored for $host · reload the page")
                        tab.browserEngine?.reload()
                    }
                    4 -> {
                        val session = tab?.session
                        if (session == null) { toast("Open a page first"); return@setItems }
                        runtime.getContentBlockingController().getLog(session).accept({ entries ->
                            runOnUiThread { AlertDialog.Builder(this).setTitle("Nova Shield · $host")
                                .setMessage("${entries?.size ?: 0} content-protection log entries recorded for this page. GeckoView applies its native tracker and Safe Browsing lists.")
                                .setPositiveButton("OK", null).show() }
                        }, { error -> runOnUiThread { toast("Protection log unavailable: ${error?.localizedMessage ?: "Unknown error"}") } })
                    }
                    5 -> {
                        val activeTab = tab
                        if (activeTab == null) {
                            toast("Open a website first")
                            return@setItems
                        }
                        when (ThreatProtectionPolicy.availability(activeTab.engineType)) {
                            ThreatProtectionPolicy.Availability.NATIVE_SAFE_BROWSING_ACTIVE -> {
                                AlertDialog.Builder(this)
                                    .setTitle("Phishing & malware protection")
                                    .setMessage("Active in GeckoView. Native Safe Browsing lists remain enabled in every Nova Shield mode, including Off. This helps warn about known deceptive and harmful sites, but cannot guarantee detection of every threat.")
                                    .setPositiveButton("OK", null).show()
                            }
                            ThreatProtectionPolicy.Availability.UNAVAILABLE_ON_CEFRIUM -> {
                                AlertDialog.Builder(this)
                                    .setTitle("Protection unavailable on Cefrium")
                                    .setMessage("Cefrium does not currently provide Nova with a verified phishing and malware reputation service. This page is not covered by GeckoView Safe Browsing. Switch this tab to GeckoView to use its native Safe Browsing lists. The page will reload; website data is not transferred between engines.")
                                    .setPositiveButton("Switch to GeckoView") { _, _ ->
                                        switchTabToGeckoForThreatProtection(activeTab)
                                    }
                                    .setNegativeButton("Not now", null).show()
                            }
                        }
                    }
                }
            }.setNegativeButton("Close", null).show()
    }

    private fun applyProtectedDns(enabled: Boolean) {
        Cefrium.setSecureDns(
            if (enabled) Cefrium.SECURE_DNS_SECURE else Cefrium.SECURE_DNS_AUTOMATIC,
            if (enabled) "$ADGUARD_DOH_ENDPOINT{?dns}" else null
        )
        if (!::runtime.isInitialized) return
        if (enabled) {
            runtime.settings.setTrustedRecursiveResolverUri(ADGUARD_DOH_ENDPOINT)
            // ONLY mode intentionally has no system-DNS fallback if the encrypted resolver fails.
            runtime.settings.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_ONLY)
        } else {
            runtime.settings.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_OFF)
        }
    }

    private fun showProtectedDnsSettings() {
        val enabled = prefs.getBoolean("protected_dns_enabled", false)
        val check = CheckBox(this).apply {
            text = "Use AdGuard encrypted DNS for Nova browsing"
            isChecked = enabled
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Protected DNS · AdGuard DNS")
            .setMessage("When enabled, Nova's GeckoView and eligible Cefrium Normal-tab engines send DNS lookups to AdGuard over DNS-over-HTTPS. Strict mode does not fall back to unencrypted DNS if the resolver is unavailable.\n\nThis protects DNS lookups made by Nova only. It is not a VPN, does not hide your IP address, and does not change DNS for other apps or Android itself.")
            .setView(check)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putBoolean("protected_dns_enabled", check.isChecked).apply()
                applyProtectedDns(check.isChecked)
                toast(if (check.isChecked) "AdGuard encrypted DNS enabled in Nova" else "Protected DNS disabled")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openOrbotTorRouting() {
        val stopping = TorGuestRoutingController.isStopping()
        if (currentTab()?.isGuest != true && !stopping) {
            toast("Tor status is available only in Guest Mode")
            return
        }
        val dialog = AlertDialog.Builder(this).setTitle("Nova Guest Tor · always on")
            .setMessage("Guest Mode requires the embedded Tor service. Browsing is allowed only after Tor has built a circuit, the SOCKS5 proxy is active, remote DNS is enabled, and WebRTC leak protection is confirmed. If Tor or its proxy bridge stops, Guest Mode closes instead of falling back to your normal connection.\n\nStatus: ${TorGuestRoutingController.statusText()}\n${NovaVpnHonestyPolicy.guestTorStatusLabel(guestTorReservesGeckoRuntime(), TorGuestRoutingController.isReady())}")
        if (stopping) {
            dialog.setPositiveButton("Retry reset") { _, _ ->
                TorGuestRoutingController.stop { runOnUiThread { restoreAfterGuestTorStop() } }
            }.setNegativeButton("Close", null)
        } else {
            dialog.setPositiveButton("OK", null)
        }
        dialog.show()
    }

    @androidx.annotation.OptIn(markerClass = [org.mozilla.geckoview.ExperimentalGeckoViewApi::class])
    private fun showAiAssistant() {
        if (denyGuestAccess("AI Assistant")) return
        val tab = currentTab()
        NovaAiAssistantUi(
            context = this,
            store = NovaAiSecureStore(this),
            requestPageText = { callback ->
                val active = currentTab()
                if (active == null || active.isPrivate || active.isGuest ||
                    active.lastUrl == HOME_URL || active.lastUrl.startsWith("about:") ||
                    active.lastUrl.startsWith("chrome:")) {
                    callback(null)
                } else if (active.engineType == BrowserEngineType.CEFRIUM) {
                    val expectedUrl = active.lastUrl
                    val engine = active.browserEngine as? CefriumBrowserEngine
                    if (engine == null) {
                        callback(null)
                    } else {
                        // Cefrium exposes a typed page-JavaScript callback. Extract only readable
                        // article/main text and remove form controls before sending context to AI.
                        val script = """
                            (() => {
                              const root = document.querySelector('article') ||
                                document.querySelector('[role="main"]') ||
                                document.querySelector('main') || document.body;
                              if (!root) return JSON.stringify({ url: location.href, text: '' });
                              const excluded = 'script,style,form,input,textarea,select,button,iframe,object,embed,[contenteditable="true"],[hidden],[aria-hidden="true"]';
                              const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
                              const parts = [];
                              let total = 0;
                              let node;
                              while ((node = walker.nextNode()) && total < 24000) {
                                const parent = node.parentElement;
                                if (!parent || parent.closest(excluded)) continue;
                                let element = parent;
                                let visible = true;
                                // Inspect ancestors above the selected article too; a hidden
                                // wrapper can make the entire article invisible while retaining layout.
                                while (element) {
                                  const style = getComputedStyle(element);
                                  if (style.display === 'none' || style.visibility === 'hidden' ||
                                      style.visibility === 'collapse' || Number(style.opacity) === 0) {
                                    visible = false;
                                    break;
                                  }
                                  element = element.parentElement;
                                }
                                if (!visible || parent.getClientRects().length === 0) continue;
                                const value = (node.nodeValue || '').replace(/\s+/g, ' ').trim();
                                if (!value) continue;
                                const chunk = value.slice(0, 24000 - total);
                                if (chunk) {
                                  parts.push(chunk);
                                  total += chunk.length;
                                }
                              }
                              const text = parts.join(' ').trim().slice(0, 24000);
                              return JSON.stringify({ url: location.href, text });
                            })()
                        """.trimIndent()
                        runCatching {
                            engine.evaluateJavaScript(script) { raw ->
                                val pageText = runCatching {
                                    val decoded = org.json.JSONTokener(raw ?: "{}").nextValue()
                                    val payload = if (decoded is JSONObject) decoded else JSONObject(decoded.toString())
                                    val pageUrl = payload.optString("url")
                                    val text = payload.optString("text").take(24000)
                                    // Compare the complete URL, including fragments. Hash-route
                                    // changes can represent a different document view even when the
                                    // origin/path/query remain unchanged.
                                    if (text.isNotBlank() && expectedUrl == pageUrl &&
                                        active.lastUrl == pageUrl && currentTab()?.id == active.id) {
                                        aiPageText[active.id] = text
                                        aiPageUrl[active.id] = pageUrl
                                        text
                                    } else null
                                }.getOrNull()
                                callback(pageText)
                            }
                        }.onFailure { callback(null) }
                    }
                } else if (active.engineType == BrowserEngineType.GECKOVIEW) {
                    val expectedUrl = active.lastUrl
                    val cachedUrl = aiPageUrl[active.id]
                    val fallback = aiPageText[active.id]?.takeIf { cachedUrl != null && sameDocumentUrl(cachedUrl, expectedUrl) }
                    // GeckoView intentionally exposes no direct page-JavaScript evaluation API.
                    // The built-in Nova reader extension asynchronously maintains this tab-bound cache.
                    callback(fallback)
                } else {
                    callback(null)
                }
            },
            persistHistory = tab?.isPrivate != true && tab?.isGuest != true,
            notify = ::toast
        ).show()
    }

    private fun purgeRetiredUserPackages() {
        runCatching { java.io.File(filesDir, "nova_cefrium_addons").deleteRecursively() }
        val retainedBuiltIns = setOf(
            NovaBrowserVpnController.BRIDGE_ID,
            "nova-ai-reader@nova.browser"
        )
        val controller = runtime.getWebExtensionController()
        controller.list().accept({ installed ->
            installed.orEmpty().filterNotNull().filterNot { it.id in retainedBuiltIns }.forEach { item ->
                controller.uninstall(item).accept(
                    { android.util.Log.i("NovaBrowser", "Removed retired user-installed browser component") },
                    { error -> android.util.Log.w("NovaBrowser", "Could not remove a retired user-installed browser component", error) }
                )
            }
        }, { error ->
            android.util.Log.w("NovaBrowser", "Could not enumerate retired browser components", error)
        })
    }

    private fun registerAiPageReaderBridge() {
        runtime.getWebExtensionController()
            .ensureBuiltIn("resource://android/assets/nova_ai_reader/", "nova-ai-reader@nova.browser")
            .accept({ bridge ->
                aiPageReaderBridge = bridge
                tabs.forEach { tab ->
                    if (!tab.isPrivate && !tab.isGuest) tab.session?.let { attachAiPageReader(it, tab) }
                }
            }, { error ->
                runOnUiThread { if (!isFinishing && !isDestroyed) toast("AI page reader unavailable: ${error?.localizedMessage ?: "Could not initialize the reader"}") }
            })
    }

    private fun sameDocumentUrl(left: String, right: String): Boolean =
        left == right

    private fun attachAiPageReader(session: GeckoSession, tab: BrowserTab) {
        if (tab.isPrivate || tab.isGuest) return
        val extension = aiPageReaderBridge ?: return
        session.getWebExtensionController().setMessageDelegate(
            extension,
            object : WebExtension.MessageDelegate {
                override fun onMessage(
                    nativeApp: String,
                    message: Any,
                    sender: WebExtension.MessageSender
                ): GeckoResult<Any>? {
                    if (nativeApp != "nova_ai_reader") return null
                    val payload = message as? JSONObject ?: return null
                    if (payload.optString("type") != "nova_page_text") return null
                    val source = sender.session ?: session
                    val sourceTab = tabs.firstOrNull { it.session === source } ?: return null
                    if (sourceTab.isPrivate || sourceTab.isGuest) return null
                    val text = payload.optString("text").take(24000)
                    val pageUrl = payload.optString("url").ifBlank { sourceTab.lastUrl }
                    if (!sameDocumentUrl(sourceTab.lastUrl, pageUrl)) return null
                    if (text.isNotBlank()) {
                        aiPageText[sourceTab.id] = text
                        aiPageUrl[sourceTab.id] = pageUrl
                    }
                    return null
                }
            },
            "nova_ai_reader"
        )
    }

    private fun capabilityRouteForTab(tab: BrowserTab, required: BrowserEngineCapability) =
        BrowserEngineCapabilityPolicy.resolve(
            currentEngine = tab.engineType,
            profile = BrowserEngineProfilePolicy.kindFor(tab.isPrivate, tab.isGuest),
            required = required,
            currentCapabilities = if (tab.engineType == BrowserEngineType.CEFRIUM)
                BrowserEngineCapabilitySets.CEFRIUM else BrowserEngineCapabilitySets.GECKOVIEW
        )

    private fun fillSavedLoginForCurrentCefriumTab() {
        val tab = currentTab() ?: return
        if (tab.engineType != BrowserEngineType.CEFRIUM || tab.isPrivate || tab.isGuest) {
            toast("Saved-login filling is available only in Normal Cefrium tabs")
            return
        }
        val expectedOrigin = cefriumOriginForUrl(tab.lastUrl)
        if (expectedOrigin == null) { toast("Open a secure website first"); return }
        val keyguard = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (!keyguard.isDeviceSecure) {
            AlertDialog.Builder(this).setTitle(getString(R.string.vault_device_lock_required))
                .setMessage(getString(R.string.vault_device_lock_message))
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val prompt = android.hardware.biometrics.BiometricPrompt.Builder(this)
            .setTitle(getString(R.string.unlock_password_vault))
            .setSubtitle(getString(R.string.verify_identity))
            .setDescription("Verify before filling a saved login into this website")
            .setDeviceCredentialAllowed(true)
            .build()
        prompt.authenticate(android.os.CancellationSignal(), mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) {
                runOnUiThread {
                    val active = tabs.firstOrNull { it.id == tab.id }
                    if (active == null || currentTab()?.id != tab.id || active.engineType != BrowserEngineType.CEFRIUM ||
                        cefriumOriginForUrl(active.lastUrl) != expectedOrigin) {
                        toast("The active page changed; no login was filled")
                        return@runOnUiThread
                    }
                    val matches = runCatching { passwordVault.all().filter {
                        CefriumPasswordAutofillPolicy.isAllowed(active.lastUrl, it.origin, it.formActionOrigin)
                    } }.getOrElse {
                        toast("Could not read Nova Password Manager: ${it.localizedMessage ?: "Unknown error"}")
                        return@runOnUiThread
                    }
                    if (matches.isEmpty()) { toast("No saved login matches this exact website"); return@runOnUiThread }
                    val labels = matches.map { it.username.ifBlank { "Saved login" } }.toTypedArray()
                    AlertDialog.Builder(this@MainActivity).setTitle("Choose saved login · ${Uri.parse(active.lastUrl).host}")
                        .setItems(labels) { _, which ->
                            val stillActive = tabs.firstOrNull { it.id == tab.id }
                            if (stillActive == null || currentTab()?.id != tab.id ||
                                cefriumOriginForUrl(stillActive.lastUrl) != expectedOrigin) {
                                toast("The active page changed; no login was filled")
                                return@setItems
                            }
                            val login = matches[which]
                            if (!CefriumPasswordAutofillPolicy.isAllowed(stillActive.lastUrl, login.origin, login.formActionOrigin)) {
                                toast("Saved login origin did not match this page")
                                return@setItems
                            }
                            val engine = stillActive.browserEngine as? CefriumBrowserEngine
                            if (engine == null) { toast("The browser tab is not ready"); return@setItems }
                            engine.fillSavedLogin(expectedOrigin, login.username, login.password, login.formActionOrigin) { success, message ->
                                runOnUiThread { if (!isFinishing && !isDestroyed) toast(message) }
                            }
                        }.setNegativeButton(android.R.string.cancel, null).show()
                }
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                runOnUiThread { toast(getString(R.string.vault_locked)) }
            }
        })
    }

    private fun showPasswordVault() {
        val keyguard = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (!keyguard.isDeviceSecure) {
            AlertDialog.Builder(this).setTitle(getString(R.string.vault_device_lock_required))
                .setMessage(getString(R.string.vault_device_lock_message))
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val prompt = android.hardware.biometrics.BiometricPrompt.Builder(this)
            .setTitle(getString(R.string.unlock_password_vault))
            .setSubtitle(getString(R.string.verify_identity))
            .setDescription(getString(R.string.password_vault_protected))
            .setDeviceCredentialAllowed(true)
            .build()
        prompt.authenticate(android.os.CancellationSignal(), mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) {
                runOnUiThread { showPasswordVaultUnlocked() }
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                runOnUiThread { toast(getString(R.string.vault_locked)) }
            }
        })
    }

    private fun showPasswordVaultUnlocked() {
        val entries = runCatching { passwordVault.all() }.getOrElse {
            toast(getString(R.string.vault_read_failed))
            return
        }
        val revealed = mutableSetOf<String>()
        val listView = ListView(this)
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount(): Int = entries.size
            override fun getItem(position: Int) = entries[position]
            override fun getItemId(position: Int): Long = getItem(position).guid?.hashCode()?.toLong() ?: position.toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    addView(TextView(this@MainActivity).apply { textSize = 14f; setTextColor(getColor(R.color.nova_text_primary)) })
                    addView(TextView(this@MainActivity).apply { textSize = 12f; setTextColor(getColor(R.color.nova_text_secondary)) })
                    addView(TextView(this@MainActivity).apply { textSize = 12f; setTextColor(getColor(R.color.nova_text_secondary)) })
                }
                val entry = getItem(position)
                (row.getChildAt(0) as TextView).text = Uri.parse(entry.origin).host ?: entry.origin
                (row.getChildAt(1) as TextView).text = getString(R.string.vault_username, entry.username)
                val isRevealed = entry.guid?.let { it in revealed } == true
                (row.getChildAt(2) as TextView).text = getString(if (isRevealed) R.string.vault_password_revealed else R.string.vault_password_hidden, if (isRevealed) entry.password else "••••••••")
                row.minimumHeight = dp(64)
                return row
            }
        }
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            entries.getOrNull(position)?.guid?.let { if (!revealed.add(it)) revealed.remove(it) }
            adapter.notifyDataSetChanged()
        }
        var vaultDialog: AlertDialog? = null
        val refreshVault = { vaultDialog?.dismiss(); showPasswordVaultUnlocked() }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            entries.getOrNull(position)?.let { showPasswordEntryActions(it, refreshVault) }
            true
        }
        val addButton = actionButton(getString(R.string.add_login)) { editPasswordEntry(null, refreshVault) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(addButton); addView(listView, LinearLayout.LayoutParams(-1, 0, 1f)) }
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.password_vault_count, entries.size)).setView(content)
            .setPositiveButton(getString(R.string.close), null).create()
        vaultDialog = dialog
        dialog.show()
    }

    private fun showPasswordEntryActions(entry: org.mozilla.geckoview.Autocomplete.LoginEntry, onChanged: () -> Unit) {
        showBottomSheetMenu(Uri.parse(entry.origin).host ?: entry.origin, listOf(
            getString(R.string.reveal_password) to {
                val reveal = EditText(this).apply { setText(entry.password); inputType = InputType.TYPE_CLASS_TEXT; isSingleLine = true }
                AlertDialog.Builder(this).setTitle(getString(R.string.saved_password)).setView(reveal).setPositiveButton(getString(R.string.close), null).show()
            },
            getString(R.string.edit_login) to { editPasswordEntry(entry, onChanged) },
            getString(R.string.copy_password) to {
                copyToClipboard(getString(R.string.password), entry.password)
            },
            getString(R.string.delete_login) to {
                AlertDialog.Builder(this).setTitle(getString(R.string.delete_login)).setMessage(getString(R.string.delete_login_confirm))
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton(getString(R.string.delete)) { _, _ -> passwordVault.delete(entry.guid ?: return@setPositiveButton); onChanged() }.show()
            }
        ))
    }

    private fun editPasswordEntry(entry: org.mozilla.geckoview.Autocomplete.LoginEntry?, onSaved: () -> Unit) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val origin = EditText(this).apply { hint = getString(R.string.login_origin); setText(entry?.origin.orEmpty()); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        val username = EditText(this).apply { hint = getString(R.string.username); setText(entry?.username.orEmpty()) }
        val password = EditText(this).apply { hint = getString(R.string.password); setText(entry?.password.orEmpty()); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        form.addView(origin); form.addView(username); form.addView(password)
        AlertDialog.Builder(this).setTitle(getString(if (entry == null) R.string.add_login else R.string.edit_login)).setView(form)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val url = origin.text.toString().trim()
                if (!(url.startsWith("https://") || url.startsWith("http://")) || username.text.isBlank() || password.text.isBlank()) {
                    toast(getString(R.string.invalid_login_details))
                    return@setPositiveButton
                }
                val builder = org.mozilla.geckoview.Autocomplete.LoginEntry.Builder()
                    .guid(entry?.guid ?: java.util.UUID.randomUUID().toString())
                    .origin(url).username(username.text.toString()).password(password.text.toString())
                entry?.httpRealm?.let { builder.httpRealm(it) }
                entry?.formActionOrigin?.let { builder.formActionOrigin(it) }
                runCatching { passwordVault.save(builder.build()); onSaved() }
                    .onFailure { toast(getString(R.string.vault_save_failed)) }
            }.setNegativeButton("Cancel", null).show()
    }


    private fun addManualLogin() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val origin = EditText(this).apply { hint = "https://example.com"; setSingleLine(true) }
        val username = EditText(this).apply { hint = "Username"; setSingleLine(true) }
        val password = EditText(this).apply { hint = "Password"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        form.addView(origin); form.addView(username); form.addView(password)
        AlertDialog.Builder(this).setTitle("Add encrypted login").setView(form)
            .setPositiveButton("Save") { _, _ ->
                val raw = origin.text.toString().trim()
                val url = if (raw.startsWith("https://") || raw.startsWith("http://")) raw else "https://$raw"
                if (Uri.parse(url).host.isNullOrBlank() || username.text.isBlank() || password.text.isBlank()) { toast("Enter a valid site, username and password"); return@setPositiveButton }
                runCatching { passwordVault.save(Autocomplete.LoginEntry.Builder().guid(UUID.randomUUID().toString()).origin("${Uri.parse(url).scheme}://${Uri.parse(url).host}").username(username.text.toString()).password(password.text.toString()).build()) }
                    .onSuccess { toast("Login saved encrypted on this device") }
                    .onFailure { toast("Could not save login: ${it?.localizedMessage ?: "Unknown error"}") }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, value)
        if (Build.VERSION.SDK_INT >= 33) {
            // Hide the value from clipboard previews and overlays.
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        clipboardClearTask?.let(clipboardHandler::removeCallbacks)
        val clearTask = Runnable {
            runCatching {
                val current = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                // Background apps cannot read the clip on Android 10+, so a null read still clears our own copy.
                if (current == null || current == value) clipboard.clearPrimaryClip()
            }
        }
        clipboardClearTask = clearTask
        clipboardHandler.postDelayed(clearTask, 45_000L)
        toast("$label copied · clears from the clipboard in 45 s")
    }

    private fun javaScriptPreferenceKey(engine: BrowserEngineType, url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val origin = "$scheme://$host${if (uri.port > 0) ":${uri.port}" else ""}"
        return "javascript_enabled_${engine.name.lowercase()}_${Uri.encode(origin)}"
    }

    private fun defaultJavaScriptPreferenceKey(engine: BrowserEngineType): String =
        "site_default_${engine.name.lowercase()}_javascript"

    private fun isJavaScriptAllowed(engine: BrowserEngineType, url: String): Boolean {
        val key = javaScriptPreferenceKey(engine, url) ?: return true
        val canonicalDefault = defaultJavaScriptPreferenceKey(engine)
        val legacyDefault = "site_default_javascript_${engine.name.lowercase()}"
        return prefs.getBoolean(key, prefs.getBoolean(canonicalDefault, prefs.getBoolean(legacyDefault, true)))
    }

    private fun applyJavaScriptSetting(tab: BrowserTab, url: String, reload: Boolean) {
        val enabled = isJavaScriptAllowed(tab.engineType, url)
        when (tab.engineType) {
            BrowserEngineType.GECKOVIEW -> tab.session?.settings?.setAllowJavascript(enabled)
            BrowserEngineType.CEFRIUM -> (tab.browserEngine as? CefriumBrowserEngine)?.setJavaScriptEnabled(enabled)
        }
        if (reload && tab.lastUrl.startsWith("http", true)) tab.browserEngine?.reload()
    }

    private fun toggleJavaScriptForCurrentSite(
        tab: BrowserTab,
        expectedOrigin: String? = null,
        expectedEngine: BrowserEngineType? = null
    ) {
        val currentOrigin = siteOriginForUrl(tab.lastUrl)
        if (expectedOrigin != null && (currentOrigin != expectedOrigin || tab.engineType != expectedEngine)) {
            toast("This tab navigated or changed engines. Reopen Site controls.")
            return
        }
        val key = javaScriptPreferenceKey(tab.engineType, tab.lastUrl)
        if (key == null) { toast("JavaScript controls are available on HTTP and HTTPS websites"); return }
        val nextEnabled = !isJavaScriptAllowed(tab.engineType, tab.lastUrl)
        prefs.edit().putBoolean(key, nextEnabled).apply()
        applyJavaScriptSetting(tab, tab.lastUrl, reload = true)
        toast("JavaScript ${if (nextEnabled) "allowed" else "blocked"} for ${Uri.parse(tab.lastUrl).host} · reload started")
    }
    private fun siteSettingKey(engine: BrowserEngineType, url: String, setting: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val origin = "$scheme://$host${if (uri.port > 0) ":${uri.port}" else ""}"
        return "site_" + setting + "_" + engine.name.lowercase() + "_" + Uri.encode(origin)
    }
    private fun siteSettingAllowed(engine: BrowserEngineType, url: String, setting: String, default: Boolean): Boolean {
        val key = siteSettingKey(engine, url, setting) ?: return default
        val globalDefault = prefs.getBoolean("site_default_${engine.name.lowercase()}_$setting", default)
        return prefs.getBoolean(key, globalDefault)
    }
    private fun toggleSiteSetting(
        tab: BrowserTab,
        setting: String,
        default: Boolean,
        expectedOrigin: String? = null,
        expectedEngine: BrowserEngineType? = null
    ) {
        val currentOrigin = siteOriginForUrl(tab.lastUrl)
        if (expectedOrigin != null && (currentOrigin != expectedOrigin || tab.engineType != expectedEngine)) {
            toast("This tab navigated or changed engines. Reopen Site controls.")
            return
        }
        val key = siteSettingKey(tab.engineType, tab.lastUrl, setting) ?: return toast("Open an HTTP or HTTPS site first")
        val enabled = !siteSettingAllowed(tab.engineType, tab.lastUrl, setting, default)
        prefs.edit().putBoolean(key, enabled).apply()
        val engine = tab.browserEngine as? CefriumBrowserEngine
        when (setting) {
            "autoplay" -> if (tab.engineType == BrowserEngineType.CEFRIUM) engine?.setAutoplayAllowed(enabled) else {
                tab.contentPermissions.filter { it.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE || it.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE }
                    .forEach { runtime.storageController.setPermission(it, if (enabled) GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY) }
            }
            "ads" -> engine?.setAdBlockingEnabled(enabled)
            "sound" -> if (tab.engineType == BrowserEngineType.CEFRIUM) engine?.setAudioMuted(!enabled) else tab.mediaSession?.muteAudio(!enabled)
        }
        if (setting != "sound") tab.browserEngine?.reload()
        if (setting == "ads" && tab.engineType == BrowserEngineType.CEFRIUM) {
            toast("Ads and tracker protection turned " + (if (enabled) "on" else "off"))
        } else {
            toast(setting + (if (enabled) " allowed for " else " blocked for ") + (Uri.parse(tab.lastUrl).host ?: "site"))
        }
    }

    /**
     * Android Site Settings hub. Rules are keyed by engine and exact origin,
     * keeping GeckoView and Cefrium preferences isolated.
     */
    private data class SiteRuleEntry(val engine: BrowserEngineType, val origin: String)

    private fun storedSiteRuleEntries(): List<SiteRuleEntry> {
        val entries = linkedSetOf<SiteRuleEntry>()
        prefs.all.keys.forEach { key ->
            BrowserEngineType.values().forEach engineLoop@{ engine ->
                val engineToken = engine.name.lowercase() + "_"
                val encoded = when {
                    key.startsWith("javascript_enabled_$engineToken") ->
                        key.removePrefix("javascript_enabled_$engineToken")
                    key.startsWith("site_permission_$engineToken") -> {
                        val permissionAndOrigin = key.removePrefix("site_permission_$engineToken")
                        val separator = permissionAndOrigin.indexOf('_')
                        if (separator < 1) return@engineLoop
                        permissionAndOrigin.substring(separator + 1)
                    }
                    key.startsWith("site_$engineToken") -> key.removePrefix("site_$engineToken")
                    key.startsWith("site_") -> {
                        val marker = "_$engineToken"
                        val markerIndex = key.indexOf(marker)
                        if (markerIndex < 0) return@engineLoop
                        key.substring(markerIndex + marker.length)
                    }
                    else -> return@engineLoop
                }
                val origin = runCatching { Uri.decode(encoded) }.getOrNull().orEmpty()
                val uri = runCatching { Uri.parse(origin) }.getOrNull()
                if (uri != null && uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()) {
                    entries.add(SiteRuleEntry(engine, origin))
                }
            }
        }
        tabs.filter { !it.isGuest && !it.isPrivate }.forEach { tab ->
            val uri = runCatching { Uri.parse(tab.lastUrl) }.getOrNull() ?: return@forEach
            if (uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()) {
                val origin = "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
                entries.add(SiteRuleEntry(tab.engineType, origin))
            }
        }
        return entries.sortedWith(compareBy({ it.engine.name }, { it.origin }))
    }

private fun showQuickSettings() {
        val tab = currentTab()
        val isGuest = tab?.isGuest == true
        val isRestricted = tab?.let { it.isPrivate || it.isGuest } == true
        val host = tab?.lastUrl?.let { runCatching { Uri.parse(it).host }.getOrNull() }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(panel, android.view.ViewGroup.LayoutParams(-1, -2))
        }
        lateinit var dialog: AlertDialog

        fun addHeading(label: String) {
            panel.addView(TextView(this).apply {
                text = label
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
                setTextColor(0xFFBFA9D8.toInt())
                setPadding(dp(12), dp(12), dp(12), dp(6))
            })
        }

        fun addDivider() {
            panel.addView(View(this).apply { setBackgroundColor(0x332F263A) },
                LinearLayout.LayoutParams(-1, dp(1)))
        }

        fun addSwitch(
            title: String,
            detail: String,
            checked: Boolean,
            enabled: Boolean = true,
            onChanged: (Boolean) -> Unit
        ) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(9), dp(8), dp(9))
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.48f
            }
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(TextView(this).apply {
                text = title; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFF1E9FA.toInt())
            })
            labels.addView(TextView(this).apply {
                text = detail; textSize = 11f
                setTextColor(0xFFB9AEC8.toInt())
            })
            val toggle = android.widget.Switch(this).apply {
                isChecked = checked
                isEnabled = enabled
                contentDescription = title
                setOnCheckedChangeListener { _, value -> onChanged(value) }
            }
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(toggle, LinearLayout.LayoutParams(-2, -2))
            panel.addView(row, LinearLayout.LayoutParams(-1, -2))
            addDivider()
        }

        fun addAction(title: String, detail: String, enabled: Boolean = true, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.48f
                isClickable = enabled
                setOnClickListener { if (enabled) { dialog.dismiss(); action() } }
            }
            row.addView(TextView(this).apply {
                text = title; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFF1E9FA.toInt())
            })
            row.addView(TextView(this).apply {
                text = detail; textSize = 11f
                setTextColor(0xFFB9AEC8.toInt())
            })
            panel.addView(row, LinearLayout.LayoutParams(-1, -2))
            addDivider()
        }

        addHeading("PAGE")
        addSwitch(
            "Desktop site",
            if (host == null) "Open a website to change its layout" else "Request the desktop version of this site",
            checked = tab?.desktopSite == true,
            enabled = tab != null && !isRestricted && host != null
        ) { toggleDesktopSite() }
        addAction("Reader mode", "Read supported articles with less clutter",
            enabled = tab?.lastUrl?.startsWith("http") == true) { openReaderMode() }
        addAction("Translate page", "Translate this page with GeckoView",
            enabled = tab?.lastUrl?.startsWith("http") == true) { translatePage() }
        addAction("Share page", "Send the current page to another app",
            enabled = tab != null && tab.lastUrl != HOME_URL) { sharePage() }
        addAction("Bookmark page", "Save or remove this page from bookmarks",
            enabled = tab != null && !isRestricted && tab.lastUrl != HOME_URL) { toggleBookmark() }

        addHeading("PRIVACY & PERMISSIONS")
        addSwitch(
            "Protected DNS",
            "AdGuard DNS-over-HTTPS for Nova only · not a VPN",
            checked = prefs.getBoolean("protected_dns_enabled", false),
            enabled = !isRestricted
        ) { enabled ->
            prefs.edit().putBoolean("protected_dns_enabled", enabled).apply()
            applyProtectedDns(enabled)
            toast(if (enabled) "AdGuard encrypted DNS enabled in Nova" else "Protected DNS disabled")
        }
        addSwitch(
            "Data Saver",
            "Block page images in both engines to reduce data use",
            checked = prefs.getBoolean("nova_data_saver_enabled", false),
            enabled = !isRestricted
        ) { enabled ->
            prefs.edit().putBoolean("nova_data_saver_enabled", enabled).apply()
            applyGeckoImageBehavior { applied ->
                if (!applied) toast("GeckoView image settings could not be updated")
                tabs.filter { !it.isPrivate && !it.isGuest && it.lastUrl.startsWith("http", true) }
                    .forEach { it.browserEngine?.reload() }
            }
            toast(if (enabled) "Data Saver enabled · page images blocked" else "Data Saver disabled · image preferences restored")
        }
        addAction("Nova Shield", "Current mode: ${NovaShield.fromStored(prefs.getString("shield_mode", "STANDARD")).name}",
            enabled = !isGuest) { showShieldSettings() }
        addAction("Site permissions", "Review permissions and clear this site's data",
            enabled = !isGuest && host != null) { showSiteControls() }

        dialog = AlertDialog.Builder(this)
            .setTitle("Quick settings")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .create()
        dialog.show()
    }

    private fun showSiteSettingsHub() {
        if (denyGuestAccess("Site Settings")) return
        val host = currentTab()?.lastUrl?.let { runCatching { Uri.parse(it).host }.getOrNull() }
        val actions = arrayOf(
            "Current site settings · ${host ?: "open a website"}",
            "Default settings · GeckoView",
            "Default settings · Cefrium",
            "All sites",
            "Clear all site data · GeckoView",
            "Clear all site data · Cefrium"
        )
        AlertDialog.Builder(this).setTitle("Site Settings")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showSiteControls()
                    1 -> showDefaultSiteSettings(BrowserEngineType.GECKOVIEW)
                    2 -> showDefaultSiteSettings(BrowserEngineType.CEFRIUM)
                    3 -> showAllSiteSettings()
                    4 -> confirmClearAllSiteData(BrowserEngineType.GECKOVIEW)
                    5 -> confirmClearAllSiteData(BrowserEngineType.CEFRIUM)
                }
            }.setNegativeButton(getString(R.string.close), null).show()
    }

    private fun resetClosedGeckoAutoplayPermissions() {
        if (!::runtime.isInitialized) return
        val openOrigins = tabs.asSequence()
            .filter { it.engineType == BrowserEngineType.GECKOVIEW && !it.isPrivate && !it.isGuest }
            .mapNotNull { siteOriginForUrl(it.lastUrl) }
            .toSet()
        runtime.storageController.getAllPermissions().accept({ permissions ->
            permissions.orEmpty().asSequence()
                .filter { !it.privateMode && it.contextId.isNullOrBlank() }
                .filter {
                    it.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ||
                        it.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
                }
                .forEach { permission ->
                    val origin = siteOriginForUrl(permission.uri) ?: return@forEach
                    val siteOverrideKey = "site_autoplay_geckoview_${Uri.encode(origin)}"
                    if (origin !in openOrigins && !prefs.contains(siteOverrideKey)) {
                        runtime.storageController.setPermission(
                            permission,
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT
                        )
                    }
                }
        }, { error ->
            runOnUiThread { toast("Could not refresh saved autoplay permissions") }
        })
    }

    private fun cefriumCookieNotice(message: String): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            text = message
            textSize = 13f
            setPadding(
                (20 * density).toInt(),
                (12 * density).toInt(),
                (20 * density).toInt(),
                (8 * density).toInt()
            )
        }
    }

    private fun showDefaultSiteSettings(engine: BrowserEngineType) {
        val definitions = listOf(Triple("javascript", "JavaScript", true)) +
            if (engine == BrowserEngineType.CEFRIUM) listOf(
                Triple("popups", "Pop-ups and redirects", false),
                Triple("autoplay", "Autoplay", false),
                Triple("ads", "Ads and tracker protection", true),
                Triple("images", "Images", true),
                Triple("cookies", "Cookies (browser requests only)", true),
                Triple("sound", "Sound", true)
            ) else listOf(
                Triple("popups", "Pop-ups and redirects", false),
                Triple("autoplay", "Autoplay", false),
                Triple("sound", "Sound", true)
            )
        val permissionDefaults = if (engine == BrowserEngineType.CEFRIUM)
            arrayOf("Location, camera, microphone, notifications, clipboard and NFC permissions")
        else arrayOf("Location, camera, microphone, notifications and device permissions")
        val cookieDefaults = if (engine == BrowserEngineType.GECKOVIEW) arrayOf("Cookies and site data", "Images") else emptyArray()
        val labels = definitions.map { (key, name, fallback) ->
            val enabled = prefs.getBoolean("site_default_${engine.name.lowercase()}_$key", fallback)
            if (key == "ads" && engine == BrowserEngineType.CEFRIUM) "$name · ${if (enabled) "On" else "Off"}"
            else "$name · ${if (enabled) "Allowed" else "Blocked"}"
        }.toTypedArray() + permissionDefaults + cookieDefaults
        val engineLabel = NovaEnginePresentation.label(engine)
        AlertDialog.Builder(this).setTitle("Default site settings · $engineLabel")
            .apply { if (engine == BrowserEngineType.CEFRIUM) setView(cefriumCookieNotice("Nova's Cefrium cookie rule applies to browser-owned requests only; service-worker requests may use profile cookies.")) }
            .setItems(labels) { _, which ->
                if (which == definitions.size) {
                    if (engine == BrowserEngineType.CEFRIUM) showDefaultCefriumPermissions() else showDefaultGeckoPermissions()
                    return@setItems
                }
                if (engine == BrowserEngineType.GECKOVIEW && which == definitions.size + permissionDefaults.size) {
                    showDefaultGeckoCookieSettings()
                    return@setItems
                }
                if (engine == BrowserEngineType.GECKOVIEW && which == definitions.size + permissionDefaults.size + 1) {
                    showDefaultGeckoImageSettings()
                    return@setItems
                }
                val (key, name, fallback) = definitions[which]
                val prefKey = if (key == "javascript") defaultJavaScriptPreferenceKey(engine)
                    else "site_default_${engine.name.lowercase()}_$key"
                val next = !prefs.getBoolean(prefKey, fallback)
                prefs.edit().putBoolean(prefKey, next).apply()
                tabs.filter { it.engineType == engine && !it.isPrivate && !it.isGuest }.forEach { active ->
                    when (key) {
                        "javascript" -> applyJavaScriptSetting(active, active.lastUrl, reload = false)
                        "autoplay" -> {
                            val effective = siteSettingAllowed(engine, active.lastUrl, "autoplay", false)
                            if (engine == BrowserEngineType.CEFRIUM) {
                                (active.browserEngine as? CefriumBrowserEngine)?.setAutoplayAllowed(effective)
                            } else {
                                val origin = cefriumOriginForUrl(active.lastUrl)
                                val hasSiteOverride = origin?.let { prefs.contains("site_autoplay_geckoview_${Uri.encode(it)}") } == true
                                if (!hasSiteOverride) active.contentPermissions.filter { it.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE || it.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE }
                                    .forEach { runtime.storageController.setPermission(it, if (effective) GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY) }
                            }
                        }
                        "ads" -> (active.browserEngine as? CefriumBrowserEngine)?.setAdBlockingEnabled(siteSettingAllowed(engine, active.lastUrl, "ads", true))
                        "images", "cookies" -> Unit // Browser request interceptor reads preferences; per-origin cookie hooks are not verified for profile-level service-worker requests.
                        "sound" -> {
                            val effective = siteSettingAllowed(engine, active.lastUrl, "sound", true)
                            if (engine == BrowserEngineType.CEFRIUM) (active.browserEngine as? CefriumBrowserEngine)?.setAudioMuted(!effective) else active.mediaSession?.muteAudio(!effective)
                        }
                    }
                    if (active.lastUrl.startsWith("http", true)) active.browserEngine?.reload()
                }
                if (engine == BrowserEngineType.GECKOVIEW && key == "autoplay") {
                    resetClosedGeckoAutoplayPermissions()
                }
                toast(if (key == "ads" && engine == BrowserEngineType.CEFRIUM) "$engineLabel default · Ads and tracker protection ${if (next) "on" else "off"}" else "$engineLabel default · $name ${if (next) "allowed" else "blocked"}")
                showDefaultSiteSettings(engine)
            }.setNegativeButton(getString(R.string.close), null).show()
    }

    private fun showAllSiteSettings() {
        val entries = storedSiteRuleEntries().toMutableSet()
        runtime.storageController.getAllPermissions().accept({ permissions ->
            runOnUiThread {
                permissions.orEmpty().filter { !it.privateMode && it.contextId.isNullOrBlank() }.forEach { permission ->
                    val uri = runCatching { Uri.parse(permission.uri) }.getOrNull() ?: return@forEach
                    val scheme = uri.scheme?.lowercase()
                    val host = uri.host?.lowercase()
                    if (scheme in listOf("http", "https") && !host.isNullOrBlank()) {
                        val origin = "${scheme}://$host${if (uri.port > 0) ":${uri.port}" else ""}"
                        entries.add(SiteRuleEntry(BrowserEngineType.GECKOVIEW, origin))
                    }
                }
                showAllSiteSettingsList(entries.toList())
            }
        }, {
            runOnUiThread { showAllSiteSettingsList(entries.toList()) }
        })
    }

    private fun showAllSiteSettingsList(entries: List<SiteRuleEntry>) {
        if (entries.isEmpty()) {
            AlertDialog.Builder(this).setTitle("All sites")
                .setMessage("No saved site-specific settings or permissions yet. Open a website and change one of its settings to add it here.")
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val sorted = entries.sortedWith(compareBy({ it.engine.name }, { it.origin }))
        val labels = sorted.map {
            "${it.origin}  ·  ${NovaEnginePresentation.label(it.engine)}"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("All sites")
            .setItems(labels) { _, which -> showSiteRuleDetails(sorted[which]) }
            .setNegativeButton(getString(R.string.close), null).show()
    }

    private fun showGeckoSitePermissions(entry: SiteRuleEntry) {
        runtime.storageController.getAllPermissions().accept({ permissions ->
            val matching = permissions.orEmpty().filter { permission ->
                if (permission.privateMode || !permission.contextId.isNullOrBlank()) return@filter false
                runCatching {
                    val uri = Uri.parse(permission.uri)
                    val origin = "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
                    origin == entry.origin
                }.getOrDefault(false)
            }
            runOnUiThread {
                if (matching.isEmpty()) {
                    toast("No saved Nova Compatibility permissions for ${Uri.parse(entry.origin).host}")
                    showSiteRuleDetails(entry)
                } else {
                    val labels = matching.map { permission ->
                        "${sitePermissionLabel(permission.permission)} · " + when (permission.value) {
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW -> "Allowed"
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY -> "Blocked"
                            else -> "Ask"
                        }
                    }.toTypedArray()
                    AlertDialog.Builder(this).setTitle("Permissions · ${Uri.parse(entry.origin).host}")
                        .setItems(labels) { _, which ->
                            val permission = matching[which]
                            val current = when (permission.value) {
                                GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY -> 1
                                GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW -> 2
                                else -> 0
                            }
                            AlertDialog.Builder(this).setTitle(sitePermissionLabel(permission.permission))
                                .setSingleChoiceItems(arrayOf("Ask every time", "Block", "Allow"), current) { dialog, selected ->
                                    val next = when (selected) {
                                        1 -> GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                                        2 -> GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                                        else -> GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT
                                    }
                                    runtime.storageController.setPermission(permission, next)
                                    dialog.dismiss()
                                    showGeckoSitePermissions(entry)
                                }
                                .setNegativeButton(getString(R.string.close), null)
                                .show()
                        }.setNegativeButton(getString(R.string.close), null).show()
                }
            }
        }, { error ->
            runOnUiThread { toast("Could not read saved permissions: ${error?.localizedMessage ?: "Unknown error"}") }
        })
    }

    private fun siteRuleKey(entry: SiteRuleEntry, setting: String): String {
        val encoded = Uri.encode(entry.origin)
        return if (setting == "javascript") {
            "javascript_enabled_${entry.engine.name.lowercase()}_$encoded"
        } else {
            "site_${setting}_${entry.engine.name.lowercase()}_$encoded"
        }
    }

    private fun siteRuleKeys(entry: SiteRuleEntry): Set<String> {
        val engine = entry.engine.name.lowercase()
        val encoded = Uri.encode(entry.origin)
        return buildSet {
            add("javascript_enabled_${engine}_$encoded")
            add("site_popups_${engine}_$encoded")
            add("site_autoplay_${engine}_$encoded")
            add("site_sound_${engine}_$encoded")
            if (entry.engine == BrowserEngineType.CEFRIUM) {
                add("site_ads_${engine}_$encoded")
                add("site_images_${engine}_$encoded")
                add("site_cookies_${engine}_$encoded")
                add(cefriumSitePermissionsKey(entry.origin))
            } else if (entry.engine == BrowserEngineType.GECKOVIEW) {
                prefs.all.keys.filter {
                    it.startsWith("site_permission_geckoview_") && it.endsWith("_$encoded")
                }.forEach { add(it) }
            }
        }
    }

    private fun showSiteRuleDetails(entry: SiteRuleEntry) {
        val host = Uri.parse(entry.origin).host ?: entry.origin
        val engineLabel = NovaEnginePresentation.label(entry.engine)
        val definitions = listOf(Triple("javascript", "JavaScript", true)) +
            if (entry.engine == BrowserEngineType.CEFRIUM) listOf(
                Triple("popups", "Pop-ups and redirects", false),
                Triple("autoplay", "Autoplay", false),
                Triple("ads", "Ads and tracker protection", true),
                Triple("images", "Images", true),
                Triple("cookies", "Cookies (browser requests only)", true),
                Triple("sound", "Sound", true)
            ) else listOf(
                Triple("popups", "Pop-ups and redirects", false),
                Triple("autoplay", "Autoplay", false),
                Triple("sound", "Sound", true)
            )
        fun label(definition: Triple<String, String, Boolean>): String {
            val value = if (definition.first == "javascript") {
                isJavaScriptAllowed(entry.engine, entry.origin)
            } else {
                siteSettingAllowed(entry.engine, entry.origin, definition.first, definition.third)
            }
            return "${definition.second} · ${if (value) "Allowed" else "Blocked"}"
        }
        val permissionsOffset = if (entry.engine == BrowserEngineType.GECKOVIEW || entry.engine == BrowserEngineType.CEFRIUM) 1 else 0
        val resetIndex = definitions.size + permissionsOffset
        val permissionAction = when (entry.engine) {
            BrowserEngineType.GECKOVIEW -> arrayOf("View saved permissions")
            BrowserEngineType.CEFRIUM -> arrayOf("Site permissions")
            else -> emptyArray()
        }
        val labels = definitions.map(::label).toTypedArray() + permissionAction +
            arrayOf("Reset this site's settings", "Clear this site's data")
        AlertDialog.Builder(this).setTitle("$host · $engineLabel")
            .apply { if (entry.engine == BrowserEngineType.CEFRIUM) setView(cefriumCookieNotice("Nova's per-origin cookie rule applies to browser-owned requests only; service-worker requests may use profile cookies.")) }
            .setItems(labels) { _, which ->
                when {
                    which < definitions.size -> {
                        val definition = definitions[which]
                        val key = siteRuleKey(entry, definition.first)
                        val effective = if (definition.first == "javascript") isJavaScriptAllowed(entry.engine, entry.origin)
                            else siteSettingAllowed(entry.engine, entry.origin, definition.first, definition.third)
                        val next = !effective
                        prefs.edit().putBoolean(key, next).apply()
                        tabs.filter { active ->
                            active.engineType == entry.engine && !active.isGuest && !active.isPrivate &&
                                runCatching {
                                    val uri = Uri.parse(active.lastUrl)
                                    "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}" == entry.origin
                                }.getOrDefault(false)
                        }.forEach { active ->
                            when (definition.first) {
                                "javascript" -> applyJavaScriptSetting(active, active.lastUrl, reload = false)
                                "autoplay" -> if (entry.engine == BrowserEngineType.CEFRIUM) {
                                    (active.browserEngine as? CefriumBrowserEngine)?.setAutoplayAllowed(next)
                                } else {
                                    active.contentPermissions.filter { it.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE || it.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE }
                                        .forEach { runtime.storageController.setPermission(it, if (next) GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY) }
                                }
                                "ads" -> (active.browserEngine as? CefriumBrowserEngine)?.setAdBlockingEnabled(next)
                                "images", "cookies" -> Unit // Browser-owned requests only; per-origin cookie callbacks are not relied on for profile-level service-worker requests.
                                "sound" -> if (entry.engine == BrowserEngineType.CEFRIUM) (active.browserEngine as? CefriumBrowserEngine)?.setAudioMuted(!next) else active.mediaSession?.muteAudio(!next)
                            }
                            active.browserEngine?.reload()
                        }
                        toast("$host · ${definition.second} ${if (next) "allowed" else "blocked"}")
                        showSiteRuleDetails(entry)
                    }
                    entry.engine == BrowserEngineType.GECKOVIEW && which == definitions.size -> {
                        showGeckoSitePermissionHub(entry.origin)
                    }
                    entry.engine == BrowserEngineType.CEFRIUM && which == definitions.size -> {
                        showCefriumSitePermissionSettings(entry.origin)
                    }
                    which == resetIndex -> {
                        val editor = prefs.edit()
                        siteRuleKeys(entry).forEach(editor::remove)
                        editor.apply()
                        toast("Site settings reset for $host")
                        showAllSiteSettings()
                    }
                    else -> confirmClearSingleSiteData(entry)
                }
            }.setNegativeButton(getString(R.string.close), null).show()
    }

    private fun confirmClearSingleSiteData(entry: SiteRuleEntry) {
        val host = Uri.parse(entry.origin).host ?: return
        AlertDialog.Builder(this).setTitle("Clear site data?")
            .setMessage("Delete cookies and site storage for $host in ${entry.engine.name}. The other engine's profile is not affected.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear data") { _, _ ->
                val editor = prefs.edit()
                siteRuleKeys(entry).forEach(editor::remove)
                editor.apply()
                if (entry.engine == BrowserEngineType.CEFRIUM) {
                    runCatching {
                        CefriumProfile.get(enginePreferences.cefriumProfileName()).clearSiteData(entry.origin) {
                            runOnUiThread { toast("Cefrium site data cleared for $host") }
                        }
                    }.onFailure { toast("Could not clear Cefrium site data: ${it.localizedMessage ?: "unknown error"}") }
                } else {
                    runtime.storageController.clearDataFromHost(host, StorageController.ClearFlags.SITE_DATA)
                        .accept({ runOnUiThread { toast("GeckoView site data cleared for $host") } },
                            { error -> runOnUiThread { toast("Could not clear site data: ${error?.localizedMessage ?: "Unknown error"}") } })
                }
            }.show()
    }

    private fun confirmClearAllSiteData(engine: BrowserEngineType) {
        if (engine == BrowserEngineType.CEFRIUM) {
            clearCefriumProfileData()
            return
        }
        AlertDialog.Builder(this).setTitle("Clear all GeckoView site data?")
            .setMessage("This deletes cookies, site storage, caches and saved site permissions in the GeckoView profile. Open pages may recreate data after clearing.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear all data") { _, _ ->
                runtime.storageController.clearData(StorageController.ClearFlags.SITE_DATA)
                    .accept({ runOnUiThread { toast("GeckoView site data cleared") } },
                        { error -> runOnUiThread { toast("Could not clear GeckoView data: ${error?.localizedMessage ?: "Unknown error"}") } })
            }.show()
    }

    private fun siteOriginForUrl(url: String): String? {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.lowercase() ?: return null
        return "$scheme://$host${if (uri.port > 0) ":${uri.port}" else ""}"
    }

    private fun showSiteControls() {
        if (denyGuestAccess("Site controls")) return
        val tab = currentTab() ?: return
        val siteUrlAtOpen = tab.lastUrl
        val originAtOpen = siteOriginForUrl(siteUrlAtOpen)
        val engineAtOpen = tab.engineType
        val host = runCatching { Uri.parse(siteUrlAtOpen).host }.getOrNull()
        if (host.isNullOrBlank() || originAtOpen == null) { toast("Open a website to manage its permissions"); return }
        fun ensureOriginUnchanged(): Boolean {
            if (siteOriginForUrl(tab.lastUrl) == originAtOpen && tab.engineType == engineAtOpen) return true
            toast("This tab navigated or changed engines. Reopen Site controls.")
            return false
        }
        val jsLabel = "JavaScript · " + if (isJavaScriptAllowed(tab.engineType, siteUrlAtOpen)) "Allowed" else "Blocked"
        val popupLabel = "Pop-ups and redirects · " + if (siteSettingAllowed(tab.engineType, siteUrlAtOpen, "popups", false)) "Allowed" else "Blocked"
        val autoplayLabel = "Autoplay · " + if (siteSettingAllowed(tab.engineType, siteUrlAtOpen, "autoplay", false)) "Allowed" else "Blocked"
        val soundLabel = "Sound · " + if (siteSettingAllowed(tab.engineType, siteUrlAtOpen, "sound", true)) "Allowed" else "Muted"
        if (tab.engineType == BrowserEngineType.CEFRIUM) {
            val origin = originAtOpen
            val adsLabel = "Ads and tracker protection · " + if (siteSettingAllowed(tab.engineType, siteUrlAtOpen, "ads", true)) "On" else "Off"
            val imagesLabel = "Images · " + if (siteSettingAllowed(tab.engineType, siteUrlAtOpen, "images", true)) "Allowed" else "Blocked"
            val cookiesLabel = "Cookies (browser requests only) · " + if (siteSettingAllowed(tab.engineType, siteUrlAtOpen, "cookies", true)) "Allowed" else "Blocked"
            AlertDialog.Builder(this).setTitle("Site controls · $host")
                .setView(cefriumCookieNotice("Nova's Cefrium cookie rule applies to browser-owned requests only. Service-worker requests may use profile cookies, so this is not complete cookie isolation."))
                .setItems(arrayOf(jsLabel, popupLabel, autoplayLabel, adsLabel, imagesLabel, cookiesLabel, "Sound · " + if (siteSettingAllowed(tab.engineType, siteUrlAtOpen, "sound", true)) "Allowed" else "Muted", "Clear this site's cookies and storage", "Manage location, camera, microphone, notifications, clipboard and NFC")) { _, which ->
                    if (!ensureOriginUnchanged()) return@setItems
                    if (which == 0) {
                        toggleJavaScriptForCurrentSite(tab, originAtOpen, engineAtOpen)
                    } else if (which == 1) {
                        toggleSiteSetting(tab, "popups", false, originAtOpen, engineAtOpen)
                    } else if (which == 2) {
                        toggleSiteSetting(tab, "autoplay", false, originAtOpen, engineAtOpen)
                    } else if (which == 3) {
                        toggleSiteSetting(tab, "ads", true, originAtOpen, engineAtOpen)
                    } else if (which == 4) {
                        toggleSiteSetting(tab, "images", true, originAtOpen, engineAtOpen)
                    } else if (which == 5) {
                        toggleSiteSetting(tab, "cookies", true, originAtOpen, engineAtOpen)
                    } else if (which == 6) {
                        toggleSiteSetting(tab, "sound", true, originAtOpen, engineAtOpen)
                    } else if (which == 7) {
                        AlertDialog.Builder(this).setMessage("Clear cookies, local storage and service workers for $host? Other sites are not affected.")
                            .setPositiveButton("Clear site data") { _, _ ->
                                if (!ensureOriginUnchanged()) return@setPositiveButton
                                runCatching {
                                    CefriumProfile.get(enginePreferences.cefriumProfileName()).clearSiteData(origin) {
                                        toast("Cefrium site data cleared for $host")
                                    }
                                }.onFailure { toast("Could not clear Cefrium site data: ${it.localizedMessage ?: "unknown error"}") }
                            }.setNegativeButton("Cancel", null).show()
                    } else if (which == 8) showCefriumSitePermissionSettings(origin)
                }.setNegativeButton("Close", null).show()
            return
        }
        val perms = tab.contentPermissions.filter {
            it.permission != GeckoSession.PermissionDelegate.PERMISSION_TRACKING &&
                it.permission != GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE &&
                it.permission != GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
        }
        val rows = perms.map { "${sitePermissionLabel(it.permission)} · ${when (it.value) { GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW -> "Allowed"; GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY -> "Blocked"; else -> "Ask" }}" }.toTypedArray()
        val controls = arrayOf(jsLabel, popupLabel, autoplayLabel, soundLabel)
        val options = controls + rows + "Camera and microphone permissions" + "Allow / block trackers for this site" + "Clear this site's cookies and storage"
        AlertDialog.Builder(this).setTitle("Site controls · $host")
            .setItems(options) { _, which ->
                if (!ensureOriginUnchanged()) return@setItems
                when {
                    which == 0 -> toggleJavaScriptForCurrentSite(tab)
                    which == 1 -> toggleSiteSetting(tab, "popups", false)
                    which == 2 -> toggleSiteSetting(tab, "autoplay", false)
                    which == 3 -> toggleSiteSetting(tab, "sound", true)
                    which < controls.size + perms.size -> {
                        val perm = perms[which - controls.size]
                        val current = when (perm.value) {
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY -> 1
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW -> 2
                            else -> 0
                        }
                        AlertDialog.Builder(this).setTitle(sitePermissionLabel(perm.permission))
                            .setSingleChoiceItems(arrayOf("Ask every time", "Block", "Allow"), current) { dialog, selected ->
                                if (!ensureOriginUnchanged()) { dialog.dismiss(); return@setSingleChoiceItems }
                                val next = when (selected) {
                                    1 -> GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                                    2 -> GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                                    else -> GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT
                                }
                                runtime.storageController.setPermission(perm, next)
                                dialog.dismiss()
                                toast("Site permission updated · reload the page")
                            }
                            .setNegativeButton(getString(R.string.close), null)
                            .show()
                    }
                    which == controls.size + perms.size -> showGeckoMediaPermissionSettings(originAtOpen)
                    which == controls.size + perms.size + 1 -> showShieldSettings()
                    else -> AlertDialog.Builder(this).setMessage("Clear cookies, caches, and site storage for $host? Other sites are not affected.")
                        .setPositiveButton("Clear site data") { _, _ ->
                            if (!ensureOriginUnchanged()) return@setPositiveButton
                            runtime.storageController.clearDataFromHost(host, StorageController.ClearFlags.SITE_DATA)
                                .accept({ runOnUiThread { toast("Site data cleared for $host") } }, { error -> runOnUiThread { toast("Could not clear site data: ${error?.localizedMessage ?: "Unknown error"}") } })
                        }.setNegativeButton("Cancel", null).show()
                }
            }.setNegativeButton("Close", null).show()
    }

    private fun showAccessibilitySettings() {
        val factors = floatArrayOf(0.9f, 1.0f, 1.15f, 1.3f, 1.5f)
        val labels = arrayOf("90%", "100%", "115%", "130%", "150%")
        val selected = prefs.getInt("font_scale_index", 1).coerceIn(factors.indices)
        AlertDialog.Builder(this).setTitle("Accessibility · page text size")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                prefs.edit().putInt("font_scale_index", which).apply()
                runtime.settings.setFontSizeFactor(factors[which])
                runtime.settings.setForceUserScalableEnabled(true)
                toast("Page text size set to ${labels[which]}")
                dialog.dismiss()
            }.setNegativeButton("Close", null).show()
    }

    private fun exportHistory() {
        if (denyGuestAccess("History export")) return
        val array = JSONArray()
        readHistory().sortedByDescending { it.visitedAt }.forEach { array.put(JSONObject().put("url", it.url).put("title", it.title).put("visitedAt", it.visitedAt)) }
        pendingHistoryExport = array.toString(2)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"; putExtra(Intent.EXTRA_TITLE, "nova-history.json")
        }
        startActivityForResult(intent, historyExportRequestCode)
    }

    private fun showBrowserEngineSettings() {
        val actions = arrayOf(
            "Choose the Normal-tab engine",
            "Switch this tab's engine",
            "Remember this site's engine",
            "Remember related sign-in domains"
        )
        AlertDialog.Builder(this).setCustomTitle(TextView(this).apply {
            text = "Nova browser engine\n\nNova automatically chooses the best fit for each page. Private and Guest tabs always use Nova Compatibility. Nova Core is used only when enabled protections and site requirements allow it. Website data stays separate between engines."
            setPadding(dp(24), dp(16), dp(24), dp(8))
            textSize = 14f
        }).setItems(actions) { _, which ->
                when (which) {
                    0 -> chooseNormalEngineMode()
                    1 -> switchCurrentTabEngine()
                    2 -> rememberCurrentSiteEngine()
                    3 -> rememberRelatedSignInDomains()
                }
            }.setNegativeButton(getString(R.string.close), null).show()
    }

    private fun chooseNormalEngineMode() {
        val modes = BrowserEngineMode.values()
        val labels = BrowserEngineMode.values().map(NovaEnginePresentation::normalModeLabel).toTypedArray()
        val selected = modes.indexOf(enginePreferences.normalMode()).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Normal-tab engine preference")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                val mode = modes[which]
                enginePreferences.setNormalMode(mode)
                currentTab()?.let { tab ->
                    tab.engineFallbackReason = null
                    val desired = resolveEngineForTab(tab)
                    if (desired != tab.engineType) {
                        closeTabRuntime(tab)
                        tab.engineType = desired
                        if (desired != BrowserEngineType.CEFRIUM) tab.cefriumNavigationState = null
                        tab.needsReload = tab.lastUrl != HOME_URL
                        activateTab(tab)
                    }
                }
                toast("Normal-tab engine preference: ${NovaEnginePresentation.normalModeLabel(mode)}")
                dialog.dismiss()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun switchCurrentTabEngine() {
        val tab = currentTab() ?: return
        if (tab.isPrivate || tab.isGuest) { toast("Private and Guest tabs always use Nova Compatibility"); return }
        val target = if (tab.engineType == BrowserEngineType.CEFRIUM) BrowserEngineType.GECKOVIEW else BrowserEngineType.CEFRIUM
        if (target == BrowserEngineType.CEFRIUM) {
            val previousFallback = tab.engineFallbackReason
            tab.engineFallbackReason = null // An explicit switch is also the user's retry after a prior runtime failure.
            val reason = cefriumCompatibilityReason(tab)
            if (reason != null) {
                tab.engineFallbackReason = previousFallback
                toast("${NovaEnginePresentation.label(BrowserEngineType.CEFRIUM)} unavailable: ${NovaEnginePresentation.userFacingReason(reason)}")
                return
            }
        }
        val targetLabel = NovaEnginePresentation.label(target)
        AlertDialog.Builder(this).setTitle("Switch to $targetLabel?")
            .setMessage("The tab will reload. Cookies, local storage and active sessions are not migrated between engines; you may need to sign in again.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Switch") { _, _ ->
                if (target == BrowserEngineType.GECKOVIEW && pendingGeckoDataClearActive && !tab.isPrivate && !tab.isGuest) {
                    toast("Wait for GeckoView data deletion to finish before switching this tab")
                    return@setPositiveButton
                }
                closeTabRuntime(tab)
                if (target == BrowserEngineType.CEFRIUM && tab.engineType != BrowserEngineType.CEFRIUM) tab.cefriumNavigationState = null
                tab.explicitEngineChoice = target
                tab.engineType = target
                if (target != BrowserEngineType.CEFRIUM) tab.cefriumNavigationState = null
                tab.needsReload = tab.lastUrl != HOME_URL
                tab.engineFallbackReason = null
                activateTab(tab)
                persistTabs()
            }.show()
    }

    private fun rememberCurrentSiteEngine() {
        val tab = currentTab() ?: return
        if (tab.isPrivate || tab.isGuest) { toast("Site engine preferences are not saved from Private or Guest tabs"); return }
        val host = Uri.parse(tab.lastUrl).host ?: run { toast("Open a website first"); return }
        val saved = enginePreferences.setSiteAffinity(tab.lastUrl, tab.engineType)
        toast(if (saved) "${NovaEnginePresentation.label(tab.engineType)} remembered for $host" else "Could not save this site's engine preference")
    }

    private fun rememberRelatedSignInDomains() {
        val tab = currentTab() ?: return
        if (tab.isPrivate || tab.isGuest) { toast("Site engine preferences are not saved from Private or Guest tabs"); return }
        val host = Uri.parse(tab.lastUrl).host ?: run { toast("Open a website first"); return }
        val input = EditText(this).apply {
            hint = "accounts.example.com, login.example.com"
            setText(host)
            setSingleLine(false)
        }
        AlertDialog.Builder(this).setTitle("Related sign-in domains")
            .setMessage("Enter exact hostnames separated by commas. Nova will remember the current engine for those hosts only; no wildcard domains are saved.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val hosts = input.text.toString().split(',').map { it.trim() }.filter { it.isNotBlank() }
                val count = enginePreferences.setRelatedSiteAffinities(hosts, tab.engineType)
                toast("Saved engine preference for $count exact host(s)")
            }.show()
    }

    private fun showAppearanceSettings() {
        val modes = NovaThemeMode.values()
        val labels = arrayOf("Follow device", "Light", "Dark")
        val current = modes.indexOf(NovaThemeManager.currentMode(this)).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Nova appearance")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                NovaThemeManager.setMode(this, modes[which])
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettings() {
        if (TorGuestRoutingController.isStopping() && currentTab()?.isGuest != true) {
            showBottomSheetMenu("Guest Tor recovery", listOf(
                "Retry Guest routing reset" to {
                    TorGuestRoutingController.stop { runOnUiThread { restoreAfterGuestTorStop() } }
                },
                "View Guest Tor status" to { openOrbotTorRouting() }
            ), "Normal and Private tabs remain paused until Nova confirms that Guest routing is disabled.")
            return
        }
        if (currentTab()?.isGuest == true) {
            showBottomSheetMenu(getString(R.string.guest_settings), listOf(
                getString(R.string.tor_routing) to { openOrbotTorRouting() },
                getString(R.string.exit_guest_mode) to { confirmExitGuestMode() }
            ), getString(R.string.guest_settings_message))
            return
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        var settingsDialog: AlertDialog? = null
        fun section(title: String, actions: List<Pair<String, () -> Unit>>) {
            content.addView(TextView(this).apply {
                text = title
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.nova_text_secondary))
                setPadding(dp(8), dp(14), dp(8), dp(6))
            })
            actions.forEach { (label, action) ->
                content.addView(actionButton(label) { settingsDialog?.dismiss(); action() }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        section(getString(R.string.settings_privacy), listOf(
            getString(R.string.remote_search_suggestions, if (prefs.getBoolean("remote_search_suggestions", false)) "On" else "Off") to {
                val enabled = !prefs.getBoolean("remote_search_suggestions", false)
                prefs.edit().putBoolean("remote_search_suggestions", enabled).apply()
                if (!enabled) cancelPendingRemoteSuggestions()
                toast(getString(if (enabled) R.string.remote_suggestions_enabled else R.string.remote_suggestions_disabled))
                if (::addressBar.isInitialized && addressBar.hasFocus()) updateAddressSuggestions(addressBar.text?.toString().orEmpty())
            },
            getString(R.string.clear_history) to { clearBrowsingHistory() },
            "Clear browsing data" to { showClearBrowsingData() },
            "Appearance" to { showAppearanceSettings() },
            "Clear Cefrium profile data" to { clearCefriumProfileData() },
            getString(R.string.close_private_guest_tabs) to { closePrivateAndGuestTabs() },
            "Site Settings" to { showSiteSettingsHub() },
            getString(R.string.nova_shield) to { showShieldSettings() },
            getString(R.string.password_manager) to { showPasswordVault() }
        ))
        section(getString(R.string.settings_network), listOf(
            "Browser engine" to { showBrowserEngineSettings() },
            getString(R.string.protected_dns) to { showProtectedDnsSettings() },
            getString(R.string.vpn_firewall) to { startActivity(Intent(this, NovaVpnFirewallActivity::class.java)) }
        ))
        section(getString(R.string.settings_sync), listOf(
            getString(R.string.cloud_sync) to { showCloudAccount() }
        ))
        section(getString(R.string.settings_appearance), listOf(
            getString(R.string.choose_search_engine, searchEngine) to { chooseSearchEngine() },
            getString(R.string.web_page_color_scheme) to { chooseWebColorScheme() },
            getString(R.string.toggle_home_shortcuts) to {
                val show = !prefs.getBoolean("show_home_shortcuts", true)
                prefs.edit().putBoolean("show_home_shortcuts", show).apply()
                homeShortcuts.visibility = if (show) View.VISIBLE else View.GONE
                toast(if (show) getString(R.string.shortcuts_shown) else getString(R.string.shortcuts_hidden))
            },
            getString(R.string.reader_mode) to { if (currentTab()?.readerOriginalUrl != null) exitReaderMode() else openReaderMode() },
            getString(R.string.accessibility_settings) to { showAccessibilitySettings() }
        ))
        val aboutActions = mutableListOf<Pair<String, () -> Unit>>(
            getString(R.string.about_nova) to {
                val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: getString(R.string.version_unknown)
                AlertDialog.Builder(this).setTitle(getString(R.string.about_nova)).setMessage(getString(R.string.about_nova_version, version))
                    .setPositiveButton(android.R.string.ok, null).show()
            }
        )
        if (BuildConfig.DEBUG) aboutActions.add("Cefrium Engine Lab" to {
            runCatching { startActivity(Intent().setClassName(packageName, "$packageName.CefriumEngineProbeActivity")) }
                .onFailure { toast("Engine Lab is unavailable in this build") }
        })
        section(getString(R.string.settings_about), aboutActions)
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.settings_title)).setView(scroll)
            .setNegativeButton(getString(R.string.close), null).create()
        settingsDialog = dialog
        dialog.show()
    }


    private fun confirmFireClear() {
        if (currentTab()?.isPrivate == true) {
            toast("Use Fire from a Normal tab")
            return
        }
        if (denyGuestAccess("Fire")) return
        if (!NovaFireClearPolicy.canRun(tabs.any { it.isPrivate || it.isGuest })) {
            toast("Close all Private and Guest tabs before using Fire. Their session data will not be touched.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Fire")
            .setMessage("Fire closes Normal tabs and clears history, cookies, cache, and Cefrium site data. Close all Private and Guest tabs before continuing. Saved passwords and downloaded files are retained.")
            .setPositiveButton("Fire") { _, _ -> applyFireClear() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyFireClear() {
        if (!NovaFireClearPolicy.canRun(tabs.any { it.isPrivate || it.isGuest })) {
            toast("Close all Private and Guest tabs before using Fire. Their session data will not be touched.")
            return
        }
        if (pendingFireClearOperationId != null) {
            toast("Fire is already clearing browser data")
            return
        }
        val selection = BrowsingDataSelection.fireDefaults()
        val operationId = java.util.UUID.randomUUID().toString()
        pendingFireClearOperationId = operationId
        pendingFireClearCompletion = null
        // The completion is routed to whichever MainActivity is current when Gecko finishes.
        clearGeckoWebsiteData(
            selection.geckoFlags(),
            activeTabId = null,
            fireOperationId = operationId
        ) { _, _ -> }
    }

    private fun consumePendingFireClearCompletion() {
        val completion = pendingFireClearCompletion ?: return
        if (pendingFireClearOperationId != completion.operationId) {
            pendingFireClearCompletion = null
            return
        }
        pendingFireClearCompletion = null
        continueFireClearAfterGecko(completion)
    }

    private fun finishFireClearOperation(operationId: String) {
        if (pendingFireClearOperationId != operationId) return
        pendingFireClearCompletion = null
        pendingFireClearOperationId = null
        // Release deferred tab/external-link work only after every Fire cleanup stage is terminal.
        resumePendingGeckoDataClearTab()
        retryPendingExternalLink()
    }

    private fun continueFireClearAfterGecko(completion: FireClearCompletion) {
        if (pendingFireClearOperationId != completion.operationId) return
        if (!completion.succeeded) {
            finishFireClearOperation(completion.operationId)
            toast("Fire stopped: GeckoView data was not confirmed cleared. ${completion.message.orEmpty()}")
            return
        }
        val selection = BrowsingDataSelection.fireDefaults()
        clearCefriumProfileDataNow(activeTabId = null) { cefriumSucceeded, cefriumMessage ->
            if (pendingFireClearOperationId != completion.operationId) return@clearCefriumProfileDataNow
            if (!cefriumSucceeded) {
                finishFireClearOperation(completion.operationId)
                toast("GeckoView data was cleared, but Cefrium data could not be confirmed: ${cefriumMessage.orEmpty()}")
                return@clearCefriumProfileDataNow
            }
            if (selection.closeNormalTabs) closeNormalTabsForFire()
            if (selection.history) clearAllBrowsingHistory()
            if (tabs.isEmpty() && !createTab(false, HOME_URL)) {
                ensureHomeTabRecord()
                persistTabs()
            }
            finishFireClearOperation(completion.operationId)
            toast("Fire complete · Normal tabs, history and GeckoView site data cleared. ${cefriumMessage ?: "Cefrium profile refreshed"}")
        }
    }

    /** Remove Normal tabs in one batch without writing any URL to recently-closed history. */
    private fun closeNormalTabsForFire() {
        val removed = tabs.filter { !it.isPrivate && !it.isGuest }
        tabs.removeAll { !it.isPrivate && !it.isGuest }
        removed.forEach { tab ->
            aiPageText.remove(tab.id)
            aiPageUrl.remove(tab.id)
            closeTabRuntime(tab)
            tab.thumbnail?.recycle()
            tab.thumbnail = null
        }
        activeIndex = if (tabs.isEmpty()) 0 else activeIndex.coerceIn(tabs.indices)
        cleanupGuestContextIfIdle()
        if (tabs.isEmpty()) {
            if (!createTab(false, HOME_URL)) ensureHomeTabRecord()
        } else {
            switchTab(activeIndex)
        }
        persistTabs()
    }

    private fun showClearBrowsingData() {
        if (currentTab()?.isPrivate == true) {
            toast("Clear owner browsing data from a Normal tab")
            return
        }
        if (denyGuestAccess("Clear browsing data")) return
        val history = CheckBox(this).apply { text = "Browsing history · GeckoView and Cefrium"; isChecked = true }
        val geckoSiteData = CheckBox(this).apply { text = "Cookies, site storage and authentication · GeckoView"; isChecked = true }
        val geckoCache = CheckBox(this).apply { text = "Browser cache · GeckoView"; isChecked = true }
        val geckoSettings = CheckBox(this).apply { text = "Site permissions and settings · GeckoView"; isChecked = false }
        val cefriumData = CheckBox(this).apply { text = "Cookies, cache, local storage and service workers · Cefrium"; isChecked = false }
        val passwords = CheckBox(this).apply { text = "Saved passwords · encrypted Nova vault"; isChecked = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(history)
            addView(geckoSiteData)
            addView(geckoCache)
            addView(geckoSettings)
            addView(cefriumData)
            addView(passwords)
            addView(TextView(this@MainActivity).apply {
                text = "Downloaded files and download records are not deleted. Private and Guest browsing data is kept separate and is not included."
                textSize = 12f
                setPadding(0, dp(12), 0, 0)
            })
        }
        AlertDialog.Builder(this).setTitle("Clear browsing data")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Clear selected") { _, _ ->
                val selection = BrowsingDataSelection(
                    history = history.isChecked,
                    siteData = geckoSiteData.isChecked,
                    cache = geckoCache.isChecked,
                    siteSettings = geckoSettings.isChecked,
                    cefriumProfileData = cefriumData.isChecked,
                    savedPasswords = passwords.isChecked
                )
                if (!selection.hasSelection()) {
                    toast("Select at least one data type")
                    return@setPositiveButton
                }
                val activeTabId = currentTab()?.id
                if (selection.history) {
                    clearAllBrowsingHistory()
                    toast("Browsing history cleared")
                }
                if (selection.savedPasswords) {
                    runCatching { passwordVault.clear() }
                        .onSuccess { toast("Encrypted password vault cleared") }
                        .onFailure { toast("Could not clear password vault: ${it.localizedMessage ?: "Unknown error"}") }
                }
                val overridesCleared = !selection.siteSettings || clearGeckoSiteOverrides()
                if (!overridesCleared) toast("Could not clear all Nova GeckoView site overrides")
                val flags = selection.geckoFlags()
                val finishCefrium = {
                    if (selection.cefriumProfileData) {
                        clearCefriumProfileDataNow(activeTabId) { succeeded, message ->
                            if (succeeded) toast(message ?: "Cefrium profile data cleared")
                            else toast("Cefrium data clear failed: ${message ?: "Unknown error"}")
                        }
                    } else if (flags == 0L && !selection.history && !selection.savedPasswords) {
                        toast("No selected browser data was cleared")
                    }
                }
                if (flags != 0L) {
                    // Complete Cefrium clearing before the asynchronous Gecko callback can outlive this Activity.
                    finishCefrium()
                    clearGeckoWebsiteData(flags, activeTabId) { succeeded, message ->
                        if (succeeded && overridesCleared) toast("Selected GeckoView website data cleared")
                        else if (!succeeded) toast("GeckoView data clear failed: ${message ?: "Unknown error"}")
                        else toast("GeckoView data cleared, but some Nova site overrides remain")
                    }
                } else {
                    finishCefrium()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearAllBrowsingHistory() {
        prefs.edit()
            .putLong("history_cleared_at", System.currentTimeMillis())
            .remove("history")
            .remove("history_v2")
            .remove("nova_recently_closed_tabs")
            .apply()
    }

    private fun clearGeckoSiteOverrides(): Boolean {
        val geckoPrefixes = listOf(
            "site_permission_geckoview_",
            "site_autoplay_geckoview_",
            "site_popups_geckoview_",
            "site_ads_geckoview_",
            "site_sound_geckoview_",
            "javascript_enabled_geckoview_"
        )
        val keys = prefs.all.keys.filter { key -> geckoPrefixes.any(key::startsWith) }
        if (keys.isEmpty()) return true
        val editor = prefs.edit()
        keys.forEach { editor.remove(it) }
        return editor.commit()
    }

    private fun resumePendingGeckoDataClearTab() {
        if (!NovaFireClearPolicy.canResumeAfterGeckoClear(
                geckoClearActive = pendingGeckoDataClearActive,
                fireClearActive = pendingFireClearOperationId != null
            )) return
        val tabId = pendingGeckoDataClearResumeTabId
        pendingGeckoDataClearResumeTabId = null
        if (tabId != null) {
            val index = tabs.indexOfFirst { it.id == tabId }
            if (currentTab()?.id == tabId && index >= 0 &&
                tabs[index].engineType == BrowserEngineType.GECKOVIEW &&
                !tabs[index].isPrivate && !tabs[index].isGuest) {
                switchTab(index)
            }
        }
        retryPendingExternalLink()
    }

    private fun clearGeckoWebsiteData(
        flags: Long,
        activeTabId: String?,
        fireOperationId: String? = null,
        onFinished: (Boolean, String?) -> Unit
    ) {
        if (pendingGeckoDataClearActive) {
            if (fireOperationId != null) dispatchFireClearCompletion(FireClearCompletion(fireOperationId, false, "A GeckoView data deletion is already in progress"))
            else onFinished(false, "A GeckoView data deletion is already in progress")
            return
        }
        // GeckoView clearData(flags) is runtime-wide; it cannot scope the request to Normal.
        // Do not run it while excluded Guest/Private contexts exist, or their data would be erased.
        if (tabs.any { it.isGuest || it.isPrivate }) {
            val message = "GeckoView cannot clear only Normal data while Guest or Private sessions are open. Keep those sessions open and try again later; their data has not been touched."
            if (fireOperationId != null) dispatchFireClearCompletion(FireClearCompletion(fireOperationId, false, message))
            else onFinished(false, message)
            return
        }
        if (splitTabId != null) exitSplitScreen()
        val affectedTabs = tabs.filter {
            !it.isPrivate && !it.isGuest &&
                (it.session != null || engineSelectionForTab(it).engine == BrowserEngineType.GECKOVIEW)
        }
        val affectedIds = affectedTabs.map { it.id }.toSet()
        pendingGeckoDataClearActive = true
        pendingGeckoDataClearTabIds.addAll(affectedIds)
        affectedTabs.forEach { tab ->
            if (tab.browserEngine != null) {
                deactivateTabRuntime(tab)
                closeTabRuntime(tab)
            }
            tab.needsReload = tab.lastUrl != HOME_URL
        }
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val finish = { succeeded: Boolean, message: String? ->
            if (completed.compareAndSet(false, true)) {
                // Clear process state first, then deliver the resume to whichever Activity owns the tabs.
                pendingGeckoDataClearTabIds.removeAll(affectedIds)
                pendingGeckoDataClearActive = false
                pendingGeckoDataClearResumeTabId = activeTabId
                if (fireOperationId != null) {
                    dispatchFireClearCompletion(FireClearCompletion(fireOperationId, succeeded, message))
                } else {
                    val target = currentActivityReference?.get()
                    if (target != null && !target.isFinishing && !target.isDestroyed) {
                        target.runOnUiThread {
                            if (target.isFinishing || target.isDestroyed) return@runOnUiThread
                            target.resumePendingGeckoDataClearTab()
                            if (target === this) onFinished(succeeded, message)
                            else target.toast(message ?: if (succeeded) "Selected GeckoView tab resumed after data deletion" else "GeckoView data clear failed: ${message ?: "Unknown error"}")
                        }
                    } else {
                        // If completion beats Activity recreation, onResume consumes this retained tab ID.
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            resumePendingGeckoDataClearTab()
                            onFinished(succeeded, message)
                        }
                    }
                }
            }
        }
        runCatching {
            runtime.storageController.clearData(flags).accept(
                { finish(true, null) },
                { error -> finish(false, error?.localizedMessage) }
            )
        }.onFailure { finish(false, it.localizedMessage) }
    }

    private fun dispatchFireClearCompletion(completion: FireClearCompletion) {
        if (pendingFireClearOperationId != completion.operationId) return
        val dispatcher = currentActivityReference?.get()
        if (dispatcher == null || dispatcher.isFinishing || dispatcher.isDestroyed) {
            pendingFireClearCompletion = completion
            return
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            // Resolve the Activity at execution time, not dispatch time: recreation can
            // happen while this completion is waiting in the main-thread queue.
            val target = currentActivityReference?.get()
            if (target == null || target.isFinishing || target.isDestroyed) {
                pendingFireClearCompletion = completion
                return@post
            }
            target.resumePendingGeckoDataClearTab()
            target.continueFireClearAfterGecko(completion)
        }
    }

    private fun retryRetiredCefriumProfileDeletes() {
        val activeProfile = enginePreferences.cefriumProfileName()
        enginePreferences.retiredCefriumProfiles().forEach { retired ->
            if (retired == activeProfile) return@forEach
            runCatching { CefriumProfile.delete(retired) }
                .onSuccess { enginePreferences.forgetRetiredCefriumProfile(retired) }
                .onFailure { android.util.Log.w("NovaBrowser", "Retired Cefrium profile cleanup will be retried", it) }
        }
    }

    private fun clearCefriumProfileDataNow(activeTabId: String?, onFinished: (Boolean, String?) -> Unit) {
        val oldProfile = enginePreferences.cefriumProfileName()
        if (!enginePreferences.recordRetiredCefriumProfile(oldProfile)) {
            onFinished(false, "Could not record the old Cefrium profile for cleanup. Existing data was not deleted.")
            return
        }
        val newProfile = enginePreferences.rotateCefriumProfile() ?: run {
            // The old profile is still active; startup retry explicitly skips active profiles.
            onFinished(false, "Could not persist a new Cefrium profile. Existing profile data was not deleted.")
            return
        }
        val cefriumTabs = tabs.filter { it.engineType == BrowserEngineType.CEFRIUM && !it.isPrivate && !it.isGuest }
        cefriumTabs.forEach { tab ->
            runCatching { closeTabRuntime(tab) }
            tab.needsReload = tab.lastUrl != HOME_URL
            tab.cefriumNavigationState = null
        }
        val deletion = runCatching { CefriumProfile.delete(oldProfile) }
        if (deletion.isSuccess) {
            // A normal false return is Cefrium's deferred-delete result; only exceptions need retry.
            enginePreferences.forgetRetiredCefriumProfile(oldProfile)
        }
        val index = tabs.indexOfFirst { it.id == activeTabId }
        if (index >= 0 && tabs[index].engineType == BrowserEngineType.CEFRIUM && currentTab()?.id == activeTabId) switchTab(index)
        if (splitTabId != null && cefriumTabs.any { it.id == splitTabId }) restoreSplitPane()
        persistTabs()
        when {
            deletion.isFailure -> onFinished(false, "New clean Cefrium profile active ($newProfile); old profile deletion failed and is queued for retry")
            deletion.getOrNull() == true -> onFinished(true, "Cefrium profile cleared; new clean profile active")
            else -> onFinished(true, "Cefrium profile cleared; old profile deletion is scheduled for restart")
        }
    }

    private fun clearCefriumProfileData() {
        if (denyGuestAccess("Cefrium profile deletion")) return
        AlertDialog.Builder(this).setTitle("Clear Cefrium profile data?")
            .setMessage("This removes cookies, cache, local/session storage, IndexedDB and service workers from all Normal Cefrium tabs. Those tabs will reload in a fresh profile. You may need to sign in again. Private and Guest data are separate and are not affected.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear profile") { _, _ ->
                clearCefriumProfileDataNow(currentTab()?.id) { succeeded, message ->
                    toast(message ?: if (succeeded) "Cefrium profile cleared" else "Cefrium profile clear failed")
                }
            }.show()
    }

    private fun closePrivateAndGuestTabs() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.close_private_guest_title))
            .setMessage(getString(R.string.close_private_guest_message))
            .setNegativeButton("Cancel", null)
            .setPositiveButton(getString(R.string.close_tabs)) { _, _ ->
                val activeId = currentTab()?.id
                tabs.indices.filter { tabs[it].isPrivate || tabs[it].isGuest }.reversed().forEach { index ->
                    val removed = tabs.removeAt(index)
        aiPageText.remove(removed.id)
        aiPageUrl.remove(removed.id)
                    if (removed.id == splitTabId) exitSplitScreen()
                    closeTabRuntime(removed)
                    removed.thumbnail?.recycle(); removed.thumbnail = null
                }
                cleanupGuestContextIfIdle()
                if (tabs.isEmpty()) {
                    if (!createTab(false, HOME_URL)) {
                        ensureHomeTabRecord()
                        persistTabs()
                    }
                } else {
                    activeIndex = tabs.indexOfFirst { it.id == activeId }.takeIf { it >= 0 } ?: tabs.lastIndex
                    switchTab(activeIndex)
                }
                toast(getString(R.string.private_guest_tabs_closed))
            }.show()
    }

    private fun chooseWebColorScheme() {
        val values = arrayOf(getString(R.string.follow_system), getString(R.string.light), getString(R.string.dark))
        val current = when (prefs.getString("web_color_scheme", "system")) { "light" -> 1; "dark" -> 2; else -> 0 }
        AlertDialog.Builder(this).setTitle(getString(R.string.web_page_color_scheme)).setSingleChoiceItems(values, current) { dialog, which ->
            val value = when (which) { 1 -> "light"; 2 -> "dark"; else -> "system" }
            prefs.edit().putString("web_color_scheme", value).apply()
            runtime.settings.setPreferredColorScheme(when (value) {
                "light" -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
                "dark" -> GeckoRuntimeSettings.COLOR_SCHEME_DARK
                else -> GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
            })
            dialog.dismiss()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun showCloudAccount() {
        if (denyGuestAccess("Nova Cloud")) return
        val email = cloudSync.signedInEmail()
        if (email.isNullOrBlank()) {
            val options = arrayOf(
                "Create account (email + password)",
                "Sign in with password",
                "Sign in with email code"
            )
            AlertDialog.Builder(this)
                .setTitle("Nova Cloud")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> showCloudSignUp()
                        1 -> showCloudPasswordSignIn()
                        2 -> showCloudOtpEmailEntry()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val enabled = prefs.getBoolean("cloud_sync_enabled", false)
        val lastSync = prefs.getLong("cloud_sync_last_success_at", 0L)
        val lastSyncText = if (lastSync > 0L) SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(lastSync)) else "Not synced yet"
        val syncError = prefs.getString("cloud_sync_last_error", null)
        val check = CheckBox(this).apply { text = "Allow encrypted cloud sync from this device"; isChecked = enabled; setPadding(dp(24), dp(12), dp(24), dp(12)) }
        val syncMessage = "Your sync data is encrypted before upload. The passphrase is never sent to Nova or Supabase and is stored only as Android-Keystore-protected ciphertext on this device after the first successful sync. Passwords, bookmarks, history and saved workspaces are included; private and Guest data is excluded.\n\nAutomatic sync: every 6 hours when a network is available.\nLast successful sync: $lastSyncText" + if (!syncError.isNullOrBlank()) "\nLast sync issue: $syncError" else ""
        AlertDialog.Builder(this).setTitle("Nova Cloud · $email").setMessage(syncMessage)
            .setView(check).setPositiveButton("Save") { _, _ ->
                prefs.edit().putBoolean("cloud_sync_enabled", check.isChecked).apply()
                if (check.isChecked) showSyncPassphrase() else {
                    NovaAutoSync.cancel(this)
                    AutoSyncPassphraseStore.clear(this)
                    toast("Cloud sync paused; the local sync key was cleared and your cloud backup is unchanged")
                }
            }.setNeutralButton("Sign out") { _, _ ->
                Thread {
                    invalidateCloudSync()
                    cloudSync.signOut()
                    NovaAutoSync.cancel(this@MainActivity)
                    AutoSyncPassphraseStore.clear(this@MainActivity)
                    // Keep the account binding: a later sign-in must not merge another account
                    // into this device's existing local browser profile.
                    prefs.edit().putBoolean("cloud_sync_enabled", false).apply()
                    runOnUiThread { toast("Signed out. Local browsing data is unchanged.") }
                }.start()
            }.setNegativeButton("Close", null).show()
    }

private fun showCloudOtpEmailEntry() {
        val input = EditText(this).apply {
            hint = "Email address"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle("Sign in with email code")
            .setMessage("We'll email you a one-time 6-digit code. No password is required.")
            .setView(input)
            .setPositiveButton("Send code") { _, _ ->
                val address = input.text.toString().trim()
                if (address.isBlank()) {
                    toast("Enter your email address")
                    return@setPositiveButton
                }
                toast("Requesting sign-in code\u2026")
                Thread {
                    runCatching { cloudSync.sendEmailOtp(address) }
                        .onSuccess { runOnUiThread { showCloudOtp(address, System.currentTimeMillis()) } }
                        .onFailure { runOnUiThread { toast("Could not send code: ${it.message ?: "Network error"}") } }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

private fun showCloudSignUp() {
        val pad = dp(16)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val emailInput = EditText(this).apply {
            hint = "Email address"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            isSingleLine = true
        }
        val passwordInput = EditText(this).apply {
            hint = "Password (min 8 characters)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        val confirmInput = EditText(this).apply {
            hint = "Confirm password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        container.addView(emailInput)
        container.addView(passwordInput)
        container.addView(confirmInput)
        AlertDialog.Builder(this)
            .setTitle("Create Nova Cloud account")
            .setMessage("Create an account to sync bookmarks, history, passwords and settings across your devices. Data is encrypted on this device before upload.")
            .setView(container)
            .setPositiveButton("Create account") { _, _ ->
                val address = emailInput.text.toString().trim()
                val password = passwordInput.text.toString()
                val confirm = confirmInput.text.toString()
                when {
                    address.isBlank() -> toast("Enter your email address")
                    password.length < 8 -> toast("Password must be at least 8 characters")
                    password != confirm -> toast("Passwords do not match")
                    else -> {
                        toast("Creating account\u2026")
                        Thread {
                            runCatching { cloudSync.signUpWithPassword(address, password) }
                                .onSuccess {
                                    runOnUiThread {
                                        toast("Account created and signed in")
                                        showCloudAccount()
                                    }
                                }
                                .onFailure { err ->
                                    val msg = err.message ?: "Sign-up failed"
                                    runOnUiThread {
                                        if (msg.contains("confirm", ignoreCase = true) || msg.contains("check your email", ignoreCase = true)) {
                                            AlertDialog.Builder(this)
                                                .setTitle("Confirm your email")
                                                .setMessage(msg)
                                                .setPositiveButton("OK") { _, _ -> showCloudPasswordSignIn() }
                                                .show()
                                        } else {
                                            toast(msg)
                                        }
                                    }
                                }
                        }.start()
                    }
                }
            }
            .setNeutralButton("Use email code") { _, _ -> showCloudOtpEmailEntry() }
            .setNegativeButton("Cancel", null)
            .show()
    }

private fun showCloudPasswordSignIn() {
        val pad = dp(16)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val emailInput = EditText(this).apply {
            hint = "Email address"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            isSingleLine = true
        }
        val passwordInput = EditText(this).apply {
            hint = "Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        container.addView(emailInput)
        container.addView(passwordInput)
        AlertDialog.Builder(this)
            .setTitle("Sign in to Nova Cloud")
            .setView(container)
            .setPositiveButton("Sign in") { _, _ ->
                val address = emailInput.text.toString().trim()
                val password = passwordInput.text.toString()
                if (address.isBlank() || password.isEmpty()) {
                    toast("Enter email and password")
                    return@setPositiveButton
                }
                toast("Signing in\u2026")
                Thread {
                    runCatching { cloudSync.signInWithPassword(address, password) }
                        .onSuccess {
                            runOnUiThread {
                                toast("Signed in to Nova Cloud")
                                showCloudAccount()
                            }
                        }
                        .onFailure { runOnUiThread { toast("Sign-in failed: ${it.message ?: "Check email and password"}") } }
                }.start()
            }
            .setNeutralButton("Forgot password?") { _, _ ->
                showForgotPassword(emailInput.text.toString().trim())
            }
            .setNegativeButton("Email code") { _, _ -> showCloudOtpEmailEntry() }
            .show()
    }

private fun showForgotPassword(prefillEmail: String = "") {
        val input = EditText(this).apply {
            hint = "Email address"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            isSingleLine = true
            if (prefillEmail.isNotBlank()) setText(prefillEmail)
        }
        AlertDialog.Builder(this)
            .setTitle("Reset password")
            .setMessage("We'll email a password-reset link to this address. Open the link on any device to choose a new password, then sign in here.")
            .setView(input)
            .setPositiveButton("Send reset link") { _, _ ->
                val address = input.text.toString().trim()
                if (address.isBlank()) {
                    toast("Enter your email address")
                    return@setPositiveButton
                }
                sendPasswordResetRequest(address)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendPasswordResetRequest(address: String) {
        toast("Sending reset link…")
        Thread {
            val sent = runCatching { cloudSync.requestPasswordReset(address) }.getOrDefault(false)
            runOnUiThread {
                if (sent) {
                    AlertDialog.Builder(this)
                        .setTitle("Check your email")
                        .setMessage("If an account exists for $address, a password-reset link has been sent. Open it to set a new password, then return here to sign in.")
                        .setPositiveButton("OK") { _, _ -> showCloudPasswordSignIn() }
                        .show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Couldn’t send reset link")
                        .setMessage("Nova could not reach the password-recovery service. Check your connection and try again.")
                        .setPositiveButton("Retry") { _, _ -> sendPasswordResetRequest(address) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }.start()
    }

    private fun showCloudOtp(email: String, lastSentAtMillis: Long) {
        val input = EditText(this).apply { hint = "6-digit email code"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; isSingleLine = true }
        val dialog = AlertDialog.Builder(this).setTitle("Verify email").setMessage("Enter the six-digit code sent to $email")
            .setView(input).setPositiveButton("Verify") { _, _ ->
                val code = input.text.toString()
                toast("Verifying code…")
                Thread {
                    runCatching { cloudSync.verifyEmailOtp(email, code) }
                        .onSuccess { runOnUiThread { toast("Signed in to Nova Cloud"); showCloudAccount() } }
                        .onFailure { runOnUiThread { toast("Sign-in failed: ${it.message ?: "Try again"}") } }
                }.start()
            }.setNeutralButton("Resend code", null).setNegativeButton("Cancel", null).create()

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var lastRequestAt = lastSentAtMillis
        val ticker = object : Runnable {
            override fun run() {
                val remaining = NovaEmailOtpPolicy.remainingCooldownSeconds(lastRequestAt, System.currentTimeMillis())
                val resend = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                resend.isEnabled = remaining == 0L
                resend.text = if (remaining > 0L) "Resend in ${remaining}s" else "Resend code"
                if (remaining > 0L) handler.postDelayed(this, 1_000L)
            }
        }
        dialog.setOnShowListener {
            val resend = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            resend.setOnClickListener {
                if (NovaEmailOtpPolicy.remainingCooldownSeconds(lastRequestAt, System.currentTimeMillis()) > 0L) return@setOnClickListener
                lastRequestAt = System.currentTimeMillis()
                ticker.run()
                Thread {
                    runCatching { cloudSync.sendEmailOtp(email) }
                        .onSuccess {
                            runOnUiThread {
                                if (dialog.isShowing) {
                                    dialog.dismiss()
                                    showCloudOtp(email, lastRequestAt)
                                    toast("A new code was requested")
                                }
                            }
                        }
                        .onFailure { runOnUiThread { toast("Could not resend: ${it.message ?: "Network error"}") } }
                }.start()
            }
            ticker.run()
        }
        dialog.setOnDismissListener { handler.removeCallbacks(ticker) }
        dialog.show()
    }

    private fun showSyncPassphrase() {
        val input = EditText(this).apply { hint = "Sync passphrase (10+ characters)"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; isSingleLine = true }
        AlertDialog.Builder(this).setTitle("Encrypt and sync").setMessage("Use the same passphrase on every device. If you lose it, the encrypted backup cannot be recovered. Passwords, bookmarks, history and saved workspaces are included; private and Guest data is excluded.")
            .setView(input).setPositiveButton("Sync now") { _, _ ->
                val passphrase = input.text.toString().toCharArray()
                if (passphrase.size < 10) { passphrase.fill('\u0000'); toast("Passphrase must be at least 10 characters"); return@setPositiveButton }
                toast("Downloading, merging and encrypting your data…")
                Thread { performCloudSync(passphrase) }.start()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun invalidateCloudSync() {
        synchronized(cloudSyncStateLock) {
            cloudSyncGeneration = maxOf(cloudSyncGeneration, prefs.getLong("cloud_sync_generation", 0L)) + 1L
            // Commit the fence before sign-out/disable can complete so a queued worker apply sees it.
            prefs.edit().putLong("cloud_sync_generation", cloudSyncGeneration).commit()
        }
    }

    private fun isCloudSyncCurrent(generation: Long): Boolean = synchronized(cloudSyncStateLock) {
        generation == cloudSyncGeneration && prefs.getLong("cloud_sync_generation", 0L) == generation
    }

    @Synchronized
    private fun performCloudSync(passphrase: CharArray) {
        val operationGeneration = synchronized(cloudSyncStateLock) {
            cloudSyncGeneration = maxOf(cloudSyncGeneration, prefs.getLong("cloud_sync_generation", 0L))
            cloudSyncGeneration
        }
        try {
            val userId = cloudSync.signedInUserId() ?: throw IllegalStateException("Sign in to Nova Cloud first")
            val boundUserId = prefs.getString("cloud_sync_user_id", null)
            if (!boundUserId.isNullOrBlank() && boundUserId != userId) {
                throw IllegalStateException("This device's local browser profile is linked to a different Nova Cloud account. Use that account or clear this app's local data before switching accounts.")
            }
            val local = buildSyncSnapshot()
            val localVault = passwordVault.exportSyncBundle()
            val remote = cloudSync.downloadEncrypted(userId)
            check(isCloudSyncCurrent(operationGeneration)) { "Cloud sync was cancelled because you signed out" }
            val remoteSnapshot = remote.snapshot
            val remoteVault = remote.encryptedVaultPayloads.fold(JSONObject()) { mergedRemote, payload ->
                PasswordVault.mergeSyncBundles(
                    mergedRemote,
                    PasswordVault.decodeSyncBundle(CloudCrypto.decrypt(payload, passphrase))
                )
            }
            val merged = if (remoteSnapshot == null) local else mergeSyncSnapshots(local,
                JSONObject(CloudCrypto.decrypt(remoteSnapshot, passphrase)))
            val mergedVault = PasswordVault.mergeSyncBundles(localVault, remoteVault)
            NovaSyncSnapshotRepository.validate(merged)
            // Validate without mutating local credentials. A failed upload must not apply remote
            // deletions or partial merges to the device's existing vault.
            passwordVault.validateSyncBundle(mergedVault, allowLegacyImport = true)
            check(isCloudSyncCurrent(operationGeneration)) { "Cloud sync was cancelled because you signed out" }
            // Bind the device before its first remote write. If upload partially succeeds or
            // local apply later conflicts, a different account must not inherit this profile.
            synchronized(cloudSyncStateLock) {
                check(operationGeneration == cloudSyncGeneration &&
                    prefs.getLong("cloud_sync_generation", 0L) == operationGeneration) {
                    "Cloud sync was cancelled because you signed out"
                }
                val currentBinding = prefs.getString("cloud_sync_user_id", null)
                check(currentBinding.isNullOrBlank() || currentBinding == userId) {
                    "This device's local browser profile is linked to a different Nova Cloud account"
                }
                if (currentBinding.isNullOrBlank()) {
                    check(prefs.edit().putString("cloud_sync_user_id", userId).commit()) {
                        "Could not securely bind this device to the Nova Cloud account"
                    }
                }
            }
            cloudSync.uploadEncrypted(userId, CloudCrypto.encrypt(merged.toString(), passphrase),
                CloudCrypto.encrypt(mergedVault.toString(), passphrase))
            // The worker clears its input array in finally. Keep a separate copy for the UI
            // completion, which may execute after the worker has already returned.
            val passphraseForStore = passphrase.copyOf()
            runOnUiThread {
                try {
                    synchronized(cloudSyncStateLock) {
                        if (operationGeneration != cloudSyncGeneration || isFinishing || isDestroyed) return@synchronized
                        // UI preferences and the vault may have changed while the network upload ran.
                        // Never replace those newer local edits with the earlier merge.
                        if (!NovaJsonEquality.areEqual(buildSyncSnapshot(), local)) {
                            toast("Cloud upload finished, but local browser data changed during sync. Local edits were preserved; run Sync now again.")
                            return@synchronized
                        }
                        if (!passwordVault.importSyncBundleIfUnchanged(localVault, mergedVault)) {
                            toast("Cloud upload finished, but the password vault changed during sync. Local edits were preserved; run Sync now again.")
                            return@synchronized
                        }
                        applySyncSnapshot(merged)
                        if (prefs.getBoolean("cloud_sync_enabled", false)) {
                            try {
                                AutoSyncPassphraseStore.save(this, passphraseForStore)
                                NovaAutoSync.schedule(this)
                            } catch (error: Exception) {
                                // The browser data merge succeeded, but auto-sync must remain off
                                // until its local Keystore-protected passphrase can be saved.
                                prefs.edit().putBoolean("cloud_sync_enabled", false).apply()
                                NovaAutoSync.cancel(this)
                                prefs.edit().putString("cloud_sync_user_id", userId).apply()
                                toast("Cloud sync completed, but automatic sync setup failed: ${error.message ?: "secure key storage unavailable"}")
                                return@synchronized
                            }
                        }
                        // Do not turn sync back on here: the user may have paused it while upload ran.
                        prefs.edit().putString("cloud_sync_user_id", userId).apply()
                        toast("Encrypted cloud sync completed")
                    }
                } finally {
                    passphraseForStore.fill('\u0000')
                }
            }
        } catch (error: Exception) {
            runOnUiThread { toast("Cloud sync failed: ${error.message ?: "Check connection and passphrase"}") }
        } finally { passphrase.fill('\u0000') }
    }

    private fun buildSyncSnapshot(): JSONObject = NovaSyncSnapshotRepository.build(this, searchEngine)

    private fun mergeSyncSnapshots(local: JSONObject, remote: JSONObject): JSONObject = NovaSyncMerge.merge(local, remote)

    private fun applySyncSnapshot(snapshot: JSONObject) {
        NovaSyncSnapshotRepository.apply(this, snapshot)
        searchEngine = prefs.getString("search_engine", searchEngine) ?: searchEngine
        NovaShield.apply(runtime, NovaShield.fromStored(prefs.getString("shield_mode", "STANDARD")))
        applyGeckoCookieBehavior()
        applyGeckoImageBehavior()
        val fontScaleFactors = floatArrayOf(0.9f, 1.0f, 1.15f, 1.3f, 1.5f)
        val savedFontScale = prefs.getInt("font_scale_index", 1).coerceIn(fontScaleFactors.indices)
        runtime.settings.setFontSizeFactor(fontScaleFactors[savedFontScale])
        runtime.settings.setForceUserScalableEnabled(true)
        homeShortcuts.visibility = if (prefs.getBoolean("show_home_shortcuts", true)) View.VISIBLE else View.GONE
        searchEngineButton.text = getString(R.string.search_engine_button, searchEngine)
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }.toSet()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val nightMask = Configuration.UI_MODE_NIGHT_MASK
        val nightChanged = (newConfig.uiMode and nightMask) != (lastUiMode and nightMask)
        val fontScaleChanged = newConfig.fontScale != lastFontScale
        lastUiMode = newConfig.uiMode
        lastFontScale = newConfig.fontScale
        if (nightChanged || fontScaleChanged) { recreate(); return }
        if (!::browserPane.isInitialized || !::browserView.isInitialized || !::secondaryPane.isInitialized) return
        applyFoldAwareLayout(separatingFold)
    }

    private val activeVideoProbeScript = """
        (() => Array.from(document.querySelectorAll('video')).some(video => !video.paused && !video.ended && video.readyState >= 2))()
    """.trimIndent()

    private val fullscreenVideoProbeScript = """
        (() => {
          const root = document.fullscreenElement;
          if (!root) return false;
          const videos = root instanceof HTMLVideoElement ? [root] : Array.from(root.querySelectorAll('video'));
          return videos.some(video => !video.paused && !video.ended && video.readyState >= 2);
        })()
    """.trimIndent()

    private fun checkFullscreenVideoPlayback(tab: BrowserTab, onResult: (Boolean) -> Unit) {
        // The pinned GeckoView API does not expose a supported evaluateJS call here.
        // Keep PiP playback detection on Cefrium until a GeckoView-supported playback
        // signal is available; never call an API that prevents the app from compiling.
        if (tab.engineType != BrowserEngineType.CEFRIUM) {
            onResult(false)
            return
        }
        val callback: (String?) -> Unit = { raw ->
            onResult(raw?.trim()?.trim('"') == "true")
        }
        runCatching {
            val engine = tab.browserEngine as? CefriumBrowserEngine
            if (engine == null) onResult(false) else engine.evaluateJavaScript(fullscreenVideoProbeScript, callback)
        }.onFailure { onResult(false) }
    }

    private fun refreshFullscreenVideoPlayback(tab: BrowserTab) {
        checkFullscreenVideoPlayback(tab) { playing ->
            runOnUiThread {
                if (isFullscreen && fullscreenTabId == tab.id && currentTab()?.id == tab.id) {
                    tab.isFullscreenVideoPlaying = playing
                }
            }
        }
    }


    private fun requestUserPictureInPicture() {
        val tab = currentTab() ?: return
        if (tab.engineType != BrowserEngineType.CEFRIUM) {
            toast("Pop out video currently requires a Cefrium tab; this tab uses GeckoView")
            return
        }
        checkActiveVideoPlayback(tab) { playing ->
            runOnUiThread {
                if (isDestroyed || currentTab()?.id != tab.id) return@runOnUiThread
                if (!BrowserPictureInPicturePolicy.shouldEnterUserInitiated(
                        apiLevel = Build.VERSION.SDK_INT,
                        activeTabIsCurrent = true,
                        videoIsPlaying = playing,
                        alreadyInPictureInPicture = isInPictureInPictureMode
                    )
                ) {
                    toast("Play a video first, then try Pop out video")
                    return@runOnUiThread
                }
                enterBrowserPictureInPicture(tab, playing, userInitiated = true)
            }
        }
    }

    private fun checkActiveVideoPlayback(tab: BrowserTab, onResult: (Boolean) -> Unit) {
        val engine = tab.browserEngine as? CefriumBrowserEngine
        if (engine == null) { onResult(false); return }
        runCatching {
            engine.evaluateJavaScript(activeVideoProbeScript) { raw -> onResult(raw?.trim()?.trim('"') == "true") }
        }.onFailure { onResult(false) }
    }

    private fun enterBrowserPictureInPicture(tab: BrowserTab, videoIsPlaying: Boolean, userInitiated: Boolean = false) {
        val activeFullscreenTab = fullscreenTabId == tab.id && currentTab()?.id == tab.id
        val allowed = if (userInitiated) {
            BrowserPictureInPicturePolicy.shouldEnterUserInitiated(
                Build.VERSION.SDK_INT, currentTab()?.id == tab.id, videoIsPlaying, isInPictureInPictureMode
            )
        } else {
            BrowserPictureInPicturePolicy.shouldEnter(
                apiLevel = Build.VERSION.SDK_INT,
                isFullscreen = isFullscreen,
                fullscreenTabIsActive = activeFullscreenTab,
                videoIsPlaying = videoIsPlaying,
                alreadyInPictureInPicture = isInPictureInPictureMode
            )
        }
        if (!allowed) return
        runCatching {
            val params = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }.onFailure {
            android.util.Log.w("NovaBrowser", "Could not enter Picture-in-Picture", it)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val tab = fullscreenTabId?.let { id -> currentTab()?.takeIf { it.id == id } } ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isFullscreen || isInPictureInPictureMode) return
        val requestId = ++pipLeaveRequestId
        val requestedTabId = tab.id
        // Always query current playback: the cached fullscreen flag can be stale after pause/end.
        checkFullscreenVideoPlayback(tab) { playing ->
            runOnUiThread {
                if (requestId != pipLeaveRequestId || !isFullscreen ||
                    fullscreenTabId != requestedTabId || currentTab()?.id != requestedTabId ||
                    isInPictureInPictureMode) return@runOnUiThread
                tab.isFullscreenVideoPlaying = playing
                if (playing) enterBrowserPictureInPicture(tab, true)
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!::topChrome.isInitialized || !::bottomToolbar.isInitialized) return
        if (isInPictureInPictureMode) {
            topChrome.visibility = View.GONE
            bottomToolbar.visibility = View.GONE
        } else if (isFullscreen) {
            setBrowserFullscreen(true, tabs.firstOrNull { it.id == fullscreenTabId })
        } else {
            setBrowserFullscreen(false, currentTab())
        }
    }

    override fun onPause() {
        cancelPendingRemoteSuggestions()
        // Keep the renderer alive while Android hosts fullscreen video in PiP.
        if (!isInPictureInPictureMode) tabs.forEach { it.browserEngine?.onHostPause() }
        persistTabs()
        super.onPause()
    }

    override fun onStop() {
        // onPause normally pauses engines, but Android can stop the Activity after
        // onPause was skipped for PiP. Do not pause the active PiP playback surface.
        if (!isInPictureInPictureMode) tabs.forEach { it.browserEngine?.onHostPause() }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Returning to the full app invalidates a delayed leave-hint probe; resuming
        // inside PiP must not cancel the probe that is entering PiP.
        if (!isInPictureInPictureMode) pipLeaveRequestId++
        resumePendingGeckoDataClearTab()
        consumePendingFireClearCompletion()
        applyGeckoImageBehavior { applied ->
            if (!applied) toast("Could not update GeckoView image-loading settings")
        }
        applyVpnRoutingReadiness()
        tabs.forEach { it.browserEngine?.onHostResume() }
    }

    @Deprecated("Activity result API retained for compatibility with the current minimal app module")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == uploadRequestCode) {
            val prompt = pendingFilePrompt
            val result = pendingFileResult
            pendingFilePrompt = null; pendingFileResult = null
            if (prompt != null && result != null) {
                if (resultCode == RESULT_OK && data != null) {
                    val uris = mutableListOf<Uri>()
                    data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
                    if (uris.isEmpty()) data.data?.let { uris.add(it) }
                    if (prompt.type == FilePrompt.Type.FOLDER) {
                        val folderUri = data.data
                        if (folderUri != null) result.complete(prompt.confirm(this, folderUri)) else result.complete(prompt.dismiss())
                    } else if (uris.isNotEmpty()) {
                        val response = if (prompt.type == FilePrompt.Type.MULTIPLE) prompt.confirm(this, uris.toTypedArray()) else prompt.confirm(this, uris.first())
                        result.complete(response)
                    } else result.complete(prompt.dismiss())
                } else result.complete(prompt.dismiss())
            }
            return
        }
        if (requestCode == historyExportRequestCode) {
            val payload = pendingHistoryExport
            pendingHistoryExport = null
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null && payload != null) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(payload) }
                        ?: throw IllegalStateException("Could not open export destination")
                    toast("Browsing history exported")
                }.onFailure { toast("History export failed: ${it?.localizedMessage ?: "Unknown error"}") }
            }
            return
        }
        if (requestCode == bookmarkRequestCode) {
            val mode = bookmarkTransferMode
            bookmarkTransferMode = null
            val uri = data?.data
            if (resultCode != RESULT_OK || uri == null || mode == null) return
            runCatching {
                if (mode == "export") {
                    val array = JSONArray()
                    readBookmarks().forEach { array.put(JSONObject().put("url", it.url).put("title", it.title).put("folder", it.folder).put("addedAt", it.addedAt)) }
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(array.toString(2)) }
                        ?: throw IllegalStateException("Could not open export destination")
                    toast("Bookmarks exported")
                } else {
                    val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalStateException("Could not read bookmark file")
                    val imported = if (json.trimStart().startsWith("{")) {
                        JSONObject(json).optJSONArray("bookmarks")
                            ?: throw IllegalArgumentException("Bookmark backup does not contain a bookmarks array")
                    } else {
                        JSONArray(json)
                    }
                    val merged = readBookmarks().associateBy { it.url }.toMutableMap()
                    for (i in 0 until imported.length()) {
                        val item = imported.getJSONObject(i)
                        val url = item.optString("url")
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            val importedAt = item.optLong("addedAt", System.currentTimeMillis())
                            val deletedAt = runCatching {
                                JSONObject(prefs.getString("bookmark_tombstones", "{}") ?: "{}").optLong(url)
                            }.getOrDefault(0L)
                            val restoredAt = maxOf(importedAt, deletedAt + 1L)
                            merged[url] = BookmarkEntry(url, item.optString("title", Uri.parse(url).host ?: url), item.optString("folder", "Imported"), restoredAt)
                        }
                    }
                    if (!saveBookmarks(merged.values.toList())) return@runCatching
                    toast("Imported ${imported.length()} bookmarks")
                }
            }.onFailure { toast("Bookmark transfer failed: ${it?.localizedMessage ?: "Unknown error"}") }
        }
    }


    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_SITE_PERMISSIONS) return
        val callback = pendingAndroidPermissionCallback ?: return
        pendingAndroidPermissionCallback = null
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) callback.grant() else callback.reject()
    }

    override fun onDestroy() {
        if (currentActivityReference?.get() === this) currentActivityReference = null
        downloadManagers.values.forEach { it.setOnChangedListener(null) }
        guestTorStartPending = false
        if ((!isChangingConfigurations || tabs.none { it.isGuest }) && TorGuestRoutingController.blocksNormalGeckoSessions()) {
            TorGuestRoutingController.stop { /* a recreated Activity is notified after the extension acknowledges the reset */ }
        }
        NovaBrowserVpnController.clearStatusListener(this)
        cancelPendingRemoteSuggestions()
        remoteSuggestionExecutor.shutdownNow()
        pendingAndroidPermissionCallback?.reject()
        pendingAndroidPermissionCallback = null
        cefriumPermissionCancelActions.keys.toList().forEach(::cancelCefriumPermissionPrompt)
        pendingCefriumAndroidPermissions.clear()
        activeCefriumAndroidPermission?.let { request ->
            request.cancelled = true
            request.complete(false)
        }
        activeCefriumAndroidPermission = null
        tabSearchInputDialog?.dismiss()
        tabSearchInputDialog = null
        tabSearchResultsDialog?.dismiss()
        tabSearchResultsDialog = null
        activeBottomSheetDialogs.toList().forEach { runCatching { it.dismiss() } }
        activeBottomSheetDialogs.clear()
        activeUndoPopups.toList().forEach { runCatching { it.dismiss() } }
        activeUndoPopups.clear()
        activePromptDialogs.toList().forEach { runCatching { it.dismiss() } }
        activePromptDialogs.clear()
        pendingFilePrompt?.let { prompt -> pendingFileResult?.let { result -> runCatching { result.complete(prompt.dismiss()) } } }
        pendingFilePrompt = null
        pendingFileResult = null
        persistTabs()
        if (isChangingConfigurations) {
            tabs.forEach { tab ->
                if (tab.engineType == BrowserEngineType.CEFRIUM) {
                    closeTabRuntime(tab)
                    tab.needsReload = tab.lastUrl != HOME_URL
                } else if (tab.isGuest) {
                    // A detached GeckoSession can still issue background requests. Close it until
                    // the recreated Activity has revalidated the Tor route.
                    closeTabRuntime(tab)
                    tab.needsReload = tab.lastUrl != HOME_URL
                } else {
                    tab.browserEngine?.detach()
                    tab.browserEngine = null
                }
            }
            retainedTabsForConfiguration = tabs.toList()
            retainedActiveIndex = activeIndex
            retainedSplitTabId = splitTabId
            retainedGuestContextId = guestContextId
        } else {
            tabs.forEach { tab -> closeTabRuntime(tab); tab.thumbnail?.recycle(); tab.thumbnail = null }
            if (::runtime.isInitialized && guestContextId.isNotBlank() && prefs.getBoolean("guest_context_used", false)) {
                queueGuestContextCleanup(guestContextId)
            }
        }
        if (isChangingConfigurations) {
            val retainableIds = tabs.filter { !it.isPrivate && !it.isGuest }.map { it.id }.toSet()
            retainedAiPageText = aiPageText.filterKeys { it in retainableIds }
            retainedAiPageUrl = aiPageUrl.filterKeys { it in retainableIds }
        } else {
            retainedAiPageText = null
            retainedAiPageUrl = null
        }
        if (!isChangingConfigurations) tabs.forEach { tab -> tab.thumbnail?.recycle(); tab.thumbnail = null }
        tabs.clear()
        aiPageText.clear()
        aiPageUrl.clear()
        // Keep the process-wide GeckoRuntime alive; shutting it down can terminate in-flight downloads.
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_SITE_PERMISSIONS = 4021
        private const val HOME_URL = "about:blank"
        @Volatile private var sharedRuntime: GeckoRuntime? = null
        @Volatile private var currentActivityReference: java.lang.ref.WeakReference<MainActivity>? = null
        @Volatile private var pendingGeckoDataClearResumeTabId: String? = null
        private var retainedTabsForConfiguration: List<BrowserTab>? = null
        private var retainedActiveIndex: Int = 0
        private var retainedSplitTabId: String? = null
        private var retainedGuestContextId: String? = null
        private var retainedAiPageText: Map<String, String>? = null
        private var retainedAiPageUrl: Map<String, String>? = null
        private val pendingGeckoDataClearTabIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        @Volatile private var pendingGeckoDataClearActive = false
        @Volatile private var pendingFireClearOperationId: String? = null
        @Volatile private var pendingFireClearCompletion: FireClearCompletion? = null
        private const val ADGUARD_DOH_ENDPOINT = "https://dns.adguard-dns.com/dns-query"
        private const val PWA_MANIFEST_LINK_SCRIPT = """
            (() => {
              const link = document.querySelector('link[rel~="manifest"]');
              return JSON.stringify({ manifestUrl: link ? link.href : '' });
            })()
        """
    }
}
