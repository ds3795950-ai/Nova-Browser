package com.nova.browser

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.app.AlertDialog
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
import android.graphics.Typeface
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
    private data class HistoryEntry(val url: String, val title: String, val visitedAt: Long)

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
        notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationPermissionRequestPending = false
            if (!granted) toast("Downloads still work, but Android may hide their notifications until permission is enabled")
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val tab = currentTab()
                if (tab?.lastUrl == HOME_URL) finish()
                else if (tab?.canGoBack == true) tab.session?.goBack()
                else finish()
            }
        })
        searchEngine = prefs.getString("search_engine", "Google") ?: "Google"
        runtime = GeckoRuntime.create(this)
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
        val previousGuestContext = prefs.getString("guest_context_id", null)
        if (!previousGuestContext.isNullOrBlank()) runtime.storageController.clearDataForSessionContext(previousGuestContext)
        guestContextId = "nova-guest-${UUID.randomUUID()}"
        prefs.edit().putString("guest_context_id", guestContextId).apply()
        buildBrowserUi()
        restoreTabs()
        if (tabs.isEmpty()) createTab(false, HOME_URL)
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
        val homeButton = actionButton("⌂") { goHome() }.apply {
            contentDescription = "Home"
            textSize = 18f
            minWidth = 40
            minimumWidth = 40
        }
        homeButton.minimumWidth = dp(48)
        homeButton.minimumHeight = dp(48)
        addressRow.addView(homeButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        addressRow.addView(addressBar, LinearLayout.LayoutParams(0, dp(48), 1f))
        searchEngineButton = actionButton("$searchEngine ▾") { chooseSearchEngine() }
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
        controls.addView(actionButton("←") { goBack() })
        controls.addView(actionButton("→") { goForward() })
        controls.addView(actionButton("↻") { reloadPage() })
        controls.addView(actionButton("＋") {
            if (currentTab()?.isGuest == true) createGuestTab() else createTab(false, HOME_URL)
        })
        controls.addView(actionButton("Tabs") { showTabs() })
        controls.addView(actionButton("Split") { chooseSplitTab() })
        controls.addView(actionButton("Private") {
            if (currentTab()?.isGuest == true) toast("Private tabs are unavailable in Guest Mode") else createTab(true, HOME_URL)
        })
        controls.addView(actionButton("Guest") { createGuestTab() })
        top.addView(toolbarScroll(controls), LinearLayout.LayoutParams(-1, dp(48)))

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tools.addView(actionButton("☆") { toggleBookmark() })
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
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
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
            text = "🐉"
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
        tabLabel.text = "Nova Browser · Home"
        val activeTab = currentTab()
        pageStatus.text = when {
            activeTab?.isGuest == true -> "Guest Mode · temporary data"
            activeTab?.isPrivate == true -> "Private session"
            else -> "Ready · $searchEngine"
        }
        searchEngineButton.text = "$searchEngine ▾"
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
            tab.needsReload = false
            if (!tab.isPrivate && !tab.isGuest) persistTabs()
        }
        showHomeScreen()
    }

    private fun showBrowserPage() {
        homePanel.visibility = View.GONE
        browserPane.visibility = View.VISIBLE
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun toolbarScroll(row: LinearLayout) =
        android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            addView(row, android.view.ViewGroup.LayoutParams(-2, dp(48)))
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
        tabLabel.text = when { isGuest -> "Guest tab · temporary"; isPrivate -> "Private tab · ${tabs.size} total"; else -> "Tab ${tabs.size}" }
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
        tab.session?.let { return it }
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(tab.isPrivate || tab.isGuest)
            .contextId(if (tab.isGuest) guestContextId else null)
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

            override fun onLoginSelect(session: GeckoSession, request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.LoginSelectOption>): GeckoResult<PromptResponse> {
                val tab = tabs.firstOrNull { it.session === session }
                val result = GeckoResult<PromptResponse>()
                if (tab == null || tab.isPrivate || tab.isGuest || request.options.isEmpty()) {
                    result.complete(request.dismiss())
                    return result
                }
                val choices = request.options.map { it.value.username.ifBlank { Uri.parse(it.value.origin).host ?: "Saved login" } }.toTypedArray()
                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity).setTitle("Choose saved login")
                        .setItems(choices) { _, which -> result.complete(request.confirm(request.options[which])) }
                        .setNegativeButton("Cancel") { _, _ -> result.complete(request.dismiss()) }
                        .setOnCancelListener { result.complete(request.dismiss()) }.show()
                }
                return result
            }

            override fun onFolderUploadPrompt(session: GeckoSession, prompt: FolderUploadPrompt): GeckoResult<PromptResponse> {
                val result = GeckoResult<PromptResponse>()
                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Upload folder?")
                        .setMessage("The website is requesting access to upload the folder ${prompt.directoryName ?: "selected folder"}. Its files may be sent to this site.")
                        .setPositiveButton("Allow") { _, _ -> result.complete(prompt.confirm(AllowOrDeny.ALLOW)) }
                        .setNegativeButton("Deny") { _, _ -> result.complete(prompt.confirm(AllowOrDeny.DENY)) }
                        .setOnCancelListener { result.complete(prompt.confirm(AllowOrDeny.DENY)) }
                        .show()
                }
                return result
            }
        }
        session.setTranslationsSessionDelegate(object : TranslationsController.SessionTranslation.Delegate {
            override fun onOfferTranslate(session: GeckoSession) {
                val tab = tabs.firstOrNull { it.session === session } ?: return
                if (currentTab() === tab) runOnUiThread { toast("This page may be available in another language · tap Translate") }
            }
            override fun onExpectedTranslate(session: GeckoSession) {
                val tab = tabs.firstOrNull { it.session === session } ?: return
                if (currentTab() === tab) runOnUiThread { pageStatus.text = "Translation available" }
            }
            override fun onTranslationStateChange(session: GeckoSession, translationState: TranslationsController.SessionTranslation.TranslationState?) {
                val language = translationState?.detectedLanguages?.docLangTag
                if (!language.isNullOrBlank()) detectedPageLanguages[tabKey(session)] = language
            }
        })
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                val tab = tabs.firstOrNull { it.session === session }
                val body = response.body
                if (tab == null || tab.isPrivate || tab.isGuest) {
                    runCatching { body?.close() }
                    toast("Downloads are disabled in private and Guest Mode")
                    return
                }
                if (body == null) { toast("This download has no readable response body"); return }
                val rawName = response.uri.substringAfterLast('/').substringBefore('?').ifBlank { "nova-download-${System.currentTimeMillis()}" }
                val mime = response.headers["Content-Type"] ?: response.headers["content-type"] ?: "application/octet-stream"
                requestDownloadNotificationPermission()
                novaDownloads.saveExternalStream(body, rawName, mime.substringBefore(';'))
                toast("Webpage download received · saving to Downloads/Nova")
            }
            override fun onTitleChange(session: GeckoSession, title: String?) {
                if (!title.isNullOrBlank()) {
                    val tab = tabs.firstOrNull { it.session === session } ?: return
                    tab.title = title
                    if (currentTab() === tab) tabLabel.text = title.take(32)
                }
            }
        }
        session.open(runtime)
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { tab.canGoBack = canGoBack }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) { tab.canGoForward = canGoForward }
            override fun onLocationChange(session: GeckoSession, url: String?, perms: List<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                if (url.isNullOrBlank()) return
                tab.lastUrl = url
                tab.contentPermissions = perms
                tab.trackingPermission = perms.firstOrNull { it.permission == GeckoSession.PermissionDelegate.PERMISSION_TRACKING }
                tab.title = Uri.parse(url).host ?: url.take(48)
                if (!tab.isPrivate && !tab.isGuest && !url.startsWith("about:reader")) { addHistory(url); persistTabs() }
                if (currentTab() === tab) {
                    addressBar.setText(url)
                    pageStatus.text = when { tab.isGuest -> "Guest Mode · temporary data"; tab.isPrivate -> "Private session"; else -> "Secure browsing session" }
                }
            }
        }
        tab.session = session
        if (tab.needsReload && tab.lastUrl != HOME_URL) {
            session.loadUri(tab.lastUrl)
            tab.needsReload = false
        }
        return session
    }

    private fun enforceSessionBudget() {
        val keep = tabs.sortedByDescending { if (it === currentTab()) Long.MAX_VALUE else it.lastAccess }
            .take(4).toSet()
        tabs.forEach { tab ->
            if (tab !in keep && tab.session != null && tab.id != splitTabId) {
                tab.session?.setActive(false)
                tab.session?.setFocused(false)
                tab.session?.close()
                tab.session = null
                tab.needsReload = true
            }
        }
        val activeTab = currentTab()
        pageStatus.text = when {
            activeTab?.isGuest == true -> "Guest Mode · temporary data"
            activeTab?.isPrivate == true -> "Private session"
            else -> "Memory saver · up to 4 live tabs; sleeping tabs reload when reopened"
        }
    }

    private fun currentTab(): BrowserTab? = tabs.getOrNull(activeIndex)

    private fun denyGuestAccess(feature: String): Boolean {
        if (currentTab()?.isGuest != true) return false
        toast("$feature is unavailable in Guest Mode")
        return true
    }

    private fun switchTab(index: Int) {
        if (index !in tabs.indices) return
        if (currentTab()?.isGuest == true && !tabs[index].isGuest) {
            toast("Normal tabs are hidden in Guest Mode")
            return
        }
        currentTab()?.session?.setActive(false)
        currentTab()?.session?.setFocused(false)
        activeIndex = index
        val tab = tabs[index]
        tab.lastAccess = System.nanoTime()
        if (splitTabId == tab.id) exitSplitScreen()
        val session = ensureSession(tab)
        browserView.setSession(session)
        session.setActive(true)
        session.setFocused(true)
        addressBar.setText(tab.lastUrl)
        tabLabel.text = (when { tab.isGuest -> "Guest tab"; tab.isPrivate -> "Private tab"; else -> "Tab" }) + " · ${index + 1}/${tabs.size}"
        pageStatus.text = when { tab.isGuest -> "Guest Mode · temporary data"; tab.isPrivate -> "Private session"; else -> "Normal session · ${tab.group}" }
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
                val session = ensureSession(tab)
                secondaryBrowserView.setSession(session)
                secondaryAddressBar.setText(tab.lastUrl)
                secondaryPane.visibility = View.VISIBLE
                browserPane.visibility = View.VISIBLE
                homePanel.visibility = View.GONE
                session.setActive(true)
                session.setFocused(false)
                splitTabId = tab.id
                toast("Split screen enabled")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun exitSplitScreen() {
        val second = tabs.firstOrNull { it.id == splitTabId }
        second?.session?.setFocused(false)
        second?.session?.setActive(false)
        secondaryPane.visibility = View.GONE
        splitTabId = null
        enforceSessionBudget()
    }

    private fun navigateSecondary(input: String) {
        val tab = tabs.firstOrNull { it.id == splitTabId } ?: return
        val target = BrowserUrlResolver.resolve(input, searchEngine) ?: return
        prepareDesktopModeForNavigation(tab, target)
        tab.lastUrl = target
        tab.title = Uri.parse(target).host ?: target.take(48)
        ensureSession(tab).loadUri(target)
        secondaryAddressBar.setText(target)
        if (!tab.isPrivate && !tab.isGuest) persistTabs()
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
        tab.title = Uri.parse(target).host ?: target.take(48)
        if (!tab.isPrivate && !tab.isGuest) persistTabs()
        showBrowserPage()
        addressBar.setText(target)
        pageStatus.text = if (tab.isPrivate || tab.isGuest) "Private browsing · history disabled" else "Loading…"
        try {
            ensureSession(tab).loadUri(target)
            if (!tab.isPrivate && !tab.isGuest) addHistory(target)
        } catch (error: Exception) {
            pageStatus.text = "Navigation error"
            toast("Could not open page: ${error?.localizedMessage ?: "Unknown error"}")
        }
    }

    private fun goBack() {
        val tab = currentTab() ?: return
        if (tab.canGoBack) tab.session?.goBack() else if (tab.lastUrl == HOME_URL) toast("Already on the home screen") else toast("No previous page")
    }

    private fun goForward() {
        val tab = currentTab() ?: return
        if (tab.canGoForward) tab.session?.goForward() else toast("No next page")
    }

    private fun reloadPage() { currentTab()?.let { ensureSession(it).reload() } }

    private fun showTabs() {
        if (tabs.isEmpty()) return
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 8, 12, 8) }
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
            actions.addView(actionButton("Exit Guest") { closeGuestTabs() })
            actions.addView(actionButton("Save session") { saveNamedSession() })
            actions.addView(actionButton("Workspaces") { showSavedWorkspaces() })
            actions.addView(actionButton("Expand all") { collapsedGroups.clear(); tabDialog?.dismiss(); showTabs() })
            actions.addView(actionButton("Collapse all") { tabs.map { it.group }.distinct().forEach { collapsedGroups.add("NORMAL TABS::$it"); collapsedGroups.add("PRIVATE TABS::$it") }; tabDialog?.dismiss(); showTabs() })
        }
        content.addView(actions)
        val scroll = ScrollView(this)
        val grid = GridLayout(this).apply { columnCount = 2; useDefaultMargins = true }
        val normal = tabs.indices.filter { !tabs[it].isPrivate && !tabs[it].isGuest }
        val privateIndices = tabs.indices.filter { tabs[it].isPrivate && !tabs[it].isGuest }
        val guestIndices = tabs.indices.filter { tabs[it].isGuest }
        fun addSection(label: String, indices: List<Int>) {
            if (indices.isEmpty()) return
            val heading = TextView(this).apply { text = "$label (${indices.size})"; textSize = 13f; setTextColor(0xFFD8CBE8.toInt()); setPadding(8, 14, 8, 6) }
            grid.addView(heading, GridLayout.LayoutParams().apply { columnSpec = GridLayout.spec(0, 2); width = -1 })
            val grouped = indices.groupBy { tabs[it].group.ifBlank { "Ungrouped" } }.toSortedMap()
            grouped.forEach { (groupName, groupIndices) ->
                val groupKey = "$label::$groupName"
                val groupHeading = TextView(this).apply {
                    text = (if (groupKey in collapsedGroups) "▸ " else "▾ ") + "$groupName (${groupIndices.size})"
                    textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFFFFFFFF.toInt()); setPadding(12, 12, 8, 10)
                    setOnClickListener { if (!collapsedGroups.add(groupKey)) collapsedGroups.remove(groupKey); tabDialog?.dismiss(); showTabs() }
                }
                grid.addView(groupHeading, GridLayout.LayoutParams().apply { columnSpec = GridLayout.spec(0, 2); width = -1 })
                if (groupKey in collapsedGroups) return@forEach
                groupIndices.forEach { index ->
                    val tab = tabs[index]
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(12, 12, 12, 12)
                        background = GradientDrawable().apply { setColor(0xFF241B30.toInt()); cornerRadius = 18f; setStroke(1, 0xFF6D4C83.toInt()) }
                    }
                    val headingText = TextView(this).apply { text = tab.title.ifBlank { "New tab" }; textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); maxLines = 2 }
                    val urlText = TextView(this).apply { text = tab.lastUrl; textSize = 10f; setTextColor(0xFFB9AEC8.toInt()); maxLines = 2; setPadding(0, 5, 0, 5) }
                    card.addView(headingText); card.addView(urlText)
                    card.addView(actionButton("Open") { tabDialog?.dismiss(); switchTab(index) })
                    card.addView(actionButton("Group / rename") { tabDialog?.dismiss(); editTabGroup(index) })
                    card.addView(actionButton("Close") { tabDialog?.dismiss(); closeTabAt(index) })
                    grid.addView(card, GridLayout.LayoutParams().apply { width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(5, 5, 5, 5) })
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
        val input = EditText(this).apply { setText(tab.title); hint = "Tab name"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Rename tab").setView(input)
            .setPositiveButton("Save") { _, _ -> tab.title = input.text.toString().trim().ifBlank { "New tab" }; persistTabs(); toast("Tab renamed") }
            .setNegativeButton("Cancel", null).show()
    }

    private fun closeTabAt(index: Int) {
        if (index !in tabs.indices) return
        val wasActive = index == activeIndex
        val wasGuestActive = currentTab()?.isGuest == true
        val removed = tabs.removeAt(index)
        if (removed.id == splitTabId) exitSplitScreen()
        removed.session?.close()
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
        if (wasActive) switchTab(activeIndex.coerceIn(tabs.indices))
    }

    private fun saveNamedSession() {
        if (denyGuestAccess("Saving workspaces")) return
        val input = EditText(this).apply { hint = "Session name"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Save workspace").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "Saved workspace" }
                val urls = JSONArray()
                tabs.filter { !it.isPrivate && !it.isGuest }.forEach { urls.put(JSONObject().put("url", it.lastUrl).put("group", it.group).put("title", it.title)) }
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
        val saved = JSONArray()
        tabs.filter { !it.isPrivate && !it.isGuest }.forEach { tab ->
            saved.put(JSONObject().put("url", tab.lastUrl).put("group", tab.group).put("title", tab.title)
                .put("readerOriginalUrl", tab.readerOriginalUrl ?: ""))
        }
        val normalTabs = tabs.filter { !it.isPrivate && !it.isGuest }
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
                    desktopSite = isDesktopUrl(url),
                    readerOriginalUrl = item.optString("readerOriginalUrl")
                        .takeIf { it.startsWith("http://") || it.startsWith("https://") }
                )
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
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                createTab(false, item.optString("url", HOME_URL))
                tabs.last().group = item.optString("group", "Ungrouped")
                tabs.last().title = item.optString("title", "New tab")
                tabs.last().readerOriginalUrl = item.optString("readerOriginalUrl").takeIf { it.startsWith("http://") || it.startsWith("https://") }
            }
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
        if (existing >= 0) { items.removeAt(existing); saveBookmarks(items); toast("Bookmark removed") }
        else {
            val priorDelete = runCatching { JSONObject(prefs.getString("bookmark_tombstones", "{}") ?: "{}").optLong(tab.lastUrl) }.getOrDefault(0L)
            items.add(BookmarkEntry(tab.lastUrl, tab.title, "General", maxOf(System.currentTimeMillis(), priorDelete + 1L)))
            saveBookmarks(items); toast("Bookmark saved")
        }
    }

    private fun showBookmarks() {
        val items = readBookmarks()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 8, 12, 8) }
        val search = EditText(this).apply { hint = "Search bookmarks"; isSingleLine = true }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        content.addView(search)
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val dialog = AlertDialog.Builder(this).setTitle("Bookmarks · ${items.size}").setView(content)
            .setPositiveButton("New folder") { _, _ -> createBookmarkFolder() }
            .setNeutralButton("Import / export") { _, _ -> showBookmarkTransfer() }
            .setNegativeButton("Close", null).create()
        fun render(query: String) {
            list.removeAllViews()
            val filtered = items.filter { it.title.contains(query, true) || it.url.contains(query, true) || it.folder.contains(query, true) }
                .sortedWith(compareBy<BookmarkEntry> { it.folder.lowercase(Locale.ROOT) }.thenBy { it.title.lowercase(Locale.ROOT) })
            filtered.groupBy { it.folder }.forEach { (folder, entries) ->
                list.addView(TextView(this).apply { text = "$folder (${entries.size})"; setTextColor(0xFFD8CBE8.toInt()); textSize = 12f; setPadding(8, 12, 8, 5); typeface = Typeface.DEFAULT_BOLD })
                entries.forEach { entry ->
                    list.addView(TextView(this).apply {
                        text = "★  ${entry.title}\n${entry.url}"; textSize = 13f; setTextColor(0xFFFFFFFF.toInt()); setPadding(12, 10, 8, 10)
                        setOnClickListener { dialog.dismiss(); navigate(entry.url) }
                        setOnLongClickListener { showBookmarkActions(entry); true }
                    })
                }
            }
            if (filtered.isEmpty()) list.addView(TextView(this).apply { text = "No matching bookmarks"; setPadding(12, 20, 12, 20) })
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        render("")
        dialog.show()
    }

    private fun showBookmarkActions(entry: BookmarkEntry) {
        AlertDialog.Builder(this).setTitle(entry.title)
            .setItems(arrayOf("Edit title / folder", "Delete bookmark", "Open")) { _, which ->
                when (which) {
                    0 -> editBookmark(entry)
                    1 -> { val items = readBookmarks().filterNot { it.url == entry.url }; saveBookmarks(items); toast("Bookmark deleted") }
                    2 -> navigate(entry.url)
                }
            }.show()
    }

    private fun editBookmark(entry: BookmarkEntry) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 8, 20, 0) }
        val title = EditText(this).apply { setText(entry.title); hint = "Title" }
        val folder = EditText(this).apply { setText(entry.folder); hint = "Folder name" }
        form.addView(title); form.addView(folder)
        AlertDialog.Builder(this).setTitle("Edit bookmark").setView(form)
            .setPositiveButton("Save") { _, _ ->
                val items = readBookmarks().map { if (it.url == entry.url) it.copy(title = title.text.toString().trim().ifBlank { entry.title }, folder = folder.text.toString().trim().ifBlank { "General" }) else it }
                saveBookmarks(items); toast("Bookmark updated")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun createBookmarkFolder() {
        val input = EditText(this).apply { hint = "Folder name"; isSingleLine = true }
        AlertDialog.Builder(this).setTitle("Create bookmark folder").setView(input)
            .setPositiveButton("Create") { _, _ ->
                val folder = input.text.toString().trim()
                if (folder.isNotBlank()) { prefs.edit().putStringSet("bookmark_folders", (prefs.getStringSet("bookmark_folders", emptySet()) ?: emptySet()) + folder).apply(); toast("Folder created; assign bookmarks by editing them") }
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

    private fun clearBrowsingHistory() {
        if (denyGuestAccess("Clearing owner history")) return
        prefs.edit().putLong("history_cleared_at", System.currentTimeMillis()).remove("history").remove("history_v2").apply()
    }

    private fun addHistory(url: String) {
        if (url.isBlank() || url == HOME_URL) return
        val items = readHistory().filterNot { it.url == url }.toMutableList()
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
        if (currentTab()?.let { it.isPrivate || it.isGuest } == true) { toast("History is unavailable in private or Guest Mode"); return }
        val items = readHistory().sortedByDescending { it.visitedAt }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 8, 12, 8) }
        val search = EditText(this).apply { hint = "Search history"; isSingleLine = true }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(search); content.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val dialog = AlertDialog.Builder(this).setTitle("History · ${items.size}").setView(content)
            .setNeutralButton("Clear history") { _, _ -> clearBrowsingHistory(); toast("Browsing history cleared") }
            .setNegativeButton("Close", null).create()
        val dateFormat = SimpleDateFormat("EEEE, dd MMM yyyy", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        fun render(query: String) {
            list.removeAllViews()
            val filtered = items.filter { it.url.contains(query, true) || it.title.contains(query, true) }
            filtered.groupBy { if (it.visitedAt > 0) dateFormat.format(Date(it.visitedAt)) else "Earlier history" }.forEach { (day, entries) ->
                list.addView(TextView(this).apply { text = day; setTextColor(0xFFD8CBE8.toInt()); typeface = Typeface.DEFAULT_BOLD; setPadding(8, 12, 8, 6) })
                entries.forEach { entry ->
                    list.addView(TextView(this).apply {
                        text = "${entry.title}  ${if (entry.visitedAt > 0) timeFormat.format(Date(entry.visitedAt)) else ""}\n${entry.url}"
                        setTextColor(0xFFFFFFFF.toInt()); textSize = 12f; setPadding(12, 9, 8, 9)
                        setOnClickListener { dialog.dismiss(); navigate(entry.url) }
                    })
                }
            }
            if (filtered.isEmpty()) list.addView(TextView(this).apply { text = "No matching history"; setPadding(12, 20, 12, 20) })
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        render(""); dialog.show()
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
        if (!::pageStatus.isInitialized || items.isEmpty()) return
        val active = items.count { it.state == "RUNNING" || it.state == "QUEUED" || it.state == "FINALIZING" }
        if (active > 0 && currentTab()?.isPrivate != true && currentTab()?.isGuest != true) {
            pageStatus.text = "Downloads · $active active"
        }
    }

    private fun showDownloads() {
        if (denyGuestAccess("Downloads")) return
        val items = novaDownloads.items()
        if (items.isEmpty()) { downloadPrompt(); return }
        val rows = items.map { item ->
            val state = when (item.state) {
                "RUNNING" -> "Downloading · ${item.progressLabel()}"
                "QUEUED" -> "Queued"
                "PAUSED" -> "Paused · ${item.progressLabel()}"
                "FINALIZING", "SAVING" -> "Saving to Downloads… · ${item.progressLabel()}"
                "INTERRUPTED" -> "Interrupted · download again from the page"
                "COMPLETE" -> "Complete · ${item.progressLabel()}"
                "FAILED" -> "Failed · ${item.error}"
                else -> item.state
            }
            "${item.fileName}\n$state"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Nova Downloads · ${items.size}")
            .setItems(rows) { _, which -> showDownloadActions(items[which]) }
            .setPositiveButton("New download") { _, _ -> downloadPrompt() }
            .setNeutralButton("Refresh", null)
            .setNegativeButton("Close", null).show()
    }

    private fun showDownloadActions(item: NovaDownloadManager.Item) {
        val actions = when (item.state) {
            "RUNNING", "QUEUED" -> arrayOf("Pause", "Cancel")
            "FINALIZING" -> arrayOf("Close")
            "SAVING" -> arrayOf("Cancel")
            "PAUSED", "FAILED" -> arrayOf("Resume", "Cancel")
            "INTERRUPTED" -> arrayOf("Remove from list")
            "COMPLETE" -> arrayOf("Open file", "Remove from list")
            else -> arrayOf("Resume", "Cancel")
        }
        AlertDialog.Builder(this).setTitle(item.fileName).setItems(actions) { _, which ->
            when (actions[which]) {
                "Pause" -> { novaDownloads.pause(item.id); toast("Pausing download…") }
                "Resume" -> { novaDownloads.resume(item.id); toast("Resuming with HTTP Range when supported…") }
                "Cancel" -> { novaDownloads.cancel(item.id); toast("Download cancelled") }
                "Open file" -> openDownloadedFile(item)
                "Remove from list" -> novaDownloads.cancel(item.id)
                "Close" -> Unit
            }
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
        tab.session?.let { it.setActive(false); it.setFocused(false); it.close() }
        tab.session = null; tab.needsReload = true
        val session = ensureSession(tab)
        browserView.setSession(session); session.setActive(true); session.setFocused(true)
        session.loadUri(tab.lastUrl)
        toast(if (tab.desktopSite) "Desktop site enabled for $host" else "Mobile site restored for $host")
    }

    private fun openReaderMode() {
        val tab = currentTab() ?: return
        val url = tab.lastUrl
        if (!url.startsWith("http")) { toast("Open an article first"); return }
        tab.readerOriginalUrl = url
        val readerUrl = "about:reader?url=${Uri.encode(url)}"
        tab.lastUrl = readerUrl
        addressBar.setText(readerUrl)
        tabLabel.text = "Reader view"
        showBrowserPage()
        ensureSession(tab).loadUri(readerUrl)
        AlertDialog.Builder(this).setTitle("Reader view")
            .setMessage("GeckoView's built-in reader extracts the article and provides its own reading controls. Use the browser menu to leave Reader view.")
            .setPositiveButton("OK", null).setNeutralButton("Exit reader") { _, _ -> exitReaderMode() }.show()
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
            tab.session?.let { it.setActive(false); it.setFocused(false); it.close() }
            tab.session = null
            tab.desktopSite = desired
            tab.needsReload = false
            val newSession = ensureSession(tab)
            if (currentTab() === tab) browserView.setSession(newSession)
            if (tab.id == splitTabId) secondaryBrowserView.setSession(newSession)
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
            removed.session?.close()
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
                searchEngineButton.text = "$searchEngine ▾"
                searchEngineButton.contentDescription = "Choose search engine. Current: $searchEngine"
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
                val detail = buildString {
                    append("Extension ID: ").append(extension.id)
                    if (permissions.isNotEmpty()) append("\\n\\nPermissions:\\n• ").append(permissions.joinToString("\\n• "))
                    if (origins.isNotEmpty()) append("\\n\\nWebsite access:\\n• ").append(origins.joinToString("\\n• "))
                    if (dataCollectionPermissions.isNotEmpty()) append("\\n\\nData collection requested:\\n• ").append(dataCollectionPermissions.joinToString("\\n• "))
                    append("\\n\\nPrivate browsing access and optional data collection remain disabled.")
                }
                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity).setTitle("Install extension?")
                        .setMessage(detail)
                        .setPositiveButton("Grant listed permissions") { _, _ -> result.complete(WebExtension.PermissionPromptResponse(true, false, false)) }
                        .setNegativeButton("Cancel") { _, _ -> result.complete(WebExtension.PermissionPromptResponse(false, false, false)) }
                        .setOnCancelListener { result.complete(WebExtension.PermissionPromptResponse(false, false, false)) }
                        .show()
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
                        toast("Nova Shield: ${mode.name.lowercase().replaceFirstChar { it.uppercase() }}")
                    }
                    3 -> {
                        val permission = tab?.trackingPermission
                        if (permission == null) { toast("Open a website first; site tracking controls are not available on this page"); return@setItems }
                        val allow = permission.value != GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                        runtime.storageController.setPermission(permission, if (allow) GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                        toast(if (allow) "Trackers allowed for $host · reload the page" else "Tracking protection restored for $host · reload the page")
                        tab.session?.reload()
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
        if (!::runtime.isInitialized) return
        if (enabled) {
            runtime.settings.setTrustedRecursiveResolverUri(ADGUARD_DOH_ENDPOINT)
            // ONLY mode intentionally has no system-DNS fallback if the encrypted resolver fails.
            runtime.settings.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TR...)
        } else {
            runtime.settings.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TR...)
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
            .setMessage("When enabled, Nova's GeckoView browser engine sends DNS lookups to AdGuard over DNS-over-HTTPS. Strict mode does not fall back to unencrypted DNS if the resolver is unavailable.\n\nThis protects DNS lookups made by Nova only. It is not a VPN, does not hide your IP address, and does not change DNS for other apps or Android itself.")
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
                val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 8, 20, 0); addView(prompt); addView(includePage) }
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
        if (denyGuestAccess("Extensions")) return
        val controller = runtime.getWebExtensionController()
        controller.list().accept({ extensions ->
            runOnUiThread {
                val installed = extensions.orEmpty()
                val rows = installed.map { "${it?.metaData?.name ?: it?.id ?: "Extension"} · ${if (it.metaData.enabled) "Enabled" else "Disabled"}" }.toTypedArray()
                AlertDialog.Builder(this).setTitle("Nova Add-ons · ${installed.size}").setMessage("GeckoView supports signed Firefox-compatible WebExtensions. Chrome/Chromium extensions are not supported; compatibility depends on the embedded GeckoView version.")
                    .setItems(rows) { _, which ->
                        val extension = installed[which]
                        val actions = if (extension.metaData.enabled) arrayOf("Disable", "Uninstall") else arrayOf("Enable", "Uninstall")
                        AlertDialog.Builder(this).setTitle(extension.metaData.name ?: extension.id)
                            .setItems(actions) { _, action ->
                                when (actions[action]) {
                                    "Disable" -> controller.disable(extension, WebExtensionController.EnableSource.USER).accept({ toast("Extension disabled") }, { toast("Could not disable: ${it?.localizedMessage ?: "Unknown error"}") })
                                    "Enable" -> controller.enable(extension, WebExtensionController.EnableSource.USER).accept({ toast("Extension enabled") }, { toast("Could not enable: ${it?.localizedMessage ?: "Unknown error"}") })
                                    "Uninstall" -> AlertDialog.Builder(this).setMessage("Remove this extension and its data?")
                                        .setPositiveButton("Uninstall") { _, _ -> controller.uninstall(extension).accept({ toast("Extension removed") }, { toast("Could not uninstall: ${it?.localizedMessage ?: "Unknown error"}") }) }
                                        .setNegativeButton("Cancel", null).show()
                                }
                            }.setNegativeButton("Close", null).show()
                    }
                    .setPositiveButton("Install signed XPI URL") { _, _ -> installExtensionPrompt() }
                    .setNeutralButton("Browse add-ons") { _, _ -> createTab(false, "https://addons.mozilla.org/android/") }
                    .setNegativeButton("Close", null).show()
            }
        }, { error -> runOnUiThread { toast("Could not list extensions: ${error?.localizedMessage ?: "Unknown error"}") } })
    }

    private fun installExtensionPrompt() {
        val input = EditText(this).apply { hint = "https://…/extension.xpi"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Install signed extension")
            .setMessage("Enter an HTTPS .xpi URL. GeckoView verifies Mozilla signing and asks before granting permissions.")
            .setView(input).setPositiveButton("Install") { _, _ ->
                val url = input.text.toString().trim()
                if (!url.startsWith("https://") || !url.substringBefore('?').endsWith(".xpi", true)) { toast("Use a direct HTTPS .xpi URL"); return@setPositiveButton }
                runtime.getWebExtensionController().install(url, WebExtensionController.INSTALLATION_METHOD_MANAGER)
                    .accept({ toast("Extension installed: ${it?.metaData?.name ?: it?.id ?: "Extension"}") }, { toast("Extension install failed: ${it?.localizedMessage ?: "Unknown error"}") })
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showPasswordVault() {
        if (denyGuestAccess("Password vault")) return
        val entries = runCatching { passwordVault.all() }.getOrElse { toast("Password vault could not be unlocked: ${it?.localizedMessage ?: "Unknown error"}"); return }
        val rows = entries.map { "${Uri.parse(it.origin).host ?: it.origin} · ${it.username}" }.toTypedArray()
        val labels = rows + "Add login manually"
        AlertDialog.Builder(this).setTitle("Encrypted password vault · ${entries.size}")
            .setItems(labels) { _, which ->
                if (which == entries.size) { addManualLogin(); return@setItems }
                val entry = entries[which]
                AlertDialog.Builder(this).setTitle(entry.username)
                    .setItems(arrayOf("Copy username", "Copy password", "Delete saved login")) { _, action ->
                        when (action) {
                            0 -> copyToClipboard("Username", entry.username)
                            1 -> copyToClipboard("Password", entry.password)
                            2 -> AlertDialog.Builder(this).setMessage("Delete this saved login from the encrypted vault?")
                                .setPositiveButton("Delete") { _, _ -> passwordVault.delete(entry.guid ?: ""); toast("Login deleted") }
                                .setNegativeButton("Cancel", null).show()
                        }
                    }.setNegativeButton("Close", null).show()
            }.setPositiveButton("Close", null)
            .setNeutralButton("Add login") { _, _ -> addManualLogin() }.show()
    }

    private fun addManualLogin() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 8, 20, 0) }
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
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        toast("$label copied · clear your clipboard when finished")
    }

    private fun showSiteControls() {
        if (denyGuestAccess("Site controls")) return
        val tab = currentTab() ?: return
        val host = runCatching { Uri.parse(tab.lastUrl).host }.getOrNull()
        if (host.isNullOrBlank()) { toast("Open a website to manage its permissions"); return }
        val perms = tab.contentPermissions.filter { it.permission != GeckoSession.PermissionDelegate.PERMISSION_TRACKING }
        val permissionNames = mapOf(
            GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION to "Location",
            GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION to "Notifications",
            GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE to "Persistent storage",
            GeckoSession.PermissionDelegate.PERMISSION_XR to "XR",
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE to "Audible autoplay",
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE to "Silent autoplay",
            GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS to "DRM media",
            GeckoSession.PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS to "Local network",
            GeckoSession.PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS to "Local device"
        )
        val rows = perms.map { "${permissionNames[it.permission] ?: "Permission ${it.permission}"} · ${when (it.value) { GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW -> "Allowed"; GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY -> "Blocked"; else -> "Ask" }}" }.toTypedArray()
        val options = rows + "Allow / block trackers for this site" + "Clear this site's cookies and storage"
        AlertDialog.Builder(this).setTitle("Site controls · $host")
            .setItems(options) { _, which ->
                when {
                    which < perms.size -> {
                        val perm = perms[which]
                        val next = if (perm.value == GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW) GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY else GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                        runtime.storageController.setPermission(perm, next)
                        toast("Site permission updated · reload the page")
                    }
                    which == perms.size -> showShieldSettings()
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

    private fun showSettings() {
        if (currentTab()?.isGuest == true) {
            AlertDialog.Builder(this).setTitle("Guest Mode settings")
                .setMessage("Normal tabs, saved passwords, downloads, exports and cloud account settings are hidden while Guest Mode is active.")
                .setItems(arrayOf("Tor routing · Orbot", "Exit Guest Mode")) { _, which -> if (which == 0) openOrbotTorRouting() else confirmExitGuestMode() }
                .setNegativeButton("Close", null).show()
            return
        }
        val shortcutState = if (prefs.getBoolean("show_home_shortcuts", true)) "shown" else "hidden"
        val items = arrayOf("Choose search engine", "Toggle home shortcuts (currently $shortcutState)", "Clear browsing history", "Close private and Guest tabs", "Exit Guest Mode", "Exit Reader view", "Nova Shield", "AI assistants", "Extensions", "Password manager", "Site permissions", "Accessibility", "Export history", "About Nova", "Account & encrypted cloud sync", "Protected DNS · AdGuard DNS")
        AlertDialog.Builder(this).setTitle("Nova Settings").setItems(items) { _, which ->
            when (which) {
                0 -> chooseSearchEngine()
                1 -> {
                    val show = !prefs.getBoolean("show_home_shortcuts", true)
                    prefs.edit().putBoolean("show_home_shortcuts", show).apply()
                    homeShortcuts.visibility = if (show) View.VISIBLE else View.GONE
                    toast(if (show) "Home shortcuts shown" else "Home shortcuts hidden")
                }
                2 -> { clearBrowsingHistory(); toast("Browsing history cleared") }
                3 -> {
                    val activeId = currentTab()?.id
                    tabs.indices.filter { tabs[it].isPrivate || tabs[it].isGuest }.reversed().forEach { index ->
                        val removed = tabs.removeAt(index)
                        if (removed.id == splitTabId) exitSplitScreen()
                        removed.session?.close()
                    }
                    cleanupGuestContextIfIdle()
                    if (tabs.isEmpty()) createTab(false, HOME_URL) else {
                        activeIndex = tabs.indexOfFirst { it.id == activeId }.takeIf { it >= 0 } ?: tabs.lastIndex
                        switchTab(activeIndex)
                    }
                    toast("Private and Guest tabs closed")
                }
                4 -> closeGuestTabs()
                5 -> exitReaderMode()
                6 -> showShieldSettings()
                7 -> showAiAssistant()
                8 -> showExtensions()
                9 -> showPasswordVault()
                10 -> showSiteControls()
                11 -> showAccessibilitySettings()
                12 -> exportHistory()
                13 -> AlertDialog.Builder(this).setMessage("Nova Browser · GeckoView engine")
                    .setPositiveButton("OK", null).show()
                14 -> showCloudAccount()
                15 -> showProtectedDnsSettings()
            }
        }.show()
    }


    private fun showCloudAccount() {
        if (denyGuestAccess("Nova Cloud")) return
        val email = cloudSync.signedInEmail()
        if (email.isNullOrBlank()) {
            val input = EditText(this).apply { hint = "Email address"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS; isSingleLine = true }
            AlertDialog.Builder(this).setTitle("Sign in to Nova Cloud").setMessage("We'll email you a one-time code. No password is required.")
                .setView(input).setPositiveButton("Send code") { _, _ ->
                    val address = input.text.toString().trim()
                    toast("Requesting sign-in code…")
                    Thread {
                        runCatching { cloudSync.sendEmailOtp(address) }
                            .onSuccess { runOnUiThread { showCloudOtp(address) } }
                            .onFailure { runOnUiThread { toast("Could not send code: ${it.message ?: "Network error"}") } }
                    }.start()
                }.setNegativeButton("Cancel", null).show()
            return
        }
        val enabled = prefs.getBoolean("cloud_sync_enabled", false)
        val lastSync = prefs.getLong("cloud_sync_last_success_at", 0L)
        val lastSyncText = if (lastSync > 0L) SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(lastSync)) else "Not synced yet"
        val syncError = prefs.getString("cloud_sync_last_error", null)
        val check = CheckBox(this).apply { text = "Allow encrypted cloud sync from this device"; isChecked = enabled; setPadding(24, 12, 24, 12) }
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
                    prefs.edit().putBoolean("cloud_sync_enabled", false).apply()
                    runOnUiThread { toast("Signed out. Local browsing data is unchanged.") }
                }.start()
            }.setNegativeButton("Close", null).show()
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
            cloudSync.uploadEncrypted(CloudCrypto.encrypt(merged.toString(), passphrase),
                CloudCrypto.encrypt(mergedVault.toString(), passphrase))
            if (prefs.getBoolean("cloud_sync_enabled", false)) {
                AutoSyncPassphraseStore.save(this, passphrase)
                NovaAutoSync.schedule(this)
            }
            runOnUiThread {
                applySyncSnapshot(merged)
                passwordVault.importSyncBundle(mergedVault)
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
        searchEngineButton.text = "$searchEngine ▾"
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }.toSet()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onPause() {
        persistTabs()
        super.onPause()
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

    override fun onDestroy() {
        persistTabs()
        tabs.forEach { it.session?.close() }
        tabs.clear()
        if (::runtime.isInitialized) {
            if (guestContextId.isNotBlank()) runtime.storageController.clearDataForSessionContext(guestContextId)
            runtime.shutdown()
        }
        super.onDestroy()
    }

    companion object {
        private const val HOME_URL = "about:blank"
        private const val ADGUARD_DOH_ENDPOINT = "https://dns.adguard-dns.com/dns-query"
    }
}
