package com.nova.browser

import androidx.activity.ComponentActivity
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
import org.mozilla.geckoview.WebExtensionController
import org.mozilla.geckoview.StorageController
import android.widget.CheckBox
import android.content.ClipData
import android.content.ClipboardManager
import org.mozilla.geckoview.GeckoView
import com.cefrium.Cefrium
import com.cefrium.CefriumProfile
import com.cefrium.CefriumBrowser
import com.cefrium.CefriumContextMenu
import com.nova.browser.engine.BrowserEngine as TabBrowserEngine
import com.nova.browser.engine.BrowserEngineType
import com.nova.browser.engine.BrowserEngineMode
import com.nova.browser.engine.BrowserEngineState
import com.nova.browser.engine.BrowserEngineListener
import com.nova.browser.engine.BrowserEnginePreferenceStore
import com.nova.browser.engine.CefriumTabStateCipher
import com.nova.browser.engine.BrowserEngineProfilePolicy
import com.nova.browser.engine.BrowserProfileKind
import com.nova.browser.engine.EngineSelectionRequest
import com.nova.browser.engine.EngineSelectionPolicy
import com.nova.browser.engine.CefriumBrowserEngine
import com.nova.browser.engine.CefriumSitePermissionPolicy
import com.nova.browser.engine.GeckoViewBrowserEngine
import com.nova.browser.engine.NovaScriptAddonStore

/** Nova browser core. Normal tabs may use Cefrium; Private and Guest remain GeckoView-only. */
class MainActivity : ComponentActivity() {
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var cefriumPermissionLauncher: ActivityResultLauncher<Array<String>>
    private var pendingCefriumPermissionDecision: ((Boolean) -> Unit)? = null
    private var pendingCefriumPermissionDialog: AlertDialog? = null
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
    private var splitTabId: String? = null
    private val collapsedGroups = mutableSetOf<String>()
    private var pendingFilePrompt: FilePrompt? = null
    private var pendingFileResult: GeckoResult<PromptResponse>? = null
    private val activePromptDialogs = linkedSetOf<AlertDialog>()
    private val activeBottomSheetDialogs = linkedSetOf<Dialog>()
    private val activeUndoPopups = linkedSetOf<PopupWindow>()
    private var pendingAndroidPermissionCallback: GeckoSession.PermissionDelegate.Callback? = null
    private var bookmarkTransferMode: String? = null
    private var guestContextId: String = ""
    private val novaDownloads: NovaDownloadManager by lazy {
        NovaDownloadManager(this) { items -> runOnUiThread { updateDownloadSummary(items) } }
            .also { it.resumeInterrupted() }
    }
    private val passwordVault: PasswordVault by lazy { PasswordVault(this) }
    private val cloudSync: NovaCloudSync by lazy { NovaCloudSync(this) }
    private var pendingHistoryExport: String? = null
    private val historyExportRequestCode = 4103
    private val detectedPageLanguages = mutableMapOf<String, String>()
    private val bookmarkRequestCode = 4101
    private val uploadRequestCode = 4102
    private val desktopSites get() = prefs.getStringSet("desktop_sites", emptySet())?.toMutableSet() ?: mutableSetOf()
    private lateinit var addressBar: AutoCompleteTextView
    private var addressSuggestionItems: List<BrowserSuggestion> = emptyList()
    private val addressSuggestionHandler = Handler(Looper.getMainLooper())
    private var pendingRemoteSuggestion: Runnable? = null
    private var activeRemoteSuggestionFuture: java.util.concurrent.Future<*>? = null
    private val remoteSuggestionExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var separatingFold: FoldingFeature? = null
    private lateinit var addressSuggestionsAdapter: ArrayAdapter<String>
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
    private var activeIndex = 0
    private var lastUiMode = 0
    private var lastFontScale = 1f
    private var searchEngine = "Google"
    private val prefs by lazy { getSharedPreferences("nova_local", MODE_PRIVATE) }
    private val novaScriptAddonStore by lazy { NovaScriptAddonStore(this) }

