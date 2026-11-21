package com.nova.browser

import androidx.activity.ComponentActivity
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
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.EditText
import android.widget.LinearLayout
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

/** Nova browser core. GeckoView is the only rendering engine. */
class MainActivity : ComponentActivity() {
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var filePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var bookmarkTransferLauncher: ActivityResultLauncher<Intent>
    private lateinit var historyExportLauncher: ActivityResultLauncher<Intent>
    private var notificationPermissionRequestPending = false
    private lateinit var runtime: GeckoRuntime
    private lateinit var browserView: GeckoView
    private lateinit var secondaryBrowserView: GeckoView
    private lateinit var secondaryPane: LinearLayout
    private lateinit var secondaryAddressBar: EditText
    private lateinit var browserPane: LinearLayout
    private var splitTabId: String? = null
    private val collapsedGroups = mutableSetOf<String>()
    private var pendingFilePrompt: FilePrompt? = null
    private var pendingFileResult: GeckoResult<PromptResponse>? = null
    private var bookmarkTransferMode: String? = null
    private var guestContextId: String = ""
    private val novaDownloads: NovaDownloadManager
        get() = (application as NovaBrowserApplication).downloads
    private var downloadsDialog: AlertDialog? = null
    private var downloadsAdapter: ArrayAdapter<String>? = null
    private var downloadsDialogItems: List<NovaDownloadManager.Item> = emptyList()
    private val passwordVault: PasswordVault by lazy { PasswordVault(this) }
    private val cloudSync: NovaCloudSync by lazy { NovaCloudSync(this) }
    private var pendingHistoryExport: String? = null
    private val extensionActions = mutableMapOf<String, WebExtension.Action>()
    private var activeExtensionPopup: Dialog? = null
    private val detectedPageLanguages = mutableMapOf<String, String>()
    private val desktopSites get() = prefs.getStringSet("desktop_sites", emptySet())?.toMutableSet() ?: mutableSetOf()
    private lateinit var addressBar: EditText
    private lateinit var tabLabel: TextView
    private lateinit var pageStatus: TextView
    private lateinit var root: LinearLayout
    private lateinit var homePanel: LinearLayout
    private lateinit var homeShortcuts: LinearLayout
    private lateinit var searchEngineButton: Button
    private val tabs = mutableListOf<BrowserTab>()
    private var activeIndex = 0
    private var searchEngine = "Google"
    private val prefs by lazy { getSharedPreferences("nova_local", MODE_PRIVATE) }

    private data class BookmarkEntry(val url: String, val title: String, val folder: String, val addedAt: Long)
    private data class HistoryEntry(val url: String, val title: String, val visitedAt: Long, val id: String = UUID.randomUUID().toString())