    private data class BookmarkEntry(val url: String, val title: String, val folder: String, val addedAt: Long)
    private data class HistoryEntry(val url: String, val title: String, val visitedAt: Long)
    private data class HomeShortcut(val title: String, val url: String)

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
        var browserEngine: TabBrowserEngine? = null,
        var engineFallbackReason: String? = null,
        var cefriumNavigationState: ByteArray? = null, // AndroidKeyStore AES-GCM ciphertext only.
        var desktopSite: Boolean = false,
        var readerOriginalUrl: String? = null,
        var trackingPermission: GeckoSession.PermissionDelegate.ContentPermission? = null,
        var contentPermissions: List<GeckoSession.PermissionDelegate.ContentPermission> = emptyList()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastUiMode = resources.configuration.uiMode
        lastFontScale = resources.configuration.fontScale
        notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationPermissionRequestPending = false
            if (!granted) toast("Downloads still work, but Android may hide their notifications until permission is enabled")
        }
        cefriumPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val decision = pendingCefriumPermissionDecision
            pendingCefriumPermissionDecision = null
            decision?.invoke(results.isNotEmpty() && results.values.all { it })
        }
        enginePreferences = BrowserEnginePreferenceStore(prefs)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val tab = currentTab()
                if (findBar != null) { closeFindBar(); return }
                if (isFullscreen) {
                    val fullscreenTab = fullscreenTabId?.let { id -> tabs.firstOrNull { it.id == id } } ?: currentTab()
                    if (fullscreenTab?.engineType == BrowserEngineType.CEFRIUM) { fullscreenTab.browserEngine?.exitFullscreen(); return }
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
        val webScheme = when (prefs.getString("web_color_scheme", "system")) {
            "dark" -> GeckoRuntimeSettings.COLOR_SCHEME_DARK
            "light" -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
            else -> GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
        }
        runtime.settings.setPreferredColorScheme(webScheme)
        applyProtectedDns(prefs.getBoolean("protected_dns_enabled", false))
        if (prefs.getBoolean("cloud_sync_enabled", false) && AutoSyncPassphraseStore.has(this)) NovaAutoSync.schedule(this)
        NovaShield.apply(runtime, NovaShield.fromStored(prefs.getString("shield_mode", "STANDARD")))
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
                    .onSuccess { runOnUiThread { switchCefriumTabsForSavedLogins() } }
                    .onFailure { runOnUiThread { toast("Could not save login securely: ${it?.localizedMessage ?: "Unknown error"}") } }
            }
        })
        runtime.settings.setLoginAutofillEnabled(true)
        configureExtensionPermissions()
        NovaBrowserVpnController.initialize(this, runtime)
        NovaBrowserVpnController.setStatusListener {
            if (NovaBrowserVpnController.isVpnEnabled()) runOnUiThread {
                if (!isFinishing && !isDestroyed && ::browserPane.isInitialized) switchAllCefriumTabsToGecko("1VPN is enabled")
            }
        }
        val retained = retainedTabsForConfiguration
        if (retained != null) {
            tabs.addAll(retained)
            activeIndex = retainedActiveIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
            splitTabId = retainedSplitTabId
            guestContextId = retainedGuestContextId.orEmpty()
            retainedTabsForConfiguration = null
            retainedSplitTabId = null
            retainedGuestContextId = null
        } else {
            val previousGuestContext = prefs.getString("guest_context_id", null)
            if (!previousGuestContext.isNullOrBlank()) runtime.storageController.clearDataForSessionContext(previousGuestContext)
            guestContextId = "nova-guest-${UUID.randomUUID()}"
            prefs.edit().putString("guest_context_id", guestContextId).apply()
        }
        buildBrowserUi()
        observeWindowLayout()
        if (retained != null) {
            tabs.filter { it.session != null && it.engineType == BrowserEngineType.GECKOVIEW }.forEach { ensureSession(it) }
            if (tabs.isEmpty()) createTab(false, HOME_URL) else switchTab(activeIndex)
            restoreSplitPane()
        } else {
            restoreTabs()
            if (tabs.isEmpty()) createTab(false, HOME_URL)
        }
        if (NovaBrowserVpnController.isVpnEnabled()) switchAllCefriumTabsToGecko("1VPN is enabled")
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

    private fun applyFoldAwareLayout(fold: FoldingFeature?) {
        separatingFold = fold
        if (!::browserPane.isInitialized || !::browserView.isInitialized || !::secondaryPane.isInitialized) return
        val sideBySide = if (fold?.isSeparating == true) {
            fold.orientation == FoldingFeature.Orientation.VERTICAL
        } else resources.configuration.screenWidthDp >= 600
        browserPane.orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (sideBySide) {
            browserView.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            secondaryPane.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            val hingeGap = if (fold?.isSeparating == true) fold.bounds.width().coerceAtLeast(0) else 0
            secondaryPane.setPadding(hingeGap, 0, 0, 0)
        } else {
            browserView.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            secondaryPane.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            val hingeGap = if (fold?.isSeparating == true) fold.bounds.height().coerceAtLeast(0) else 0
            secondaryPane.setPadding(0, hingeGap, 0, 0)
        }
    }

    private fun buildBrowserUi() {
        topChrome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(4))
            setBackgroundColor(getColor(R.color.nova_surface))
        }
        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addressBar = AutoCompleteTextView(this).apply {
            hint = getString(R.string.address_bar_hint)
            contentDescription = getString(R.string.address_bar_accessibility)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textSize = 15f
            setTextColor(getColor(R.color.nova_text_primary))
            setHintTextColor(getColor(R.color.nova_text_secondary))
            setSelectAllOnFocus(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setBackgroundColor(getColor(R.color.nova_surface_elevated))
            minimumHeight = dp(48)
            setPadding(dp(12), 0, dp(12), 0)
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
        }
        addressSuggestionsAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, mutableListOf())
        addressBar.threshold = 1
        addressBar.setAdapter(addressSuggestionsAdapter)
        addressBar.setOnItemClickListener { _, _, position, _ ->
            val selected = addressSuggestionItems.getOrNull(position) ?: return@setOnItemClickListener
            addressBar.setText(selected.url)
            addressBar.setSelection(addressBar.text.length)
            addressBar.dismissDropDown()
        }
        addressBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateAddressSuggestions(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        addressBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) updateAddressSuggestions(addressBar.text?.toString().orEmpty()) else {
                cancelPendingRemoteSuggestions()
                addressBar.dismissDropDown()
            }
        }
        addressRow.minimumHeight = dp(48)
        addressBar.minimumHeight = dp(48)
        addressRow.addView(addressBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val clearAddress = actionButton(getString(R.string.clear_address_button)) { addressBar.text?.clear(); addressBar.requestFocus() }.apply {
            contentDescription = getString(R.string.clear_address)
            minWidth = dp(48); minimumWidth = dp(48)
        }
        addressRow.addView(clearAddress, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
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
        goButton.minWidth = dp(48)
        goButton.minimumWidth = dp(48)
        addressRow.addView(goButton, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
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
            textSize = 12f
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setTextColor(getColor(R.color.nova_text_secondary))
            setPadding(dp(4), dp(5), dp(4), dp(5))
        }
        pageStatus = TextView(this).apply {
            text = getString(R.string.ready)
            contentDescription = getString(R.string.nova_session_status)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            textSize = 11f
            setTextColor(getColor(R.color.nova_text_secondary))
            setPadding(dp(4), 0, dp(4), dp(4))
        }
        topChrome.addView(tabLabel)
        topChrome.addView(pageStatus)

        browserView = GeckoView(this)
        secondaryBrowserView = GeckoView(this)
        secondaryAddressBar = EditText(this).apply {
            hint = getString(R.string.secondary_address_bar_hint)
            isSingleLine = true
            textSize = 12f
            setTextColor(getColor(R.color.nova_text_primary))
            setHintTextColor(getColor(R.color.nova_text_secondary))
            setBackgroundColor(getColor(R.color.nova_surface_elevated))
            minimumHeight = dp(48)
            setPadding(dp(12), 0, dp(12), 0)
        }
        val secondaryControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(actionButton("Back") { tabs.firstOrNull { it.id == splitTabId }?.browserEngine?.goBack() })
            addView(actionButton("Forward") { tabs.firstOrNull { it.id == splitTabId }?.browserEngine?.goForward() })
            addView(actionButton("Reload") { tabs.firstOrNull { it.id == splitTabId }?.browserEngine?.reload() })
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
            minimumHeight = dp(48)
            setBackgroundColor(getColor(R.color.nova_surface))
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
            setBackgroundColor(getColor(R.color.nova_background))
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
            if (currentTab()?.let { !it.isPrivate && !it.isGuest } == true) {
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
        homeScroll.visibility = View.VISIBLE
        if (splitTabId != null) {
            browserPane.visibility = View.VISIBLE
            browserView.visibility = View.GONE
            secondaryPane.visibility = View.VISIBLE
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
        homeShortcuts.visibility = if (prefs.getBoolean("show_home_shortcuts", true)) View.VISIBLE else View.GONE
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
            addView(row, android.view.ViewGroup.LayoutParams(-2, dp(48)))
        }

    private fun iconButton(icon: Int, description: String, action: () -> Unit) = android.widget.ImageButton(this).apply {
        setImageResource(icon)
        contentDescription = description
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        setBackgroundResource(android.R.color.transparent)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        minimumWidth = dp(48)
        minimumHeight = dp(48)
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
            actions += getString(R.string.new_guest_tab) to { createGuestTab() }
            actions += getString(R.string.exit_guest_mode) to { confirmExitGuestMode() }
        } else {
            actions += getString(R.string.new_normal_tab) to { createTab(false, HOME_URL) }
            actions += getString(R.string.new_private_tab) to { createTab(true, HOME_URL) }
            actions += getString(R.string.new_guest_tab) to { createGuestTab() }
            actions += getString(R.string.split_view) to { chooseSplitTab() }
            actions += getString(R.string.bookmarks) to { showBookmarks() }
            if (!tab.isPrivate) actions += getString(R.string.history) to { showHistory() }
            if (!tab.isPrivate) actions += getString(R.string.downloads) to { showDownloads() }
            actions += getString(if (tab.isLoading) R.string.stop_loading else R.string.reload_page) to { toggleStopReload() }
            actions += getString(R.string.find_in_page) to { showFindBar() }
            actions += getString(if (tab.desktopSite) R.string.mobile_site else R.string.desktop_site) to { toggleDesktopSite() }
            actions += getString(if (tab.readerOriginalUrl != null) R.string.exit_reader_mode else R.string.reader_mode) to { if (tab.readerOriginalUrl != null) exitReaderMode() else openReaderMode() }
            actions += getString(R.string.translate_page) to { translatePage() }
        actions += "Quick settings" to { showQuickSettings() }
            actions += getString(R.string.share_page) to { sharePage() }
            actions += getString(R.string.nova_shield) to { showShieldSettings() }
            actions += getString(R.string.ai_assistants) to { showAiAssistant() }
            actions += "Nova Add-ons" to { showExtensions() }
            actions += getString(R.string.password_manager) to { showPasswordVault() }
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
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(7), 0, dp(7), 0)
        setOnClickListener { action() }
    }

    private fun createTab(isPrivate: Boolean, url: String, isGuest: Boolean = false) {
        cancelPendingRemoteSuggestions()
        if (currentTab()?.isGuest == true && !isGuest) {
            toast("Normal and Private tabs are unavailable in Guest Mode")
            return
        }
        deactivateTabRuntime(currentTab())
        val tab = BrowserTab(null, isPrivate, isGuest, lastUrl = url, desktopSite = isDesktopUrl(url))
        tab.engineType = resolveEngineForTab(tab)
        tabs.add(tab)
        activeIndex = tabs.lastIndex
        activateTab(tab)
        tab.lastAccess = System.nanoTime()
        if (!addressBar.hasFocus()) addressBar.setText(url)
        updateTabChrome(tab)
        if (!isPrivate && !isGuest) persistTabs()
        if (url == HOME_URL) showHomeScreen() else navigate(url)
        enforceSessionBudget()
    }

    private fun createGuestTab() {
        createTab(isPrivate = false, url = HOME_URL, isGuest = true)
        toast("Guest Mode started · temporary browsing data")
    }

    private fun isDesktopUrl(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return false
        return host in desktopSites
    }

    private fun tabKey(session: GeckoSession): String = tabs.firstOrNull { it.session === session }?.id ?: ""

    private fun ensureSession(tab: BrowserTab): GeckoSession {
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
        if (tab.needsReload && tab.lastUrl != HOME_URL) {
            engine.loadUrl(tab.lastUrl)
            tab.needsReload = false
        }
        return session
    }

    private fun ensureCefriumEngine(tab: BrowserTab, existingBrowser: CefriumBrowser? = null): CefriumBrowserEngine {
        (tab.browserEngine as? CefriumBrowserEngine)?.let { return it }
        check(!tab.isPrivate && !tab.isGuest) { "Private and Guest tabs must use GeckoView" }
        val profile = CefriumProfile.get(enginePreferences.cefriumProfileName())
        val restoredNavigationState = tab.cefriumNavigationState?.let { CefriumTabStateCipher.decrypt(it) }
        val engine = CefriumBrowserEngine(
            activity = this,
            profile = profile,
            initialNavigationState = restoredNavigationState,
            initialNovaAddons = novaScriptAddonStore.enabled(),
            listener = BrowserEngineListener { state -> runOnUiThread { applyCefriumState(tab, state) } },
            onDownloadCompleted = { download ->
                runOnUiThread {
                    novaDownloads.registerCompleted(
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
                if (!siteSettingAllowed(BrowserEngineType.CEFRIUM, tab.lastUrl, "popups", false)) { popup.close(); false }
                else handleCefriumPopup(popup, popupUrl, userGesture)
            },
            onPopupClosed = { popup -> handleCefriumPopupClosed(popup) },
            onExternalNavigation = { url, hasUserGesture -> openExternalUrlFromCefrium(url, hasUserGesture) },
            onHttpAuthRequest = { host, realm, isProxy, respond -> showCefriumHttpAuthPrompt(host, realm, isProxy, respond) },
            onContextMenu = { menu -> showCefriumContextMenu(tab, menu) },
            onBeforeUnloadDialog = { message, isReload, answer -> showCefriumBeforeUnloadPrompt(message, isReload, answer) },
            onPermissionRequestCanceled = { cancelCefriumPermissionPrompt() },
            desktopMode = tab.desktopSite,
            existingBrowser = existingBrowser,
            javaScriptEnabledForUrl = { url -> isJavaScriptAllowed(BrowserEngineType.CEFRIUM, url) },
            autoplayAllowedForUrl = { url -> siteSettingAllowed(BrowserEngineType.CEFRIUM, url, "autoplay", false) },
            adBlockingEnabledForUrl = { url -> siteSettingAllowed(BrowserEngineType.CEFRIUM, url, "ads", true) },
            soundAllowedForUrl = { url -> siteSettingAllowed(BrowserEngineType.CEFRIUM, url, "sound", true) },
            onPermissionRequest = { origin, permissions, respond ->
                runOnUiThread {
                    if (tabs.any { it.id == tab.id }) showCefriumPermissionPrompt(origin, permissions.toSet(), respond)
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

    private fun openExternalUrlFromCefrium(url: String, hasUserGesture: Boolean): Boolean {
        if (!hasUserGesture) {
            toast("Blocked an external-app link without a user gesture")
            return true
        }
        val intent = runCatching {
            if (url.startsWith("intent:", true)) Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }.getOrNull() ?: return true
        val fallbackUrl = intent.getStringExtra("browser_fallback_url")
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        return try {
            startActivity(intent)
            true
        } catch (_: Exception) {
            val safeFallback = fallbackUrl?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
            if (safeFallback != null) currentTab()?.let { loadTabUrl(it, safeFallback) }
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

    private fun cancelCefriumPermissionPrompt() {
        pendingCefriumPermissionDialog?.dismiss()
        pendingCefriumPermissionDialog = null
        pendingCefriumPermissionDecision?.invoke(false)
        pendingCefriumPermissionDecision = null
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

    private fun handleCefriumPopup(popup: CefriumBrowser, url: String, userGesture: Boolean): Boolean {
        val source = currentTab()
        if (!userGesture || source == null || source.isPrivate || source.isGuest || source.engineType != BrowserEngineType.CEFRIUM ||
            cefriumCompatibilityReason(source, url) != null) {
            popup.close()
            return true // We own and explicitly close blocked windows; returning false would load the URL in the opener.
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
        return true
    }

    private fun applyCefriumState(tab: BrowserTab, state: BrowserEngineState) {
        if (isDestroyed || tabs.none { it.id == tab.id }) return
        val previousUrl = tab.lastUrl
        val previousTitle = tab.title
        val stateUrl = state.url.orEmpty()
        val stateTitle = state.title.orEmpty()
        if (stateUrl.isNotBlank()) tab.lastUrl = stateUrl
        if (stateTitle.isNotBlank()) tab.title = stateTitle
        tab.canGoBack = state.canGoBack
        tab.canGoForward = state.canGoForward
        tab.isLoading = state.isLoading
        tab.progress = state.progress
        tab.isErrorPage = state.error != null
        val stateHost = Uri.parse(stateUrl).host ?: stateUrl
        if (stateUrl.startsWith("https://", true)) tab.securityStatus = getString(R.string.secure_connection, stateHost)
        else if (stateUrl.startsWith("http://", true)) tab.securityStatus = getString(R.string.not_secure, stateHost)
        if (state.error != null) tab.securityStatus = state.error
        if (tab.id == splitTabId && ::secondaryAddressBar.isInitialized && !secondaryAddressBar.hasFocus() && stateUrl.isNotBlank()) {
            secondaryAddressBar.setText(tab.lastUrl)
        }
        if (currentTab() === tab) {
            if (!addressBar.hasFocus() && stateUrl.isNotBlank()) addressBar.setText(tab.lastUrl)
            progressBar.progress = state.progress
            progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
            updateTabChrome(tab)
            refreshBookmarkButton()
            when {
                state.error != null -> setPageStatus(state.error, 5)
                state.isLoading -> setPageStatus(getString(R.string.loading_page), 1)
                stateUrl.isNotBlank() -> setPageStatus(getString(R.string.ready_for_host, Uri.parse(stateUrl).host ?: stateUrl), 1)
            }
        }
        if (!tab.isPrivate && !tab.isGuest && stateUrl.startsWith("http", true) && previousUrl != tab.lastUrl) {
            addHistory(tab.lastUrl)
            persistTabs()
        } else if (!tab.isPrivate && !tab.isGuest && previousTitle != tab.title && tab.lastUrl.startsWith("http", true)) {
            addHistory(tab.lastUrl)
            persistTabs()
        }
        if (!state.isLoading && stateUrl.isNotBlank()) captureTabThumbnail(tab)
    }

    private fun resolveEngineForTab(tab: BrowserTab, url: String = tab.lastUrl): BrowserEngineType {
        val profile = when {
            tab.isGuest -> BrowserProfileKind.GUEST
            tab.isPrivate -> BrowserProfileKind.PRIVATE
            else -> BrowserProfileKind.NORMAL
        }
        val selection = enginePreferences.resolve(
            url = url,
            profile = profile,
            cefriumAvailable = true,
            cefriumCompatible = cefriumCompatibilityReason(tab, url) == null
        )
        tab.engineFallbackReason = selection.reason.takeIf { selection.usedFallback }
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
        val host = uri.host ?: return null
        val hasSavedLogin = runCatching {
            passwordVault.all().any { Uri.parse(it.origin).host.equals(host, ignoreCase = true) }
        }.getOrDefault(false)
        if (hasSavedLogin) return "Saved-password autofill for this site requires GeckoView"
        return null
    }

    private fun enforceCefriumCompatibility(tab: BrowserTab) {
        if (tab.engineType != BrowserEngineType.CEFRIUM) return
        val reason = cefriumCompatibilityReason(tab) ?: return
        closeTabRuntime(tab)
        tab.engineType = BrowserEngineType.GECKOVIEW
        tab.cefriumNavigationState = null
        tab.needsReload = tab.lastUrl != HOME_URL
        tab.engineFallbackReason = reason
    }

    /** Returns the engine selected for this tab, creating its runtime lazily. */
    private fun ensureBrowserEngine(tab: BrowserTab): com.nova.browser.engine.BrowserEngine {
        enforceCefriumCompatibility(tab)
        return when (tab.engineType) {
            BrowserEngineType.CEFRIUM -> ensureCefriumEngine(tab)
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
        tab.engineFallbackReason = "Cefrium could not continue this tab. Nova reopened its URL in GeckoView; site data was not transferred."
        ensureSession(tab)
        return tab.browserEngine as? GeckoViewBrowserEngine
            ?: error("Nova could not fall back to GeckoView")
    }

    private fun activateTab(tab: BrowserTab) {
        enforceCefriumCompatibility(tab)
        if (tab.lastUrl == HOME_URL) {
            deactivateTabRuntime(tab)
            showHomeScreen()
            return
        }
        val engine = ensureBrowserEngine(tab)
        if (engine.type == BrowserEngineType.GECKOVIEW) engine.attachTo(browserView)
        showPrimaryEngineView(engine.view)
        engine.setActive(true, true)
        if (tab.needsReload && tab.lastUrl != HOME_URL) {
            engine.loadUrl(tab.lastUrl)
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
        if (tab.engineType == BrowserEngineType.CEFRIUM) {
            (tab.browserEngine as? CefriumBrowserEngine)?.serializeNavigationState()?.let { rawState ->
                tab.cefriumNavigationState = CefriumTabStateCipher.encrypt(rawState)
            }
        }
        tab.browserEngine?.close()
        tab.browserEngine = null
        tab.session = null
    }

    private fun showPrimaryEngineView(engineView: View) {
        activePrimaryEngineView?.let { old -> if (old !== engineView && old.parent === browserPane) browserPane.removeView(old) }
        (engineView.parent as? ViewGroup)?.let { parent -> if (parent !== browserPane) parent.removeView(engineView) }
        if (engineView.parent !== browserPane) browserPane.addView(engineView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        activePrimaryEngineView = engineView
        browserView.visibility = if (engineView === browserView) View.VISIBLE else View.GONE
    }

    private fun showSecondaryEngineView(engineView: View) {
        activeSecondaryEngineView?.let { old -> if (old !== engineView && old.parent === secondaryPane) secondaryPane.removeView(old) }
        (engineView.parent as? ViewGroup)?.let { parent -> if (parent !== secondaryPane) parent.removeView(engineView) }
        if (engineView.parent !== secondaryPane) secondaryPane.addView(engineView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        activeSecondaryEngineView = engineView
        secondaryBrowserView.visibility = if (engineView === secondaryBrowserView) View.VISIBLE else View.GONE
    }

    private fun selectEngineForNavigation(tab: BrowserTab, url: String) {
        val desired = resolveEngineForTab(tab, url)
        if (desired != tab.engineType) {
            closeTabRuntime(tab)
            if (desired == BrowserEngineType.CEFRIUM && tab.engineType != BrowserEngineType.CEFRIUM) tab.cefriumNavigationState = null
            tab.engineType = desired
            tab.needsReload = false
        }
    }

    private fun loadTabUrl(tab: BrowserTab, url: String) {
        if (url == HOME_URL) {
            tab.browserEngine?.stop()
            deactivateTabRuntime(tab)
            tab.lastUrl = HOME_URL
            tab.title = "Nova Home"
            if (currentTab() === tab) showHomeScreen()
            return
        }
        selectEngineForNavigation(tab, url)
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
        if (tab.engineType != BrowserEngineType.CEFRIUM) return
        closeTabRuntime(tab)
        tab.engineType = BrowserEngineType.GECKOVIEW
        tab.cefriumNavigationState = null
        tab.needsReload = tab.lastUrl != HOME_URL
        tab.engineFallbackReason = reason
        activateTab(tab)
        toast("Switched this tab to GeckoView: $reason")
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

    private fun switchCefriumTabsForSavedLogins() {
        val savedHosts = runCatching { passwordVault.all().mapNotNull { Uri.parse(it.origin).host?.lowercase() }.toSet() }.getOrDefault(emptySet())
        val changed = tabs.filter { tab ->
            tab.engineType == BrowserEngineType.CEFRIUM && Uri.parse(tab.lastUrl).host?.lowercase() in savedHosts
        }
        if (changed.isEmpty()) return
        changed.forEach { tab ->
            closeTabRuntime(tab)
            tab.engineType = BrowserEngineType.GECKOVIEW
            tab.cefriumNavigationState = null
            tab.needsReload = tab.lastUrl != HOME_URL
            tab.engineFallbackReason = "Saved-password autofill requires GeckoView"
        }
        currentTab()?.let { if (it in changed) activateTab(it) }
        if (splitTabId != null && changed.any { it.id == splitTabId }) restoreSplitPane()
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
        AlertDialog.Builder(this).setTitle("Default Cefrium permissions")
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

    private fun showCefriumPermissionPrompt(origin: String, permissions: Set<String>, respond: (Boolean) -> Unit) {
        val normalized = CefriumSitePermissionPolicy.normalize(origin, permissions)
        if (normalized == null) { respond(false); return }
        cancelCefriumPermissionPrompt()
        val labels = normalized.map { it.lowercase().replace('_', ' ') }
        val runtimePermissions = buildList {
            if ("CAMERA" in normalized) add(Manifest.permission.CAMERA)
            if ("MICROPHONE" in normalized) add(Manifest.permission.RECORD_AUDIO)
            if ("GEOLOCATION" in normalized) add(Manifest.permission.ACCESS_FINE_LOCATION)
            if ("NOTIFICATIONS" in normalized && Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val decisions = normalized.map { cefriumPermissionDecision(origin, it) }
        if (decisions.any { it == "BLOCK" }) { respond(false); return }
        val mustAskInNova = decisions.any { it == "ASK" }
        var completed = false
        var handedToAndroid = false
        fun finish(granted: Boolean) {
            if (!completed) { completed = true; respond(granted) }
        }
        fun requestAndroidPermissions() {
            val missing = runtimePermissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) finish(true) else {
                handedToAndroid = true
                pendingCefriumPermissionDecision = { granted -> finish(granted) }
                cefriumPermissionLauncher.launch(missing.toTypedArray())
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
        registerPromptDialog(dialog) { if (!handedToAndroid) finish(false) }
    }

    private fun setBrowserFullscreen(fullScreen: Boolean, tab: BrowserTab? = currentTab()) {
        isFullscreen = fullScreen
        fullscreenTabId = if (fullScreen) tab?.id else null
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
                    else -> { finish(prompt.dismiss()); return result }
                }
                val dialog = builder.setNegativeButton(android.R.string.cancel) { _, _ -> finish(prompt.dismiss()) }.create()
                registerPromptDialog(dialog) { finish(prompt.dismiss()) }
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
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(prompt.title ?: getString(R.string.page_confirmation))
                    .setMessage(prompt.message.orEmpty())
                    .setPositiveButton(android.R.string.ok) { _, _ -> finish(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE)) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> finish(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE)) }
                    .create()
                registerPromptDialog(dialog) { finish(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE)) }
                return result
            }

            override fun onTextPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.TextPrompt): GeckoResult<PromptResponse> {
                val result = GeckoResult<PromptResponse>()
                val input = EditText(this@MainActivity).apply { setText(prompt.defaultValue.orEmpty()); setSelectAllOnFocus(true) }
                var completed = false
                fun finish(response: PromptResponse) { if (!completed) { completed = true; result.complete(response) } }
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(prompt.title ?: getString(R.string.page_input))
                    .setMessage(prompt.message.orEmpty())
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ -> finish(prompt.confirm(input.text.toString())) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> finish(prompt.dismiss()) }
                    .create()
                registerPromptDialog(dialog) { finish(prompt.dismiss()) }
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
                val host = prompt.authOptions.uri?.let { Uri.parse(it).host }.orEmpty()
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(prompt.title ?: getString(R.string.authentication_required))
                    .setMessage(listOf(host, prompt.message.orEmpty()).filter { it.isNotBlank() }.joinToString("\n"))
                    .setView(form)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val response = if (passwordOnly) prompt.confirm(password.text.toString()) else prompt.confirm(username.text.toString(), password.text.toString())
                        finish(response)
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> finish(prompt.dismiss()) }
                    .create()
                registerPromptDialog(dialog) { finish(prompt.dismiss()) }
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
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(prompt.title ?: getString(R.string.date_time_input))
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ -> finish(prompt.confirm(input.text.toString())) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> finish(prompt.dismiss()) }
                    .create()
                registerPromptDialog(dialog) { finish(prompt.dismiss()) }
                return result
            }

            override fun onColorPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.ColorPrompt): GeckoResult<PromptResponse> {
                val result = GeckoResult<PromptResponse>()
                val input = EditText(this@MainActivity).apply { hint = getString(R.string.color_hex_hint); setText(prompt.defaultValue.orEmpty()) }
                var completed = false
                fun finish(response: PromptResponse) { if (!completed) { completed = true; result.complete(response) } }
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(prompt.title ?: getString(R.string.choose_color))
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ -> finish(prompt.confirm(input.text.toString())) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> finish(prompt.dismiss()) }
                    .create()
                registerPromptDialog(dialog) { finish(prompt.dismiss()) }
                return result
            }

            override fun onPopupPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.PopupPrompt): GeckoResult<PromptResponse> {
                val result = GeckoResult<PromptResponse>()
                var completed = false
                fun finish(response: PromptResponse) { if (!completed) { completed = true; result.complete(response) } }
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.popup_request))
                    .setMessage(prompt.targetUri.orEmpty())
                    .setPositiveButton(getString(R.string.allow)) { _, _ -> finish(prompt.confirm(AllowOrDeny.ALLOW)) }
                    .setNegativeButton(getString(R.string.block)) { _, _ -> finish(prompt.confirm(AllowOrDeny.DENY)) }
                    .create()
                registerPromptDialog(dialog) { finish(prompt.confirm(AllowOrDeny.DENY)) }
                return result
            }

            override fun onBeforeUnloadPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt): GeckoResult<PromptResponse> {
                val result = GeckoResult<PromptResponse>()
                var completed = false
                fun finish(response: PromptResponse) { if (!completed) { completed = true; result.complete(response) } }
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.leave_page_title))
                    .setMessage(getString(R.string.leave_page_message))
                    .setPositiveButton(getString(R.string.leave_page)) { _, _ -> finish(prompt.confirm(AllowOrDeny.ALLOW)) }
                    .setNegativeButton(getString(R.string.stay_page)) { _, _ -> finish(prompt.confirm(AllowOrDeny.DENY)) }
                    .create()
                registerPromptDialog(dialog) { finish(prompt.confirm(AllowOrDeny.DENY)) }
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
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.save_password_title))
                    .setMessage(getString(R.string.save_password_message, Uri.parse(login.origin).host ?: login.origin))
                    .setPositiveButton(getString(R.string.save_securely)) { _, _ -> finish(request.confirm(option)) }
                    .setNegativeButton(getString(R.string.not_now)) { _, _ -> finish(request.dismiss()) }
                    .create()
                registerPromptDialog(dialog) { finish(request.dismiss()) }
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
                val dialog = AlertDialog.Builder(this@MainActivity).setTitle(getString(R.string.choose_saved_login))
                    .setItems(choices) { _, which -> finish(request.confirm(request.options[which])) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> finish(request.dismiss()) }
                    .create()
                registerPromptDialog(dialog) { finish(request.dismiss()) }
                return result
            }

            override fun onFolderUploadPrompt(session: GeckoSession, prompt: FolderUploadPrompt): GeckoResult<PromptResponse> {
                val result = GeckoResult<PromptResponse>()
                var completed = false
                fun finish(response: PromptResponse) { if (!completed) { completed = true; result.complete(response) } }
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.upload_folder_title))
                    .setMessage(getString(R.string.upload_folder_message, prompt.directoryName ?: getString(R.string.selected_folder)))
                    .setPositiveButton(getString(R.string.allow)) { _, _ -> finish(prompt.confirm(AllowOrDeny.ALLOW)) }
                    .setNegativeButton(getString(R.string.deny)) { _, _ -> finish(prompt.confirm(AllowOrDeny.DENY)) }
                    .create()
                registerPromptDialog(dialog) { finish(prompt.confirm(AllowOrDeny.DENY)) }
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
                val missing = permissions?.distinct().orEmpty().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) { callback.grant(); return }
                if (pendingAndroidPermissionCallback != null) { callback.reject(); return }
                pendingAndroidPermissionCallback = callback
                requestPermissions(missing.toTypedArray(), REQUEST_SITE_PERMISSIONS)
            }

            @OptIn(org.mozilla.geckoview.ExperimentalGeckoViewApi::class)
            override fun onContentPermissionRequest(session: GeckoSession, perm: GeckoSession.PermissionDelegate.ContentPermission): GeckoResult<Int> {
                if (perm.value == GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW) return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                if (perm.value == GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY) return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
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
                val requested = buildList {
                    if (!video.isNullOrEmpty()) add(getString(R.string.camera))
                    if (!audio.isNullOrEmpty()) add(getString(R.string.microphone))
                }.joinToString(getString(R.string.and_separator))
                var completed = false
                fun finish(allow: Boolean) {
                    if (completed) return
                    completed = true
                    if (allow) callback.grant(video?.firstOrNull(), audio?.firstOrNull()) else callback.reject()
                }
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.media_permission_title))
                    .setMessage(getString(R.string.media_permission_message, host, requested))
                    .setPositiveButton(getString(R.string.allow)) { _, _ -> finish(true) }
                    .setNegativeButton(getString(R.string.block)) { _, _ -> finish(false) }
                    .create()
                registerPromptDialog(dialog) { finish(false) }
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            @Suppress("DEPRECATION")
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                runOnUiThread { setBrowserFullscreen(fullScreen, tab) }
            }

            override fun onCrash(session: GeckoSession) { handleTabCrash(session) }
            override fun onKill(session: GeckoSession) { handleTabCrash(session) }

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
                if (image != null && element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE) actions += getString(R.string.save_image) to {
                    runCatching {
                        val fileName = "Nova-${Uri.parse(image).lastPathSegment ?: "image"}".replace(Regex("[^A-Za-z0-9._-]"), "_")
                        val request = android.app.DownloadManager.Request(Uri.parse(image))
                            .setTitle(getString(R.string.saved_image))
                            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName)
                        (getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager).enqueue(request)
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
                novaDownloads.saveExternalStream(body, rawName, mime.substringBefore(';'))
                toast("Webpage download received · saving to Downloads/Nova")
            }
            override fun onTitleChange(session: GeckoSession, title: String?) {
                if (!title.isNullOrBlank()) {
                    val tab = tabs.firstOrNull { it.session === session } ?: return
                    if (tab.renamedTitle.isNullOrBlank()) tab.title = title
                    if (currentTab() === tab) updateTabChrome(tab)
                }
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                if (!(tab.isErrorPage && url.startsWith("data:"))) tab.isErrorPage = false
                tab.isLoading = true
                if (!tab.isErrorPage) tab.securityStatus = ""
                if (currentTab() === tab) runOnUiThread {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = 0
                    if (!tab.isErrorPage) resetPageStatus(getString(R.string.loading_page), 3)
                }
            }
            override fun onProgressChange(session: GeckoSession, progress: Int) {
                if (currentTab() === tab) runOnUiThread { progressBar.progress = progress.coerceIn(0, 100) }
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                tab.isLoading = false
                if (currentTab() === tab) runOnUiThread {
                    progressBar.visibility = View.GONE
                    if (success && !tab.isErrorPage) captureTabThumbnail(tab)
                    setPageStatus(if (tab.isErrorPage) tab.securityStatus else if (success) tab.securityStatus.ifBlank { getString(R.string.ready_for_host, Uri.parse(tab.lastUrl).host ?: "Nova") } else getString(R.string.page_failed_to_load), if (tab.isErrorPage || !success) 5 else 4)
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
                tabs.add(newTab)
                activeIndex = tabs.lastIndex
                popupEngine.attachTo(browserView)
                showPrimaryEngineView(popupEngine.view)
                popupEngine.setActive(true, true)
                if (!addressBar.hasFocus()) addressBar.setText(uri)
                updateTabChrome(newTab)
                if (!newTab.isPrivate && !newTab.isGuest) persistTabs()
                showBrowserPage()
                return GeckoResult.fromValue(newSession)
            }
            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
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
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { tab.canGoBack = canGoBack }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) { tab.canGoForward = canGoForward }
            override fun onLocationChange(session: GeckoSession, url: String?, perms: List<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                if (url.isNullOrBlank()) return
                if (tab.isErrorPage && url.startsWith("data:")) return
                tab.lastUrl = url
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



    private fun enforceSessionBudget() {
        val keep = tabs.sortedByDescending { if (it === currentTab()) Long.MAX_VALUE else it.lastAccess }
            .take(4).toSet()
        tabs.forEach { tab ->
            if (tab !in keep && tab.browserEngine != null && tab.id != splitTabId) {
                closeTabRuntime(tab)
                tab.needsReload = tab.lastUrl != HOME_URL
            }
        }
        val activeTab = currentTab()
        if (activeTab?.isGuest == true) resetPageStatus(getString(R.string.guest_session_status), 0)
        else if (activeTab?.isPrivate == true) resetPageStatus(getString(R.string.private_session_status), 0)
        else setPageStatus(getString(R.string.memory_saver_status), 1)
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

    private fun handleTabCrash(session: GeckoSession) {
        val tab = tabs.firstOrNull { it.session === session } ?: return
        closeTabRuntime(tab)
        tab.engineType = BrowserEngineType.GECKOVIEW
        tab.cefriumNavigationState = null
        tab.needsReload = true
        tab.hasCrashed = true
        if (currentTab() === tab) {
            runOnUiThread {
                showHomeScreen()
                setPageStatus(getString(R.string.tab_crashed_status), 5)
                showCrashedTabDialog(tab)
            }
        }
    }

    private fun showCrashedTabDialog(tab: BrowserTab) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.tab_crashed_title))
            .setMessage(getString(R.string.tab_crashed_message))
            .setPositiveButton(getString(R.string.reload_page)) { _, _ ->
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

    private fun denyGuestAccess(feature: String): Boolean {
        if (currentTab()?.isGuest != true) return false
        resetPageStatus(getString(R.string.guest_session_status), 0)
        toast(getString(R.string.guest_feature_unavailable, feature))
        return true
    }

    private fun switchTab(index: Int) {
        if (index !in tabs.indices) return
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
        val candidates = tabs.filter { !it.isPrivate && !it.isGuest && it !== currentTab() }
        if (candidates.isEmpty()) { toast("Open another normal tab before using split screen"); return }
        AlertDialog.Builder(this).setTitle("Open second pane")
            .setItems(candidates.map { it.title.ifBlank { it.lastUrl } }.toTypedArray()) { _, which ->
                val tab = candidates[which]
                if (splitTabId != null) exitSplitScreen()
                enforceCefriumCompatibility(tab)
                val engine = ensureBrowserEngine(tab)
                if (engine.type == BrowserEngineType.GECKOVIEW) engine.attachTo(secondaryBrowserView)
                showSecondaryEngineView(engine.view)
                engine.setActive(true, false)
                secondaryAddressBar.setText(tab.lastUrl)
                secondaryPane.visibility = View.VISIBLE
                browserPane.visibility = View.VISIBLE
                homeScroll.visibility = View.GONE
                splitTabId = tab.id
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
        enforceSessionBudget()
    }

    private fun restoreSplitPane() {
        val second = tabs.firstOrNull { it.id == splitTabId } ?: run { splitTabId = null; return }
        enforceCefriumCompatibility(second)
        val engine = ensureBrowserEngine(second)
        if (engine.type == BrowserEngineType.GECKOVIEW) engine.attachTo(secondaryBrowserView)
        showSecondaryEngineView(engine.view)
        if (::secondaryAddressBar.isInitialized) secondaryAddressBar.setText(second.lastUrl)
        secondaryPane.visibility = View.VISIBLE
        browserPane.visibility = View.VISIBLE
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
        val target = BrowserUrlResolver.resolve(input, searchEngine) ?: return
        prepareDesktopModeForNavigation(tab, target)
        loadSecondaryTabUrl(tab, target)
        if (!secondaryAddressBar.hasFocus()) secondaryAddressBar.setText(target)
        if (!tab.isPrivate && !tab.isGuest) persistTabs()
    }


    private fun loadSecondaryTabUrl(tab: BrowserTab, url: String) {
        selectEngineForNavigation(tab, url)
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
        }
        readBookmarks().asSequence().take(80).forEach { entry ->
            candidates += BrowserSuggestion(entry.title.ifBlank { entry.url }, entry.url)
        }
        val ranked = BrowserUiState.filterSuggestions(typed, candidates, 6)
        val searchUrl = BrowserUrlResolver.resolve(typed, searchEngine)
        val searchSuggestion = searchUrl?.let { BrowserSuggestion(getString(R.string.search_for_query, typed), it) }
        addressSuggestionItems = (ranked + listOfNotNull(searchSuggestion)).distinctBy { it.url }.take(7)
        addressSuggestionsAdapter.clear()
        addressSuggestionsAdapter.addAll(addressSuggestionItems.map { it.label })
        if (addressSuggestionItems.isNotEmpty()) addressBar.showDropDown() else addressBar.dismissDropDown()
        scheduleRemoteSuggestions(typed, ranked, searchSuggestion)
    }

    private fun cancelPendingRemoteSuggestions() {
        pendingRemoteSuggestion?.let(addressSuggestionHandler::removeCallbacks)
        pendingRemoteSuggestion = null
        activeRemoteSuggestionFuture?.cancel(true)
        activeRemoteSuggestionFuture = null
    }

    private fun scheduleRemoteSuggestions(query: String, local: List<BrowserSuggestion>, searchSuggestion: BrowserSuggestion?) {
        cancelPendingRemoteSuggestions()
        val tab = currentTab() ?: return
        val enabled = prefs.getBoolean("remote_search_suggestions", false)
        if (!RemoteSearchSuggestionPolicy.shouldFetch(enabled, tab.isPrivate, tab.isGuest, query)) return
        val requestedSearchEngine = searchEngine
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
                if (Thread.currentThread().isInterrupted) return@submit
                val remote = RemoteSearchSuggestions.fetch(query, requestedSearchEngine)
                if (Thread.currentThread().isInterrupted) return@submit
                runOnUiThread {
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
                    addressSuggestionsAdapter.addAll(addressSuggestionItems.map { it.label })
                    if (addressSuggestionItems.isNotEmpty()) addressBar.showDropDown()
                }
            }
        }
        pendingRemoteSuggestion = request
        addressSuggestionHandler.postDelayed(request, 350L)
    }

    private fun submitAddressBar() {
        val query = addressBar.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) return
        navigate(query)
        dismissAddressBarInput()
    }

    private fun navigate(input: String) {
        val target = BrowserUrlResolver.resolve(input, searchEngine) ?: return
        val tab = currentTab() ?: return
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

    private fun reloadPage() { currentTab()?.let { ensureBrowserEngine(it).reload() } }

    private fun toggleStopReload() {
        val tab = currentTab() ?: return
        if (tab.isLoading) tab.browserEngine?.stop() else ensureBrowserEngine(tab).reload()
    }

    private fun captureTabThumbnail(tab: BrowserTab) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val width = window.decorView.width
        val height = window.decorView.height
        if (width <= 0 || height <= 0) return
        val bitmap = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return
        PixelCopy.request(window, bitmap, { result ->
            if (result == PixelCopy.SUCCESS && tabs.any { it.id == tab.id }) {
                tab.thumbnail?.takeIf { it !== bitmap }?.recycle()
                tab.thumbnail = bitmap
            } else bitmap.recycle()
        }, android.os.Handler(android.os.Looper.getMainLooper()))
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
                    card.addView(actionButton("Open") { tabDialog?.dismiss(); switchTab(index) })
                    card.addView(actionButton("Group / rename") { tabDialog?.dismiss(); editTabGroup(index) })
                    card.addView(actionButton("Close") { tabDialog?.dismiss(); closeTabAt(index) })
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
        if (removed.id == splitTabId) exitSplitScreen()
        closeTabRuntime(removed)
        removed.thumbnail?.recycle(); removed.thumbnail = null
        cleanupGuestContextIfIdle()
        if (removed.isGuest && wasGuestActive && tabs.none { it.isGuest }) {
            createGuestTab()
            return
        }
        if (tabs.isEmpty()) {
            activeIndex = 0
            createTab(false, HOME_URL)
            return
        }
        if (index < activeIndex) activeIndex--
        else if (wasActive) activeIndex = index.coerceAtMost(tabs.lastIndex)
        persistTabs()
        if (wasActive) switchTab(activeIndex.coerceIn(tabs.indices)) else currentTab()?.let { updateTabChrome(it) }
    }

    private fun saveNamedSession() {
        if (denyGuestAccess("Saving workspaces")) return
        val input = EditText(this).apply { hint = "Session name"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Save workspace").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "Saved workspace" }
                val urls = JSONArray()
                tabs.filter { !it.isPrivate && !it.isGuest }.forEach { urls.put(JSONObject().put("url", it.lastUrl).put("group", it.group).put("title", it.title).put("renamedTitle", it.renamedTitle ?: "")) }
                val snapshots = prefs.getStringSet("saved_workspaces", emptySet())?.toMutableSet() ?: mutableSetOf()
                snapshots.removeAll { raw -> runCatching { JSONObject(raw).optString("name") == name }.getOrDefault(false) }
                val workspaceTombstones = runCatching { JSONObject(prefs.getString("workspace_tombstones", "{}") ?: "{}") }.getOrDefault(JSONObject())
                val createdAt = maxOf(System.currentTimeMillis(), workspaceTombstones.optLong(name) + 1L)
                snapshots.add(JSONObject().put("name", name).put("createdAt", createdAt).put("tabs", urls).toString())
                prefs.edit().putStringSet("saved_workspaces", snapshots).apply()
                toast("Workspace saved")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun persistTabs() {
        val normalTabs = tabs.filter { !it.isPrivate && !it.isGuest }
        normalTabs.forEach { tab ->
            (tab.browserEngine as? CefriumBrowserEngine)?.serializeNavigationState()?.let { rawState ->
                tab.cefriumNavigationState = CefriumTabStateCipher.encrypt(rawState)
            }
        }
        val saved = JSONArray()
        normalTabs.forEach { tab ->
            val encryptedState = tab.cefriumNavigationState?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) } ?: ""
            saved.put(JSONObject().put("url", tab.lastUrl).put("group", tab.group).put("title", tab.title)
                .put("readerOriginalUrl", tab.readerOriginalUrl ?: "").put("renamedTitle", tab.renamedTitle ?: "")
                .put("engineType", tab.engineType.name).put("cefriumNavigationState", encryptedState))
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
            if (tabs.size > firstNewIndex) { activeIndex = tabs.lastIndex; switchTab(activeIndex) }
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

    private fun saveBookmarks(items: List<BookmarkEntry>, trackDeletes: Boolean = true) {
        if (trackDeletes) {
            val previous = readBookmarks()
            val retained = items.map { it.url }.toSet()
            val removed = previous.filterNot { it.url in retained }
            if (removed.isNotEmpty()) {
                val tombstones = runCatching { JSONObject(prefs.getString("bookmark_tombstones", "{}") ?: "{}") }.getOrDefault(JSONObject())
                val deletedAt = System.currentTimeMillis()
                removed.forEach { tombstones.put(it.url, maxOf(deletedAt, it.addedAt + 1L)) }
                prefs.edit().putString("bookmark_tombstones", tombstones.toString()).apply()
            }
        }
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("url", it.url).put("title", it.title).put("folder", it.folder).put("addedAt", it.addedAt)) }
        prefs.edit().putString("bookmarks_v2", array.toString()).putStringSet("bookmarks", items.map { it.url }.toSet()).apply()
    }

    private fun toggleBookmark() {
        val tab = currentTab() ?: return
        if (tab.isPrivate || tab.isGuest) { toast("Private and Guest tabs cannot save bookmarks"); return }
        if (tab.lastUrl == HOME_URL) { toast("Open a page before bookmarking"); return }
        val items = readBookmarks()
        val existing = items.indexOfFirst { it.url == tab.lastUrl }
        if (existing >= 0) { items.removeAt(existing); saveBookmarks(items); refreshBookmarkButton(); toast("Bookmark removed") }
        else {
            val priorDelete = runCatching { JSONObject(prefs.getString("bookmark_tombstones", "{}") ?: "{}").optLong(tab.lastUrl) }.getOrDefault(0L)
            items.add(BookmarkEntry(tab.lastUrl, tab.title, "General", maxOf(System.currentTimeMillis(), priorDelete + 1L)))
            saveBookmarks(items); refreshBookmarkButton(); toast("Bookmark saved")
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

    private fun showBookmarkActions(entry: BookmarkEntry, onChanged: () -> Unit = {}) {
        showBottomSheetMenu(entry.title, listOf(
            getString(R.string.edit_bookmark) to { editBookmark(entry, onChanged) },
            getString(R.string.delete_bookmark) to {
                AlertDialog.Builder(this).setTitle(getString(R.string.delete_bookmark)).setMessage(getString(R.string.delete_bookmark_confirm))
                    .setNegativeButton(getString(R.string.cancel), null)
                    .setPositiveButton(getString(R.string.delete)) { _, _ ->
                        val previous = readBookmarks()
                        saveBookmarks(previous.filterNot { it.url == entry.url })
                        onChanged(); refreshBookmarkButton()
                        showUndoSnackbar(getString(R.string.bookmark_deleted), getString(R.string.undo)) { saveBookmarks(previous); onChanged(); refreshBookmarkButton() }
                    }.show()
            },
            getString(R.string.open) to { navigate(entry.url) }
        ))
    }

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
                saveBookmarks(items); onChanged(); toast(getString(R.string.bookmark_updated))
            }.setNegativeButton(getString(R.string.cancel), null).show()
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
            }.setNegativeButton(getString(R.string.cancel), null).show()
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
                prefs.edit().putLong("history_cleared_at", System.currentTimeMillis()).remove("history").remove("history_v2").apply()
                toast(getString(R.string.history_cleared))
                onCleared()
            }.show()
    }

    private fun addHistory(url: String) {
        if (url.isBlank() || url == HOME_URL) return
        val items = readHistory().sortedBy { it.visitedAt }.filterNot { it.url == url }.toMutableList()
        val visitedAt = maxOf(System.currentTimeMillis(), prefs.getLong("history_cleared_at", 0L) + 1L)
        items.add(HistoryEntry(url, Uri.parse(url).host ?: url, visitedAt))
        val array = JSONArray()
        items.takeLast(500).forEach { array.put(JSONObject().put("url", it.url).put("title", it.title).put("visitedAt", it.visitedAt)) }
        prefs.edit().putString("history_v2", array.toString()).apply()
    }

    private fun readHistory(): List<HistoryEntry> {
        val raw = prefs.getString("history_v2", null)
        if (raw != null) return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { i -> val item = array.getJSONObject(i); HistoryEntry(item.optString("url"), item.optString("title"), item.optLong("visitedAt")) }
        }.getOrDefault(emptyList())
        return (prefs.getStringSet("history", emptySet()) ?: emptySet()).map { HistoryEntry(it, Uri.parse(it).host ?: it, 0L) }
    }

    private fun showHistory() {
        if (currentTab()?.let { it.isPrivate || it.isGuest } == true) {
            toast(getString(R.string.history_unavailable_private))
            return
        }
        val items = readHistory().sortedByDescending { it.visitedAt }
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
        content.addView(searchRow)
        content.addView(listContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.history_count, items.size))
            .setView(content)
            .setNeutralButton(getString(R.string.clear_history), null)
            .setNegativeButton(getString(R.string.close), null)
            .create()
        var filteredItems = items
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
                    search.text.clear()
                    filteredItems = emptyList()
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
        runCatching { novaDownloads.enqueue(url); toast("Download started · resumable") }
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
        if (denyGuestAccess(getString(R.string.downloads))) return
        downloadsItems = novaDownloads.items()
        if (downloadsItems.isEmpty()) { downloadPrompt(); return }
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
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(emptyView, LinearLayout.LayoutParams(-1, 0, 1f)); addView(listView, LinearLayout.LayoutParams(-1, 0, 1f)) }
        listView.setOnItemClickListener { _, _, which, _ -> downloadsItems.getOrNull(which)?.let { showDownloadActions(it) } }
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.downloads_count, downloadsItems.size)).setView(container)
            .setPositiveButton(getString(R.string.new_download), null)
            .setNeutralButton(getString(R.string.refresh), null)
            .setNegativeButton(getString(R.string.close), null).create()
        downloadsDialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { dialog.dismiss(); downloadPrompt() }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { refreshDownloadsList(novaDownloads.items()) }
        }
        dialog.setOnDismissListener { if (downloadsDialog === dialog) { downloadsDialog = null; downloadsAdapter = null } }
        dialog.show()
    }

    private fun showDownloadActions(item: NovaDownloadManager.Item) {
        val actions: List<Pair<String, () -> Unit>> = when (item.state) {
            "RUNNING" -> listOf(getString(R.string.pause) to { novaDownloads.pause(item.id) }, getString(R.string.cancel_download) to { confirmRemoveDownload(item, cancel = true) })
            "PAUSED" -> listOf(getString(R.string.resume) to { novaDownloads.resume(item.id) }, getString(R.string.cancel_download) to { confirmRemoveDownload(item, cancel = true) })
            "QUEUED" -> listOf(getString(R.string.cancel_download) to { confirmRemoveDownload(item, cancel = true) })
            "FINALIZING", "SAVING" -> listOf(getString(R.string.close) to { })
            "COMPLETE" -> listOf(getString(R.string.open_download) to { openDownloadedFile(item) }, getString(R.string.remove_from_list) to { confirmRemoveDownload(item) })
            "INTERRUPTED" -> if (item.url.isNotBlank()) listOf(getString(R.string.resume) to { novaDownloads.resume(item.id) }, getString(R.string.remove_from_list) to { confirmRemoveDownload(item) }) else listOf(getString(R.string.remove_from_list) to { confirmRemoveDownload(item) })
            else -> if (item.url.isNotBlank()) listOf(getString(R.string.retry_download) to { novaDownloads.resume(item.id) }, getString(R.string.remove_from_list) to { confirmRemoveDownload(item) }) else listOf(getString(R.string.remove_from_list) to { confirmRemoveDownload(item) })
        }
        showBottomSheetMenu(item.fileName, actions)
    }

    private fun confirmRemoveDownload(item: NovaDownloadManager.Item, cancel: Boolean = false) {
        AlertDialog.Builder(this)
            .setTitle(getString(if (cancel) R.string.cancel_download else R.string.remove_download_title))
            .setMessage(getString(if (cancel) R.string.cancel_download_message else R.string.remove_download_message))
            .setNegativeButton(getString(R.string.keep), null)
            .setPositiveButton(getString(if (cancel) R.string.cancel_download else R.string.remove_from_list)) { _, _ ->
                novaDownloads.cancel(item.id)
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
        if (tab.isPrivate || tab.isGuest) { toast("Desktop preference is not saved in private or Guest Mode"); return }
        val host = Uri.parse(tab.lastUrl).host ?: run { toast("Open a website first"); return }
        val sites = desktopSites
        tab.desktopSite = if (host in sites) { sites.remove(host); false } else { sites.add(host); true }
        prefs.edit().putStringSet("desktop_sites", sites).apply()
        val activeEngine = ensureBrowserEngine(tab)
        if (activeEngine is CefriumBrowserEngine) {
            runCatching { activeEngine.setDesktopMode(tab.desktopSite); activeEngine.reload() }
                .onFailure { failure ->
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
            engine.loadUrl(tab.lastUrl)
        }
        toast(if (tab.desktopSite) "Desktop site enabled for $host" else "Mobile site restored for $host")
    }

    private fun openReaderMode() {
        val tab = currentTab() ?: return
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
        if (tab.engineType == BrowserEngineType.CEFRIUM) { toast("On-device translation currently requires GeckoView; switch this tab's engine first"); return }
        val languages = arrayOf(
            "English" to "en", "Hindi" to "hi", "Bengali" to "bn",
            "Japanese" to "ja", "Chinese" to "zh", "Spanish" to "es", "French" to "fr",
            "German" to "de", "Arabic" to "ar", "Portuguese" to "pt", "Russian" to "ru"
        )
        var source = detectedPageLanguages[tab.id] ?: "en"
        val sourceIndex = languages.indexOfFirst { it.second == source }.let { if (it >= 0) it else 0 }
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

    private fun cleanupGuestContextIfIdle() {
        if (tabs.none { it.isGuest } && guestContextId.isNotBlank()) {
            runtime.storageController.clearDataForSessionContext(guestContextId)
            guestContextId = "nova-guest-${UUID.randomUUID()}"
            prefs.edit().putString("guest_context_id", guestContextId).apply()
        }
    }

    private fun prepareDesktopModeForNavigation(tab: BrowserTab, target: String) {
        val desired = if (tab.isPrivate || tab.isGuest) tab.desktopSite else isDesktopUrl(target)
        if (desired != tab.desktopSite) {
            tab.desktopSite = desired
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
                val newSession = ensureSession(tab)
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
        val engines = arrayOf("Google", "Bing", "DuckDuckGo", "Brave Search")
        AlertDialog.Builder(this).setTitle("Default search engine")
            .setSingleChoiceItems(engines, engines.indexOf(searchEngine)) { dialog, which ->
                searchEngine = engines[which]
                searchEngineButton.text = getString(R.string.search_engine_button, searchEngine)
                searchEngineButton.contentDescription = getString(R.string.search_engine_accessibility, searchEngine)
                prefs.edit().putString("search_engine", searchEngine).apply()
                dialog.dismiss(); toast("$searchEngine selected")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun sharePage() {
        val url = currentTab()?.lastUrl ?: return
        if (url == HOME_URL) { toast("Nothing to share on the home screen"); return }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url)
        }, "Share page"))
    }

    private fun configureExtensionPermissions() {
        runtime.getWebExtensionController().setPromptDelegate(object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<out String>,
                origins: Array<out String>,
                dataCollectionPermissions: Array<out String>
            ): GeckoResult<WebExtension.PermissionPromptResponse> {
                val result = GeckoResult<WebExtension.PermissionPromptResponse>()
                var completed = false
                fun finish(granted: Boolean) {
                    if (!completed) {
                        completed = true
                        result.complete(WebExtension.PermissionPromptResponse(granted, false, false))
                    }
                }
                val detail = buildString {
                    append(getString(R.string.extension_id)).append(extension.id)
                    if (permissions.isNotEmpty()) append(getString(R.string.extension_permissions)).append(permissions.joinToString("\n• ", prefix = "• "))
                    if (origins.isNotEmpty()) append(getString(R.string.extension_website_access)).append(origins.joinToString("\n• ", prefix = "• "))
                    if (dataCollectionPermissions.isNotEmpty()) append(getString(R.string.extension_data_collection)).append(dataCollectionPermissions.joinToString("\n• ", prefix = "• "))
                    append(getString(R.string.extension_privacy_note))
                }
                runOnUiThread {
                    val dialog = AlertDialog.Builder(this@MainActivity).setTitle(getString(R.string.extension_install_title))
                        .setMessage(detail)
                        .setPositiveButton(getString(R.string.grant_permissions)) { _, _ -> finish(true) }
                        .setNegativeButton(getString(R.string.cancel), null)
                        .create()
                    registerPromptDialog(dialog) { finish(false) }
                }
                return result
            }

            override fun onOptionalPrompt(extension: WebExtension, permissions: Array<out String>, origins: Array<out String>, dataCollectionPermissions: Array<out String>): GeckoResult<AllowOrDeny> = GeckoResult.deny()
            override fun onUpdatePrompt(extension: WebExtension, newPermissions: Array<out String>, newOrigins: Array<out String>, newDataCollectionPermissions: Array<out String>): GeckoResult<AllowOrDeny> = GeckoResult.deny()
        })
    }

    private fun showShieldSettings() {
        if (denyGuestAccess("Nova Shield settings")) return
        val modes = ShieldMode.values()
        val current = NovaShield.fromStored(prefs.getString("shield_mode", "STANDARD"))
        val tab = currentTab()
        val host = tab?.lastUrl?.let { runCatching { Uri.parse(it).host }.getOrNull() }
        val items = arrayOf("Off", "Standard · recommended", "Aggressive · may break sites", "Allow trackers on this site", "Show this page's protection log")
        AlertDialog.Builder(this).setTitle("Nova Shield · ${current.name}")
            .setItems(items) { _, which ->
                when (which) {
                    0, 1, 2 -> {
                        val mode = modes[which]
                        prefs.edit().putString("shield_mode", mode.name).apply()
                        NovaShield.apply(runtime, mode)
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
        if (currentTab()?.isGuest != true) { toast("Tor routing is available only in Guest Mode"); return }
        val launch = packageManager.getLaunchIntentForPackage("org.torproject.android")
        if (launch == null) {
            AlertDialog.Builder(this).setTitle("Orbot is not installed")
                .setMessage("Install Orbot from the official Tor Project distribution, then return here. Nova does not bundle Tor.")
                .setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Route traffic through Orbot")
            .setMessage("Open Orbot and enable VPN mode. Make sure Nova Browser is selected in Orbot's app list. Android allows only one active VPN service per user/profile, so disconnect any other VPN before starting Orbot. For fail-closed protection, enable Android Settings → Network & internet → VPN → Orbot → Always-on VPN and Block connections without VPN. Nova cannot verify that the active VPN is Tor or guarantee routing if Orbot is stopped. Tor is an optional external route; Guest Mode itself does not create a Tor circuit.")
            .setPositiveButton("Open Orbot") { _, _ -> runCatching { startActivity(launch) }.onFailure { toast("Could not open Orbot") } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showAiAssistant() {
        val providers = AiAssistantRouter.Provider.values()
        AlertDialog.Builder(this).setTitle("Choose browser AI")
            .setItems(providers.map { it.label }.toTypedArray()) { _, selected ->
                val provider = providers[selected]
                val prompt = EditText(this).apply { hint = "Ask ${provider.label} anything…"; minLines = 2; setSingleLine(false) }
                val includePage = CheckBox(this).apply { text = "Include current page URL in the prompt"; isChecked = false }
                val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(prompt); addView(includePage) }
                AlertDialog.Builder(this).setTitle("Ask ${provider.label}")
                    .setMessage("Your prompt will be sent to ${provider.label} when you press Send. If you enable the checkbox, Nova also includes only the current page URL—not the page contents.")
                    .setView(content)
                    .setPositiveButton("Send") { _, _ ->
                        val question = prompt.text.toString().trim()
                        if (question.isBlank()) { toast("Enter a question first"); return@setPositiveButton }
                        val tab = currentTab()
                        val page = if (includePage.isChecked) tab?.lastUrl else null
                        val url = AiAssistantRouter.buildUrl(provider, question, page)
                        createTab(isPrivate = tab?.isPrivate == true, url = url, isGuest = tab?.isGuest == true)
                    }.setNegativeButton("Cancel", null).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showExtensions() {
        if (denyGuestAccess("Nova Add-ons")) return
        if (currentTab()?.engineType == BrowserEngineType.CEFRIUM) { showCefriumNovaAddons(); return }
        if (currentTab()?.engineType != BrowserEngineType.GECKOVIEW) {
            toast("Nova Add-ons are not available in this engine yet")
            return
        }
        val controller = runtime.getWebExtensionController()
        controller.list().accept({ extensions ->
            runOnUiThread {
                val installed = extensions.orEmpty().filterNot { it?.id == NovaBrowserVpnController.EXTENSION_ID }
                val rows = installed.map { "${it?.metaData?.name ?: it?.id ?: "Nova Add-on"} · ${if (it.metaData.enabled) "Enabled" else "Disabled"}" }.toTypedArray()
                AlertDialog.Builder(this).setTitle("Nova Add-ons · ${installed.size}")
                    .setMessage("Manage add-ons supported by the active Nova engine. Only compatible, signed packages can be installed. Availability and permissions depend on the engine.")
                    .setItems(rows) { _, which ->
                        val extension = installed[which]
                        val actions = if (extension.metaData.enabled) arrayOf("Disable", "Uninstall") else arrayOf("Enable", "Uninstall")
                        AlertDialog.Builder(this).setTitle(extension.metaData.name ?: extension.id)
                            .setItems(actions) { _, action ->
                                when (actions[action]) {
                                    "Disable" -> controller.disable(extension, WebExtensionController.EnableSource.USER).accept({ toast("Nova Add-on disabled") }, { toast("Could not disable add-on: ${it?.localizedMessage ?: "Unknown error"}") })
                                    "Enable" -> controller.enable(extension, WebExtensionController.EnableSource.USER).accept({ toast("Nova Add-on enabled") }, { toast("Could not enable add-on: ${it?.localizedMessage ?: "Unknown error"}") })
                                    "Uninstall" -> AlertDialog.Builder(this).setMessage("Remove this Nova Add-on and its data?")
                                        .setPositiveButton("Remove") { _, _ -> controller.uninstall(extension).accept({ toast("Nova Add-on removed") }, { toast("Could not remove add-on: ${it?.localizedMessage ?: "Unknown error"}") }) }
                                        .setNegativeButton("Cancel", null).show()
                                }
                            }.setNegativeButton("Close", null).show()
                    }
                    .setPositiveButton("Install Nova Add-on") { _, _ -> installExtensionPrompt() }
                    .setNegativeButton("Close", null).show()
            }
        }, { error -> runOnUiThread { toast("Could not list Nova Add-ons: ${error?.localizedMessage ?: "Unknown error"}") } })
    }


    private fun showCefriumNovaAddons() {
        val addons=novaScriptAddonStore.list()
        val rows=addons.map { "${it.name} · ${if(it.enabled) "Enabled" else "Disabled"}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Nova Add-ons · ${addons.size}")
            .setMessage("Nova Script Add-ons run only in Normal Cefrium tabs and only on approved website origins. Scripts run in the page's JavaScript world and can read or change that site's content. Install only code you trust. No add-on data is shared with GeckoView.")
            .setItems(rows) { _,which ->
                val addon=addons[which]
                val actions=if(addon.enabled)arrayOf("Disable","Remove")else arrayOf("Enable","Remove")
                AlertDialog.Builder(this).setTitle(addon.name)
                    .setMessage("Allowed websites:\n${addon.allowedOriginRules.sorted().joinToString("\n")}")
                    .setItems(actions) { _,action ->
                        when(actions[action]) {
                            "Enable" -> runCatching{novaScriptAddonStore.setEnabled(addon.id,true)}
                                .onSuccess{refreshCefriumNovaAddons();toast("Nova Add-on enabled · reload applied")}
                                .onFailure{toast("Could not enable add-on: ${it.message}")}
                            "Disable" -> runCatching{novaScriptAddonStore.setEnabled(addon.id,false)}
                                .onSuccess{refreshCefriumNovaAddons();toast("Nova Add-on disabled · reload applied")}
                                .onFailure{toast("Could not disable add-on: ${it.message}")}
                            "Remove" -> AlertDialog.Builder(this).setMessage("Remove ${addon.name} and its local script?")
                                .setPositiveButton("Remove"){_,_->runCatching{novaScriptAddonStore.remove(addon.id)}
                                    .onSuccess{refreshCefriumNovaAddons();toast("Nova Add-on removed")}
                                    .onFailure{toast("Could not remove add-on: ${it.message}")}}
                                .setNegativeButton("Cancel",null).show()
                        }
                    }.setNegativeButton("Cancel",null).show()
            }
            .setPositiveButton("Add script add-on"){_,_->showAddNovaScriptAddon()}
            .setNegativeButton("Close",null).show()
    }

    private fun showAddNovaScriptAddon() {
        val name=EditText(this).apply{hint="Add-on name";isSingleLine=true}
        val origins=EditText(this).apply{hint="https://example.com, https://*.example.org";minLines=2;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE}
        val script=EditText(this).apply{hint="// JavaScript to run at document start";minLines=8;gravity=Gravity.TOP;typeface=Typeface.MONOSPACE;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS}
        val content=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),dp(8))
            addView(TextView(this@MainActivity).apply{text="Name";textSize=12f});addView(name)
            addView(TextView(this@MainActivity).apply{text="Allowed website origins (comma-separated)";textSize=12f});addView(origins)
            addView(TextView(this@MainActivity).apply{text="JavaScript";textSize=12f});addView(script,LinearLayout.LayoutParams(-1,dp(190)))
        }
        AlertDialog.Builder(this).setTitle("Create Nova Script Add-on")
            .setMessage("Only HTTPS origins are accepted (HTTP is allowed for localhost). Scripts run in the page's main JavaScript world, including matching frames, and can access page content.")
            .setView(content).setPositiveButton("Install"){_,_->
                runCatching{novaScriptAddonStore.add(name.text.toString(),origins.text.toString(),script.text.toString())}
                    .onSuccess{refreshCefriumNovaAddons();toast("Nova Script Add-on installed")}
                    .onFailure{toast("Could not install add-on: ${it.message ?: "Check the name, origins and script size"}")}
            }.setNegativeButton("Cancel",null).show()
    }

    private fun refreshCefriumNovaAddons() {
        val active=novaScriptAddonStore.enabled()
        tabs.filter{it.engineType==BrowserEngineType.CEFRIUM}.forEach{tab->
            val engine=tab.browserEngine as? CefriumBrowserEngine?:return@forEach
            runCatching{engine.setNovaScriptAddons(active);if(tab.lastUrl!=HOME_URL)engine.reload()}
                .onFailure{toast("Could not refresh Nova Add-ons: ${it.message}")}
        }
    }

    private fun installExtensionPrompt() {
        if (currentTab()?.engineType != BrowserEngineType.GECKOVIEW) {
            toast("Nova Add-ons cannot be installed in this engine yet")
            return
        }
        val input = EditText(this).apply { hint = "https://…/package.xpi"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Install Nova Add-on")
            .setMessage("Enter a direct HTTPS .xpi package URL. Nova checks package compatibility and requests approval before granting permissions.")
            .setView(input).setPositiveButton("Install") { _, _ ->
                val url = input.text.toString().trim()
                if (!url.startsWith("https://") || !url.substringBefore('?').endsWith(".xpi", true)) { toast("Use a direct HTTPS add-on package URL"); return@setPositiveButton }
                runtime.getWebExtensionController().install(url, WebExtensionController.INSTALLATION_METHOD_MANAGER)
                    .accept({ toast("Nova Add-on installed: ${it?.metaData?.name ?: it?.id ?: "Add-on"}") }, { toast("Nova Add-on installation failed: ${it?.localizedMessage ?: "Unknown error"}") })
            }.setNegativeButton("Cancel", null).show()
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
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(getString(R.string.password), entry.password))
                toast(getString(R.string.password_copied))
            },
            getString(R.string.delete_login) to {
                AlertDialog.Builder(this).setTitle(getString(R.string.delete_login)).setMessage(getString(R.string.delete_login_confirm))
                    .setNegativeButton(getString(R.string.cancel), null)
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
                runCatching { passwordVault.save(builder.build()); switchCefriumTabsForSavedLogins(); onSaved() }
                    .onFailure { toast(getString(R.string.vault_save_failed)) }
            }.setNegativeButton(getString(R.string.cancel), null).show()
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
                    .onSuccess { switchCefriumTabsForSavedLogins(); toast("Login saved encrypted on this device") }
                    .onFailure { toast("Could not save login: ${it?.localizedMessage ?: "Unknown error"}") }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        toast("$label copied · clear your clipboard when finished")
    }

    private fun javaScriptPreferenceKey(engine: BrowserEngineType, url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val origin = "$scheme://$host${if (uri.port > 0) ":${uri.port}" else ""}"
        return "javascript_enabled_${engine.name.lowercase()}_${Uri.encode(origin)}"
    }

    private fun isJavaScriptAllowed(engine: BrowserEngineType, url: String): Boolean {
        val key = javaScriptPreferenceKey(engine, url) ?: return true
        return prefs.getBoolean(key, prefs.getBoolean("site_default_javascript_${engine.name.lowercase()}", true))
    }

    private fun applyJavaScriptSetting(tab: BrowserTab, url: String, reload: Boolean) {
        val enabled = isJavaScriptAllowed(tab.engineType, url)
        when (tab.engineType) {
            BrowserEngineType.GECKOVIEW -> tab.session?.settings?.setAllowJavascript(enabled)
            BrowserEngineType.CEFRIUM -> (tab.browserEngine as? CefriumBrowserEngine)?.setJavaScriptEnabled(enabled)
        }
        if (reload && tab.lastUrl.startsWith("http", true)) tab.browserEngine?.reload()
    }

    private fun toggleJavaScriptForCurrentSite(tab: BrowserTab) {
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
    private fun toggleSiteSetting(tab: BrowserTab, setting: String, default: Boolean) {
        val key = siteSettingKey(tab.engineType, tab.lastUrl, setting) ?: return toast("Open an HTTP or HTTPS site first")
        val enabled = !siteSettingAllowed(tab.engineType, tab.lastUrl, setting, default)
        prefs.edit().putBoolean(key, enabled).apply()
        val engine = tab.browserEngine as? CefriumBrowserEngine
        when (setting) {
            "autoplay" -> engine?.setAutoplayAllowed(enabled)
            "ads" -> engine?.setAdBlockingEnabled(enabled)
            "sound" -> engine?.setAudioMuted(!enabled)
        }
        tab.browserEngine?.reload()
        toast(setting + (if (enabled) " allowed for " else " blocked for ") + (Uri.parse(tab.lastUrl).host ?: "site"))
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

    private fun showDefaultSiteSettings(engine: BrowserEngineType) {
        val definitions = listOf(Triple("javascript", "JavaScript", true)) +
            if (engine == BrowserEngineType.CEFRIUM) listOf(
                Triple("popups", "Pop-ups and redirects", false),
                Triple("autoplay", "Autoplay", false),
                Triple("ads", "Ads and trackers", true),
                Triple("sound", "Sound", true)
            ) else emptyList()
        val permissionDefaults = if (engine == BrowserEngineType.CEFRIUM) arrayOf("Location, camera, microphone, notifications, clipboard and NFC permissions") else emptyArray()
        val labels = definitions.map { (key, name, fallback) ->
            val enabled = prefs.getBoolean("site_default_${engine.name.lowercase()}_$key", fallback)
            "$name · ${if (enabled) "Allowed" else "Blocked"}"
        }.toTypedArray() + permissionDefaults
        val engineLabel = if (engine == BrowserEngineType.GECKOVIEW) "GeckoView" else "Cefrium"
        AlertDialog.Builder(this).setTitle("Default site settings · $engineLabel")
            .setItems(labels) { _, which ->
                if (engine == BrowserEngineType.CEFRIUM && which == definitions.size) {
                    showDefaultCefriumPermissions()
                    return@setItems
                }
                val (key, name, fallback) = definitions[which]
                val prefKey = "site_default_${engine.name.lowercase()}_$key"
                val next = !prefs.getBoolean(prefKey, fallback)
                prefs.edit().putBoolean(prefKey, next).apply()
                tabs.filter { it.engineType == engine && !it.isPrivate && !it.isGuest }.forEach { active ->
                    when (key) {
                        "javascript" -> applyJavaScriptSetting(active, active.lastUrl, reload = false)
                        "autoplay" -> (active.browserEngine as? CefriumBrowserEngine)?.setAutoplayAllowed(next)
                        "ads" -> (active.browserEngine as? CefriumBrowserEngine)?.setAdBlockingEnabled(next)
                        "sound" -> (active.browserEngine as? CefriumBrowserEngine)?.setAudioMuted(!next)
                    }
                    if (active.lastUrl.startsWith("http", true)) active.browserEngine?.reload()
                }
                toast("$engineLabel default · $name ${if (next) "allowed" else "blocked"}")
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
            "${it.origin}  ·  ${if (it.engine == BrowserEngineType.GECKOVIEW) "GeckoView" else "Cefrium"}"
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
                    toast("No saved GeckoView permissions for ${Uri.parse(entry.origin).host}")
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
                            val next = if (permission.value == GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                                GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                            else GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                            runtime.storageController.setPermission(permission, next)
                            toast("Permission updated · reload the site")
                            showGeckoSitePermissions(entry)
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

    private fun showSiteRuleDetails(entry: SiteRuleEntry) {
        val host = Uri.parse(entry.origin).host ?: entry.origin
        val engineLabel = if (entry.engine == BrowserEngineType.GECKOVIEW) "GeckoView" else "Cefrium"
        val definitions = listOf(Triple("javascript", "JavaScript", true)) +
            if (entry.engine == BrowserEngineType.CEFRIUM) listOf(
                Triple("popups", "Pop-ups and redirects", false),
                Triple("autoplay", "Autoplay", false),
                Triple("ads", "Ads and trackers", true),
                Triple("sound", "Sound", true)
            ) else emptyList()
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
            .setItems(labels) { _, which ->
                when {
                    which < definitions.size -> {
                        val definition = definitions[which]
                        val key = siteRuleKey(entry, definition.first)
                        val next = !prefs.getBoolean(key, definition.third)
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
                                "autoplay" -> (active.browserEngine as? CefriumBrowserEngine)?.setAutoplayAllowed(next)
                                "ads" -> (active.browserEngine as? CefriumBrowserEngine)?.setAdBlockingEnabled(next)
                                "sound" -> (active.browserEngine as? CefriumBrowserEngine)?.setAudioMuted(!next)
                            }
                            active.browserEngine?.reload()
                        }
                        toast("$host · ${definition.second} ${if (next) "allowed" else "blocked"}")
                        showSiteRuleDetails(entry)
                    }
                    entry.engine == BrowserEngineType.GECKOVIEW && which == definitions.size -> {
                        showGeckoSitePermissions(entry)
                    }
                    entry.engine == BrowserEngineType.CEFRIUM && which == definitions.size -> {
                        showCefriumSitePermissionSettings(entry.origin)
                    }
                    which == resetIndex -> {
                        val encoded = Uri.encode(entry.origin)
                        val engineToken = entry.engine.name.lowercase() + "_"
                        val editor = prefs.edit()
                        prefs.all.keys.filter { key ->
                            (key.startsWith("javascript_enabled_$engineToken") && key.endsWith(encoded)) ||
                                (key.startsWith("site_") && key.contains("_$engineToken") && key.endsWith(encoded))
                        }.forEach(editor::remove)
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
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton("Clear data") { _, _ ->
                val encoded = Uri.encode(entry.origin)
                val engineToken = entry.engine.name.lowercase() + "_"
                val editor = prefs.edit()
                prefs.all.keys.filter { key ->
                    (key.startsWith("javascript_enabled_$engineToken") && key.endsWith(encoded)) ||
                        (key.startsWith("site_") && key.contains("_$engineToken") && key.endsWith(encoded))
                }.forEach(editor::remove)
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
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton("Clear all data") { _, _ ->
                runtime.storageController.clearData(StorageController.ClearFlags.SITE_DATA)
                    .accept({ runOnUiThread { toast("GeckoView site data cleared") } },
                        { error -> runOnUiThread { toast("Could not clear GeckoView data: ${error?.localizedMessage ?: "Unknown error"}") } })
            }.show()
    }

    private fun showSiteControls() {
        if (denyGuestAccess("Site controls")) return
        val tab = currentTab() ?: return
        val host = runCatching { Uri.parse(tab.lastUrl).host }.getOrNull()
        if (host.isNullOrBlank()) { toast("Open a website to manage its permissions"); return }
        val jsLabel = "JavaScript · " + if (isJavaScriptAllowed(tab.engineType, tab.lastUrl)) "Allowed" else "Blocked"
        val popupLabel = "Pop-ups and redirects · " + if (siteSettingAllowed(tab.engineType, tab.lastUrl, "popups", false)) "Allowed" else "Blocked"
        val autoplayLabel = "Autoplay · " + if (siteSettingAllowed(tab.engineType, tab.lastUrl, "autoplay", false)) "Allowed" else "Blocked"
        if (tab.engineType == BrowserEngineType.CEFRIUM) {
            val uri = Uri.parse(tab.lastUrl)
            val origin = "${uri.scheme ?: "https"}://${uri.host ?: host}${if (uri.port > 0) ":${uri.port}" else ""}"
            AlertDialog.Builder(this).setTitle("Site controls · $host")
                .setItems(arrayOf(jsLabel, popupLabel, autoplayLabel, "Ads and trackers · " + if (siteSettingAllowed(tab.engineType, tab.lastUrl, "ads", true)) "Allowed" else "Blocked", "Sound · " + if (siteSettingAllowed(tab.engineType, tab.lastUrl, "sound", true)) "Allowed" else "Blocked", "Clear this site's cookies and storage", "Manage location, camera, microphone, notifications, clipboard and NFC")) { _, which ->
                    if (which == 0) {
                        toggleJavaScriptForCurrentSite(tab)
                    } else if (which == 1) {
                        toggleSiteSetting(tab, "popups", false)
                    } else if (which == 2) {
                        toggleSiteSetting(tab, "autoplay", false)
                    } else if (which == 3) {
                        toggleSiteSetting(tab, "ads", true)
                    } else if (which == 4) {
                        toggleSiteSetting(tab, "sound", true)
                    } else if (which == 5) {
                        AlertDialog.Builder(this).setMessage("Clear cookies, cache, local storage and service workers for $host? Other sites are not affected.")
                            .setPositiveButton("Clear site data") { _, _ ->
                                runCatching {
                                    CefriumProfile.get(enginePreferences.cefriumProfileName()).clearSiteData(origin) {
                                        tabs.forEach { tab -> runCatching { tab.browserEngine?.clearCache() } }
                                        toast("Cefrium site data cleared for $host")
                                    }
                                }.onFailure { toast("Could not clear Cefrium site data: ${it.localizedMessage ?: "unknown error"}") }
                            }.setNegativeButton("Cancel", null).show()
                    } else if (which == 6) showCefriumSitePermissionSettings(origin)
                }.setNegativeButton("Close", null).show()
            return
        }
        val perms = tab.contentPermissions.filter { it.permission != GeckoSession.PermissionDelegate.PERMISSION_TRACKING }
        val rows = perms.map { "${sitePermissionLabel(it.permission)} · ${when (it.value) { GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW -> "Allowed"; GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY -> "Blocked"; else -> "Ask" }}" }.toTypedArray()
        val options = arrayOf(jsLabel) + rows + "Allow / block trackers for this site" + "Clear this site's cookies and storage"
        AlertDialog.Builder(this).setTitle("Site controls · $host")
            .setItems(options) { _, which ->
                when {
                    which == 0 -> toggleJavaScriptForCurrentSite(tab)
                    which <= perms.size -> {
                        val perm = perms[which]
                        val next = if (perm.value == GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW) GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY else GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                        runtime.storageController.setPermission(perm, next)
                        toast("Site permission updated · reload the page")
                    }
                    which == perms.size + 1 -> showShieldSettings()
                    else -> AlertDialog.Builder(this).setMessage("Clear cookies, caches, and site storage for $host? Other sites are not affected.")
                        .setPositiveButton("Clear site data") { _, _ ->
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
        AlertDialog.Builder(this).setTitle("Browser engine")
            .setMessage("Private and Guest tabs always use GeckoView. Cefrium is used only when Nova Shield, 1VPN and saved-password autofill do not require GeckoView. Website data is not automatically migrated between engines.")
            .setItems(actions) { _, which ->
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
        val labels = arrayOf("Automatic · Cefrium for compatible sites", "Cefrium · Chromium", "GeckoView")
        val selected = modes.indexOf(enginePreferences.normalMode()).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Normal-tab engine")
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
                toast("Normal-tab engine preference: ${mode.name.lowercase().replace('_', ' ')}")
                dialog.dismiss()
            }.setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun switchCurrentTabEngine() {
        val tab = currentTab() ?: return
        if (tab.isPrivate || tab.isGuest) { toast("Private and Guest tabs must remain on GeckoView"); return }
        val target = if (tab.engineType == BrowserEngineType.CEFRIUM) BrowserEngineType.GECKOVIEW else BrowserEngineType.CEFRIUM
        if (target == BrowserEngineType.CEFRIUM) {
            val previousFallback = tab.engineFallbackReason
            tab.engineFallbackReason = null // An explicit switch is also the user's retry after a prior runtime failure.
            val reason = cefriumCompatibilityReason(tab)
            if (reason != null) {
                tab.engineFallbackReason = previousFallback
                toast("Cefrium unavailable: $reason")
                return
            }
        }
        val targetLabel = if (target == BrowserEngineType.CEFRIUM) "Cefrium" else "GeckoView"
        AlertDialog.Builder(this).setTitle("Switch to $targetLabel?")
            .setMessage("The tab will reload. Cookies, local storage and active sessions are not migrated between engines; you may need to sign in again.")
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton("Switch") { _, _ ->
                closeTabRuntime(tab)
                if (target == BrowserEngineType.CEFRIUM && tab.engineType != BrowserEngineType.CEFRIUM) tab.cefriumNavigationState = null
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
        toast(if (saved) "${tab.engineType.name} remembered for $host" else "Could not save this site's engine preference")
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
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val hosts = input.text.toString().split(',').map { it.trim() }.filter { it.isNotBlank() }
                val count = enginePreferences.setRelatedSiteAffinities(hosts, tab.engineType)
                toast("Saved engine preference for $count exact host(s)")
            }.show()
    }

    private fun showSettings() {
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
        aboutActions.add(0, "Nova Add-ons" to { showExtensions() })
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

    private fun clearCefriumProfileData() {
        if (denyGuestAccess("Cefrium profile deletion")) return
        AlertDialog.Builder(this).setTitle("Clear Cefrium profile data?")
            .setMessage("This removes cookies, cache, local/session storage, IndexedDB and service workers from all Normal Cefrium tabs. Those tabs will reload in a fresh profile. You may need to sign in again. Private and Guest data are separate and are not affected.")
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton("Clear profile") { _, _ ->
                val oldProfile = enginePreferences.cefriumProfileName()
                val cefriumTabs = tabs.filter { it.engineType == BrowserEngineType.CEFRIUM }
                cefriumTabs.forEach { tab ->
                    runCatching { closeTabRuntime(tab) }
                    tab.needsReload = tab.lastUrl != HOME_URL
                    tab.cefriumNavigationState = null
                }
                val newProfile = enginePreferences.rotateCefriumProfile()
                val deletion = runCatching { CefriumProfile.delete(oldProfile) }
                currentTab()?.let { if (it in cefriumTabs) activateTab(it) }
                if (splitTabId != null && cefriumTabs.any { it.id == splitTabId }) restoreSplitPane()
                persistTabs()
                when {
                    deletion.isFailure -> toast("New clean Cefrium profile active ($newProfile); old profile deletion failed. Restart Nova and retry.")
                    deletion.getOrNull() == true -> toast("Cefrium profile cleared; new clean profile active")
                    else -> toast("New clean Cefrium profile active; old profile deletion will finish on next restart")
                }
            }.show()
    }

    private fun closePrivateAndGuestTabs() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.close_private_guest_title))
            .setMessage(getString(R.string.close_private_guest_message))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.close_tabs)) { _, _ ->
                val activeId = currentTab()?.id
                tabs.indices.filter { tabs[it].isPrivate || tabs[it].isGuest }.reversed().forEach { index ->
                    val removed = tabs.removeAt(index)
                    if (removed.id == splitTabId) exitSplitScreen()
                    closeTabRuntime(removed)
                    removed.thumbnail?.recycle(); removed.thumbnail = null
                }
                cleanupGuestContextIfIdle()
                if (tabs.isEmpty()) createTab(false, HOME_URL) else {
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
        }.setNegativeButton(getString(R.string.cancel), null).show()
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
                    cloudSync.signOut()
                    NovaAutoSync.cancel(this@MainActivity)
                    AutoSyncPassphraseStore.clear(this@MainActivity)
                    prefs.edit().putBoolean("cloud_sync_enabled", false).remove("cloud_sync_user_id").apply()
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
                        .onSuccess { runOnUiThread { showCloudOtp(address) } }
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
                toast("Sending reset link\u2026")
                Thread {
                    runCatching { cloudSync.requestPasswordReset(address) }
                        .onSuccess {
                            runOnUiThread {
                                AlertDialog.Builder(this)
                                    .setTitle("Check your email")
                                    .setMessage("If an account exists for $address, a password-reset link has been sent. Open it to set a new password, then return here to sign in.")
                                    .setPositiveButton("OK") { _, _ -> showCloudPasswordSignIn() }
                                    .show()
                            }
                        }
                        .onFailure { runOnUiThread { toast("Could not send reset link: ${it.message ?: "Network error"}") } }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCloudOtp(email: String) {
        val input = EditText(this).apply { hint = "6-digit email code"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; isSingleLine = true }
        AlertDialog.Builder(this).setTitle("Verify email").setMessage("Enter the six-digit code sent to $email")
            .setView(input).setPositiveButton("Verify") { _, _ ->
                val code = input.text.toString().trim()
                toast("Verifying code…")
                Thread {
                    runCatching { cloudSync.verifyEmailOtp(email, code) }
                        .onSuccess { runOnUiThread { toast("Signed in to Nova Cloud"); showCloudAccount() } }
                        .onFailure { runOnUiThread { toast("Sign-in failed: ${it.message ?: "Try again"}") } }
                }.start()
            }.setNeutralButton("Resend code") { _, _ ->
                Thread { runCatching { cloudSync.sendEmailOtp(email) }
                    .onSuccess { runOnUiThread { showCloudOtp(email); toast("A new code was requested") } }
                    .onFailure { runOnUiThread { toast("Could not resend: ${it.message}") } }
                }.start()
            }.setNegativeButton("Cancel", null).show()
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

    private fun performCloudSync(passphrase: CharArray) {
        try {
            val userId = cloudSync.signedInUserId() ?: throw IllegalStateException("Sign in to Nova Cloud first")
            val boundUserId = prefs.getString("cloud_sync_user_id", null)
            if (!boundUserId.isNullOrBlank() && boundUserId != userId) {
                throw IllegalStateException("This device's local browser profile is linked to a different Nova Cloud account. Use that account or clear this app's local data before switching accounts.")
            }
            val local = buildSyncSnapshot()
            val localVault = passwordVault.exportSyncBundle()
            val remote = cloudSync.downloadEncrypted()
            val remoteSnapshot = remote.first
            val remoteVaultPayload = remote.second
            val merged = if (remoteSnapshot == null) local else mergeSyncSnapshots(local,
                JSONObject(CloudCrypto.decrypt(remoteSnapshot, passphrase)))
            val remoteVault = remoteVaultPayload?.let { PasswordVault.decodeSyncBundle(CloudCrypto.decrypt(it, passphrase)) } ?: JSONObject()
            val mergedVault = PasswordVault.mergeSyncBundles(localVault, remoteVault)
            // Apply to the local vault first, off the UI thread and inside this try/catch, so a bad bundle
            // can neither crash the app nor be uploaded.
            passwordVault.importSyncBundle(mergedVault)
            cloudSync.uploadEncrypted(CloudCrypto.encrypt(merged.toString(), passphrase),
                CloudCrypto.encrypt(mergedVault.toString(), passphrase))
            if (prefs.getBoolean("cloud_sync_enabled", false)) {
                AutoSyncPassphraseStore.save(this, passphrase)
                NovaAutoSync.schedule(this)
            }
            runOnUiThread {
                applySyncSnapshot(merged)
                prefs.edit().putBoolean("cloud_sync_enabled", true).putString("cloud_sync_user_id", userId).apply()
                toast("Encrypted cloud sync completed")
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
        val wide = newConfig.screenWidthDp >= 600
        browserPane.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        (browserView.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.width = if (wide) 0 else ViewGroup.LayoutParams.MATCH_PARENT
            params.height = if (wide) ViewGroup.LayoutParams.MATCH_PARENT else 0
            params.weight = 1f
            browserView.layoutParams = params
        }
        (secondaryPane.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.width = if (wide) 0 else ViewGroup.LayoutParams.MATCH_PARENT
            params.height = if (wide) ViewGroup.LayoutParams.MATCH_PARENT else 0
            params.weight = 1f
            secondaryPane.layoutParams = params
        }
    }

    override fun onPause() {
        cancelPendingRemoteSuggestions()
        tabs.forEach { it.browserEngine?.onHostPause() }
        persistTabs()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (NovaBrowserVpnController.isVpnEnabled()) switchAllCefriumTabsToGecko("1VPN is enabled")
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
                    val imported = JSONArray(json)
                    val merged = readBookmarks().associateBy { it.url }.toMutableMap()
                    for (i in 0 until imported.length()) {
                        val item = imported.getJSONObject(i)
                        val url = item.optString("url")
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            merged[url] = BookmarkEntry(url, item.optString("title", Uri.parse(url).host ?: url), item.optString("folder", "Imported"), item.optLong("addedAt", System.currentTimeMillis()))
                        }
                    }
                    saveBookmarks(merged.values.toList())
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
        cancelPendingRemoteSuggestions()
        remoteSuggestionExecutor.shutdownNow()
        pendingAndroidPermissionCallback?.reject()
        pendingAndroidPermissionCallback = null
        pendingCefriumPermissionDialog?.dismiss()
        pendingCefriumPermissionDialog = null
        pendingCefriumPermissionDecision?.invoke(false)
        pendingCefriumPermissionDecision = null
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
            if (::runtime.isInitialized && guestContextId.isNotBlank()) runtime.storageController.clearDataForSessionContext(guestContextId)
        }
        tabs.clear()
        // Keep the process-wide GeckoRuntime alive; shutting it down can terminate in-flight downloads.
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_SITE_PERMISSIONS = 4021
        private const val HOME_URL = "about:blank"
        @Volatile private var sharedRuntime: GeckoRuntime? = null
        private var retainedTabsForConfiguration: List<BrowserTab>? = null
        private var retainedActiveIndex: Int = 0
        private var retainedSplitTabId: String? = null
        private var retainedGuestContextId: String? = null
        private const val ADGUARD_DOH_ENDPOINT = "https://dns.adguard-dns.com/dns-query"
    }
}