    private data class BrowserTab(
        var session: GeckoSession?,
        val isPrivate: Boolean,
        val isGuest: Boolean = false,
        val id: String = UUID.randomUUID().toString(),
        var lastUrl: String = HOME_URL,
        var group: String = "Ungrouped",
        var title: String = "New tab",
        var canGoBack: Boolean = false,
        var canGoForward: Boolean = false,
        var lastAccess: Long = 0L,
        var needsReload: Boolean = false,
        var desktopSite: Boolean = false,
        var readerOriginalUrl: String? = null,
        var trackingPermission: GeckoSession.PermissionDelegate.ContentPermission? = null,
        var contentPermissions: List<GeckoSession.PermissionDelegate.ContentPermission> = emptyList()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookmarkTransferMode = savedInstanceState?.getString("bookmark_transfer_mode")
        pendingHistoryExport = savedInstanceState?.getString("pending_history_export")
        notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationPermissionRequestPending = false
            if (!granted) toast("Downloads still work, but Android may hide their notifications until permission is enabled")
        }
        filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleFilePickerResult(result.resultCode, result.data)
        }
        bookmarkTransferLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleBookmarkTransferResult(result.resultCode, result.data)
        }
        historyExportLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleHistoryExportResult(result.resultCode, result.data)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val tab = currentTab()
                if (tab?.lastUrl == HOME_URL && tabs.size > 1) closeTabAt(activeIndex)
                else if (tab?.lastUrl == HOME_URL) finish()
                else if (tab?.canGoBack == true) tab.session?.goBack()
                else finish()
            }
        })
        searchEngine = prefs.getString("search_engine", "Google") ?: "Google"
        runtime = GeckoRuntime.create(this)
        configureAddonManagerDelegate()
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
                runCatching { passwordVault.save(login) }.onFailure { runOnUiThread { toast("Could not save login securely: ${it?.localizedMessage ?: "Unknown error"}") } }
            }
        })
        runtime.settings.setLoginAutofillEnabled(true)
        configureExtensionPermissions()
        NovaBrowserVpnController.initialize(this, runtime)
        val stalePrivateContexts = prefs.getStringSet(PRIVATE_CONTEXT_IDS_KEY, emptySet()).orEmpty().toSet()
        stalePrivateContexts.forEach(runtime.storageController::clearDataForSessionContext)
        prefs.edit().remove(PRIVATE_CONTEXT_IDS_KEY).apply()
        val previousGuestContext = prefs.getString("guest_context_id", null)
        if (!previousGuestContext.isNullOrBlank()) runtime.storageController.clearDataForSessionContext(previousGuestContext)
        guestContextId = "nova-guest-${UUID.randomUUID()}"
        prefs.edit().putString("guest_context_id", guestContextId).apply()
        buildBrowserUi()
        restoreTabs()
        if (tabs.isEmpty()) createTab(false, HOME_URL)
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        val action = incoming?.action ?: return
        val downloadId = incoming.getStringExtra(EXTRA_DOWNLOAD_ID)
        if (action == ACTION_PAUSE_DOWNLOAD && !downloadId.isNullOrBlank()) {
            novaDownloads.pause(downloadId)
            toast("Download paused")
            return
        }
        if (action == ACTION_CANCEL_DOWNLOAD && !downloadId.isNullOrBlank()) {
            novaDownloads.cancel(downloadId)
            toast("Download cancelled")
            return
        }
        val target = when (action) {
            Intent.ACTION_VIEW -> incoming.dataString
            Intent.ACTION_WEB_SEARCH -> incoming.getStringExtra(android.app.SearchManager.QUERY)
            Intent.ACTION_SEND -> if (incoming.type == "text/plain") {
                incoming.getStringExtra(Intent.EXTRA_TEXT)
            } else null
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (action == Intent.ACTION_VIEW &&
            !target.startsWith("http://", true) && !target.startsWith("https://", true)
        ) return
        navigate(target)
    }

    private fun buildBrowserUi() {
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(4))
            setBackgroundColor(0xFF171020.toInt())
        }
        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addressBar = EditText(this).apply {
            hint = "Search or enter address"
            isSingleLine = true
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFFB9AEC8.toInt())
            setSelectAllOnFocus(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
            setBackgroundColor(0xFF30243D.toInt())
            setPadding(dp(12), 0, dp(12), 0)
            contentDescription = "Nova address bar"
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
        val homeButton = actionButton("√¢¬å¬Ç") { goHome() }.apply {
            contentDescription = "Home"
            textSize = 18f
            minWidth = 40
            minimumWidth = 40
        }
        homeButton.minimumWidth = dp(48)
        homeButton.minimumHeight = dp(48)
        addressRow.addView(homeButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        addressRow.addView(addressBar, LinearLayout.LayoutParams(0, dp(48), 1f))
        searchEngineButton = actionButton("$searchEngine √¢¬ñ¬æ") { chooseSearchEngine() }
        searchEngineButton.contentDescription = "Choose search engine. Current: $searchEngine"
        searchEngineButton.minWidth = dp(64)
        searchEngineButton.minimumWidth = dp(64)
        addressRow.addView(searchEngineButton, LinearLayout.LayoutParams(dp(72), dp(48)))
        val goButton = actionButton("Go") { submitAddressBar() }
        goButton.minWidth = dp(48)
        goButton.minimumWidth = dp(48)
        addressRow.addView(goButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        top.addView(addressRow)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controls.addView(actionButton("√¢¬Ü¬ê", "Back") { goBack() })
        controls.addView(actionButton("√¢¬Ü¬í", "Forward") { goForward() })
        controls.addView(actionButton("√¢¬Ü¬ª", "Reload") { reloadPage() })
        controls.addView(actionButton("√Ø¬º¬ã") {
            if (currentTab()?.isGuest == true) createGuestTab() else createTab(false, HOME_URL)
        })
        controls.addView(actionButton("Tabs") { showTabs() })
        controls.addView(actionButton("Split") { chooseSplitTab() })
        controls.addView(actionButton("Private") {
            if (currentTab()?.isGuest == true) {
                pageStatus.text = "Guest Mode √Ç¬∑ temporary data"
                toast("Private tabs are unavailable in Guest Mode")
            } else createTab(true, HOME_URL)
        })
        controls.addView(actionButton("Guest") { createGuestTab() })
        top.addView(toolbarScroll(controls), LinearLayout.LayoutParams(-1, dp(48)))

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tools.addView(actionButton("√¢¬ò¬Ü") { toggleBookmark() })
        tools.addView(actionButton("Bookmarks") { showBookmarks() })
        tools.addView(actionButton("History") { showHistory() })
        tools.addView(actionButton("Downloads") { showDownloads() })
        tools.addView(actionButton("Desktop") { toggleDesktopSite() })
        tools.addView(actionButton("Reader") { openReaderMode() })
        tools.addView(actionButton("Translate") { translatePage() })
        tools.addView(actionButton("Share") { sharePage() })
        tools.addView(actionButton("Settings") { showSettings() })
        top.addView(toolbarScroll(tools), LinearLayout.LayoutParams(-1, dp(48)))
        val advancedTools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        advancedTools.addView(actionButton("Shield") { showShieldSettings() })
        advancedTools.addView(actionButton("AI") { showAiAssistant() })
        advancedTools.addView(actionButton("Add-ons") { showExtensions() })
        advancedTools.addView(actionButton("Passwords") { showPasswordVault() })
        advancedTools.addView(actionButton("Site") { showSiteControls() })
        top.addView(toolbarScroll(advancedTools), LinearLayout.LayoutParams(-1, dp(48)))

        tabLabel = TextView(this).apply {
            text = "Nova Browser"
            textSize = 12f
            setTextColor(0xFFD8CBE8.toInt())
            setPadding(dp(4), dp(5), dp(4), dp(5))
        }
        pageStatus = TextView(this).apply {
            text = "Ready"
            contentDescription = "Nova session status"
            textSize = 11f
            setTextColor(0xFFB9AEC8.toInt())
            setPadding(dp(4), 0, dp(4), dp(4))
        }
        top.addView(tabLabel)
        top.addView(pageStatus)

        browserView = GeckoView(this)
        secondaryBrowserView = GeckoView(this)
        secondaryAddressBar = EditText(this).apply {
            hint = "Second pane address"
            isSingleLine = true
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFFB9AEC8.toInt())
            setBackgroundColor(0xFF30243D.toInt())
            setPadding(dp(12), 0, dp(12), 0)
        }
        val secondaryControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(actionButton("Back", "Secondary pane back") { tabs.firstOrNull { it.id == splitTabId }?.let { if (it.canGoBack) it.session?.goBack() } })
            addView(actionButton("Forward", "Secondary pane forward") { tabs.firstOrNull { it.id == splitTabId }?.let { if (it.canGoForward) it.session?.goForward() } })
            addView(actionButton("Reload", "Reload secondary pane") { tabs.firstOrNull { it.id == splitTabId }?.session?.reload() })
            addView(secondaryAddressBar, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(actionButton("Go") { navigateSecondary(secondaryAddressBar.text.toString()) })
            addView(actionButton("Close split") { exitSplitScreen() })
        }
        secondaryPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(secondaryControls, LinearLayout.LayoutParams(-1, -2))
            addView(secondaryBrowserView, LinearLayout.LayoutParams(-1, 0, 1f))
            visibility = View.GONE
        }
        browserPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(browserView, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(secondaryPane, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        homePanel = createHomePanel()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF100D15.toInt())
            addView(top, LinearLayout.LayoutParams(-1, -2))
            addView(homePanel, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(browserPane, LinearLayout.LayoutParams(-1, 0, 1f))
            browserPane.visibility = View.GONE
        }
        root.isFocusableInTouchMode = true
        setContentView(root)
        root.requestFocus()
        window.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
    }

    private fun createHomePanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF171020.toInt(), 0xFF241532.toInt(), 0xFF100D15.toInt())
            )
        }
        val dragon = TextView(this).apply {
            text = "√∞¬ü¬ê¬â"
            textSize = 58f
            gravity = Gravity.CENTER
            contentDescription = "Nova dragon emblem"
            setPadding(0, 0, 0, dp(8))
        }
        val title = TextView(this).apply {
            text = "NOVA"
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.18f
            setTextColor(0xFFF1E9FA.toInt())
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Your browser. Your space."
            textSize = 13f
            setTextColor(0xFFB9AEC8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(24))
        }
        val shortcutTitle = TextView(this).apply {
            text = "QUICK ACCESS"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            setTextColor(0xFFD8CBE8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        homeShortcuts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        homeShortcuts.addView(actionButton("Google") { navigate("https://www.google.com") })
        homeShortcuts.addView(actionButton("YouTube") { navigate("https://m.youtube.com") })
        homeShortcuts.addView(actionButton("Wikipedia") { navigate("https://www.wikipedia.org") })
        homeShortcuts.addView(actionButton("GitHub") { navigate("https://github.com") })
        panel.addView(dragon)
        panel.addView(title)
        panel.addView(subtitle)
        panel.addView(shortcutTitle)
        panel.addView(homeShortcuts)
        return panel
    }

    private fun showHomeScreen() {
        homePanel.visibility = View.VISIBLE
        if (splitTabId != null) exitSplitScreen()
        browserPane.visibility = View.GONE
        addressBar.setText("")
        dismissAddressBarInput()
        tabLabel.text = "Nova Browser √Ç¬∑ Home"
        val activeTab = currentTab()
        pageStatus.text = when {
            activeTab?.isGuest == true -> "Guest Mode √Ç¬∑ temporary data"
            activeTab?.isPrivate == true -> "Private session"
            else -> "Ready √Ç¬∑ $searchEngine"
        }
        searchEngineButton.text = "$searchEngine √¢¬ñ¬æ"
        searchEngineButton.contentDescription = "Choose search engine. Current: $searchEngine"
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
            tab.lastUrl = HOME_URL
            tab.title = "Nova Home"
            tab.canGoBack = false
            tab.canGoForward = false
            tab.needsReload = false
            // Stop page audio and clear the page while the home panel is visible.
            tab.session?.loadUri(HOME_URL)
            if (!tab.isPrivate && !tab.isGuest) persistTabs()
        }
        addressBar.setText("")
        showHomeScreen()
    }

    private fun showBrowserPage() {
        homePanel.visibility = View.GONE
        browserPane.visibility = View.VISIBLE
    }

    private fun themeTextColor(attribute: Int): Int {
        val value = TypedValue()
        if (!theme.resolveAttribute(attribute, value, true)) return Color.WHITE
        return if (value.resourceId != 0) getColor(value.resourceId) else value.data
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun toolbarScroll(row: LinearLayout) =
        android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            addView(row, android.view.ViewGroup.LayoutParams(-2, dp(48)))
        }

    private fun actionButton(label: String, description: String = label, action: () -> Unit) = Button(this).apply {
        text = label
        contentDescription = when (label) {
            "√¢¬Ü¬ê" -> "Back"
            "√¢¬Ü¬í" -> "Forward"
            "√¢¬Ü¬ª" -> "Reload"
            "√Ø¬º¬ã" -> "New tab"
            else -> description
        }
        textSize = 11f
        isAllCaps = false
        minWidth = dp(48)
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(7), 0, dp(7), 0)
        setOnClickListener { action() }
    }

    private fun createTab(isPrivate: Boolean, url: String, isGuest: Boolean = false) {
        if (currentTab()?.isGuest == true && !isGuest) {
            toast("Normal and Private tabs are unavailable in Guest Mode")
            return
        }
        currentTab()?.session?.setActive(false)
        currentTab()?.session?.setFocused(false)
        val tab = BrowserTab(null, isPrivate, isGuest, lastUrl = url, desktopSite = isDesktopUrl(url))
        tabs.add(tab)
        activeIndex = tabs.lastIndex
        val session = ensureSession(tab)
        browserView.setSession(session)
        session.setActive(true)
        session.setFocused(true)
        tab.lastAccess = System.nanoTime()
        addressBar.setText(url)
        tabLabel.text = when { isGuest -> "Guest tab √Ç¬∑ temporary"; isPrivate -> "Private tab √Ç¬∑ ${tabs.size} total"; else -> "Tab ${tabs.size}" }
        if (!isPrivate && !isGuest) persistTabs()
        if (url == HOME_URL) showHomeScreen() else navigate(url)
        enforceSessionBudget()
    }

    private fun createGuestTab() {
        createTab(isPrivate = false, url = HOME_URL, isGuest = true)
        toast("Guest Mode started √Ç¬∑ temporary browsing data")
    }

    private fun isDesktopUrl(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return false
        return host in desktopSites
    }

    private fun tabKey(session: GeckoSession): String = tabs.firstOrNull { it.session === session }?.id ?: ""

    private fun ensureSession(tab: BrowserTab): GeckoSession {
        tab.session?.let { return it }
        val contextId = SessionContextPolicy.contextId(tab.id, tab.isPrivate, tab.isGuest, guestContextId)
        if (tab.isPrivate && contextId != null) {
            val tracked = prefs.getStringSet(PRIVATE_CONTEXT_IDS_KEY, emptySet()).orEmpty().toMutableSet()
            if (tracked.add(contextId)) prefs.edit().putStringSet(PRIVATE_CONTEXT_IDS_KEY, tracked).apply()
        }
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(tab.isPrivate || tab.isGuest)
            .contextId(contextId)
            .userAgentMode(if (tab.desktopSite) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(if (tab.desktopSite) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .build()
        val session = GeckoSession(settings)
        session.promptDelegate = object : GeckoSession.PromptDelegate {
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
                try { filePickerLauncher.launch(picker) }
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
                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Save password?")
                        .setMessage("Save the login for ${Uri.parse(login.origin).host ?: login.origin} in Nova's encrypted on-device vault? The password stays on this device.")
                        .setPositiveButton("Save securely") { _, _ -> result.complete(request.confirm(option)) }
                        .setNegativeButton("Not now") { _, _ -> result.complete(request.dismiss()) }
                        .setOnCancelListener { result.complete(request.dismiss()) }
                        .show()
                }
                return result
            }

            override fun onLoginSelect(session: GeckoSession, request: GeckoSession.¢Îi∫€k∫wµÁ_∫Yh∫⁄n∂∆Øy€h˛iÌ˝¯•zœ‹¢jh≤*?¢Îi∫ﬂﬁ≈Á%â˚lµ⁄.∂‹mNã‚{ay˚•ñã≠¶Îk∫w(uÍ)zÊûﬂﬂäW¨˝ &¶ã"£˙.∂õ≠˝Ï^rXü∂À]¢Îm∆⁄ﬁi”´∫«Ç8√ŒHÉÅ!”8‚‹jﬂÊûﬂﬂäW¨˝ &¶ã"£˙.∂õ≠˝Ï^rXü∂À]¢Îm∆ﬂ·yßg◊M?ö{~)^≥˜(öö,äèË∫⁄n∑˜±y…b~€-vã≠∑µ®•ü]4˛iÌ˝¯•zœ‹¢jh≤*?¢Îi∫ﬂﬁ≈Á%â˚lµ⁄.∂‹m