package com.nova.browser

import android.app.Activity
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
import android.content.Intent
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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

/** Nova browser core. GeckoView is the only rendering engine. */
class MainActivity : Activity() {
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
    private lateinit var novaDownloads: NovaDownloadManager
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
        var readerOriginalUrl: String? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        searchEngine = prefs.getString("search_engine", "Google") ?: "Google"
        runtime = GeckoRuntime.create(this)
        novaDownloads = NovaDownloadManager(this) { items -> runOnUiThread { updateDownloadSummary(items) } }
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
            setPadding(8, 8, 8, 4)
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
            setPadding(12, 0, 12, 0)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO) {
                    navigate(text.toString())
                    true
                } else false
            }
        }
        addressRow.addView(addressBar, LinearLayout.LayoutParams(0, 48, 1f))
        searchEngineButton = actionButton("$searchEngine ▾") { chooseSearchEngine() }
        searchEngineButton.contentDescription = "Choose search engine. Current: $searchEngine"
        addressRow.addView(searchEngineButton)
        addressRow.addView(actionButton("Go") { navigate(addressBar.text.toString()) })
        top.addView(addressRow)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controls.addView(actionButton("←") { goBack() })
        controls.addView(actionButton("→") { goForward() })
        controls.addView(actionButton("↻") { reloadPage() })
        controls.addView(actionButton("Home") { goHome() })
        controls.addView(actionButton("＋") { createTab(false, HOME_URL) })
        controls.addView(actionButton("Tabs") { showTabs() })
        controls.addView(actionButton("Split") { chooseSplitTab() })
        controls.addView(actionButton("Private") { createTab(true, HOME_URL) })
        controls.addView(actionButton("Guest") { createGuestTab() })
        top.addView(controls)

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
        top.addView(tools)

        tabLabel = TextView(this).apply {
            text = "Nova Browser"
            textSize = 12f
            setTextColor(0xFFD8CBE8.toInt())
            setPadding(4, 5, 4, 5)
        }
        pageStatus = TextView(this).apply {
            text = "Ready"
            textSize = 11f
            setTextColor(0xFFB9AEC8.toInt())
            setPadding(4, 0, 4, 4)
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
        }
        val secondaryControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(secondaryAddressBar, LinearLayout.LayoutParams(0, 42, 1f))
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
        setContentView(root)
    }

    private fun createHomePanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(20, 28, 20, 20)
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
            setPadding(0, 0, 0, 8)
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
            setPadding(0, 4, 0, 24)
        }
        val shortcutTitle = TextView(this).apply {
            text = "QUICK ACCESS"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            setTextColor(0xFFD8CBE8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 8)
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
        tabLabel.text = "Nova Browser · Home"
        pageStatus.text = "Ready · $searchEngine"
        searchEngineButton.text = "$searchEngine ▾"
        searchEngineButton.contentDescription = "Choose search engine. Current: $searchEngine"
        homeShortcuts.visibility = if (prefs.getBoolean("show_home_shortcuts", true)) View.VISIBLE else View.GONE
    }

    private fun goHome() {
        currentTab()?.lastUrl = HOME_URL
        showHomeScreen()
    }

    private fun showBrowserPage() {
        homePanel.visibility = View.GONE
        browserPane.visibility = View.VISIBLE
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        contentDescription = label
        textSize = 11f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(7, 0, 7, 0)
        setOnClickListener { action() }
    }

    private fun createTab(isPrivate: Boolean, url: String, isGuest: Boolean = false) {
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
        pageStatus.text = "Memory saver · up to 4 live tabs; sleeping tabs reload when reopened"
    }

    private fun currentTab(): BrowserTab? = tabs.getOrNull(activeIndex)

    private fun switchTab(index: Int) {
        if (index !in tabs.indices) return
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
            toast("Could not open page: ${error.localizedMessage}")
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
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        actions.addView(actionButton("＋ Normal") { createTab(false, HOME_URL) })
        actions.addView(actionButton("＋ Private") { createTab(true, HOME_URL) })
        actions.addView(actionButton("＋ Guest") { createGuestTab() })
        actions.addView(actionButton("Exit Guest") { closeGuestTabs() })
        actions.addView(actionButton("Save session") { saveNamedSession() })
        actions.addView(actionButton("Workspaces") { showSavedWorkspaces() })
        actions.addView(actionButton("Expand all") { collapsedGroups.clear(); tabDialog?.dismiss(); showTabs() })
        actions.addView(actionButton("Collapse all") { tabs.map { it.group }.distinct().forEach { collapsedGroups.add("NORMAL TABS::$it"); collapsedGroups.add("PRIVATE TABS::$it") }; tabDialog?.dismiss(); showTabs() })
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
        addSection("NORMAL TABS", normal)
        addSection("PRIVATE TABS", privateIndices)
        addSection("GUEST TABS", guestIndices)
        scroll.addView(grid)
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        tabDialog = AlertDialog.Builder(this).setTitle("Tabs · ${tabs.size}")
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
        val removed = tabs.removeAt(index)
        if (removed.id == splitTabId) exitSplitScreen()
        removed.session?.close()
        cleanupGuestContextIfIdle()
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
        val input = EditText(this).apply { hint = "Session name"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Save workspace").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "Saved workspace" }
                val urls = JSONArray()
                tabs.filter { !it.isPrivate && !it.isGuest }.forEach { urls.put(JSONObject().put("url", it.lastUrl).put("group", it.group).put("title", it.title)) }
                val snapshots = prefs.getStringSet("saved_workspaces", emptySet())?.toMutableSet() ?: mutableSetOf()
                snapshots.add(JSONObject().put("name", name).put("tabs", urls).toString())
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
                createTab(false, item.optString("url", HOME_URL))
                tabs.last().group = item.optString("group", "Ungrouped")
                tabs.last().title = item.optString("title", "New tab")
                tabs.last().readerOriginalUrl = item.optString("readerOriginalUrl").takeIf { it.startsWith("http://") || it.startsWith("https://") }
            }
            if (tabs.isNotEmpty()) {
                val index = savedActiveIndex.coerceIn(tabs.indices)
                switchTab(index)
            }
        } catch (_: Exception) {
            prefs.edit().remove("open_normal_tabs").apply()
        }
    }

    private fun showSavedWorkspaces() {
        val saved = prefs.getStringSet("saved_workspaces", emptySet())?.toList().orEmpty()
        if (saved.isEmpty()) { toast("No saved workspaces yet"); return }
        val labels = saved.map { runCatching { JSONObject(it).optString("name", "Workspace") }.getOrDefault("Workspace") }
        AlertDialog.Builder(this).setTitle("Saved workspaces").setItems(labels.toTypedArray()) { _, which ->
            runCatching {
                val data = JSONObject(saved[which]).getJSONArray("tabs")
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    createTab(false, item.optString("url", HOME_URL))
                    tabs.last().group = item.optString("group", "Ungrouped")
                    tabs.last().title = item.optString("title", "New tab")
                tabs.last().readerOriginalUrl = item.optString("readerOriginalUrl").takeIf { it.startsWith("http://") || it.startsWith("https://") }
                }
                persistTabs(); toast("Workspace restored")
            }.onFailure { toast("Could not restore workspace") }
        }.setNegativeButton("Close", null).show()
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

    private fun saveBookmarks(items: List<BookmarkEntry>) {
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
        else { items.add(BookmarkEntry(tab.lastUrl, tab.title, "General", System.currentTimeMillis())); saveBookmarks(items); toast("Bookmark saved") }
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
        bookmarkTransferMode = "export"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"; putExtra(Intent.EXTRA_TITLE, "nova-bookmarks.json")
        }
        startActivityForResult(intent, bookmarkRequestCode)
    }

    private fun importBookmarks() {
        bookmarkTransferMode = "import"
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/json" }
        startActivityForResult(intent, bookmarkRequestCode)
    }

    private fun addHistory(url: String) {
        if (url.isBlank() || url == HOME_URL) return
        val items = readHistory().filterNot { it.url == url }.toMutableList()
        items.add(HistoryEntry(url, Uri.parse(url).host ?: url, System.currentTimeMillis()))
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
            .setNeutralButton("Clear history") { _, _ -> prefs.edit().remove("history").remove("history_v2").apply(); toast("Browsing history cleared") }
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

    private fun startDownload(input: String) {
        if (currentTab()?.let { it.isPrivate || it.isGuest } == true) {
            toast("Downloads are disabled in private and Guest Mode because files remain on the device")
            return
        }
        val url = BrowserUrlResolver.resolve(input, searchEngine) ?: return
        if (!url.startsWith("https://") && !url.startsWith("http://")) { toast("Only HTTP and HTTPS downloads are supported"); return }
        runCatching { novaDownloads.enqueue(url); toast("Download started · resumable") }
            .onFailure { toast("Download could not start: ${it.localizedMessage}") }
    }

    private fun updateDownloadSummary(items: List<NovaDownloadManager.Item>) {
        if (!::pageStatus.isInitialized || items.isEmpty()) return
        val active = items.count { it.state == "RUNNING" || it.state == "QUEUED" || it.state == "FINALIZING" }
        if (active > 0 && currentTab()?.isPrivate != true && currentTab()?.isGuest != true) {
            pageStatus.text = "Downloads · $active active"
        }
    }

    private fun showDownloads() {
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
        }.onFailure { toast("No app can open this file: ${it.localizedMessage}") }
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

    private fun showSettings() {
        val shortcutState = if (prefs.getBoolean("show_home_shortcuts", true)) "shown" else "hidden"
        val items = arrayOf("Choose search engine", "Toggle home shortcuts (currently $shortcutState)", "Clear browsing history", "Close private and Guest tabs", "Exit Guest Mode", "Exit Reader view", "About Nova")
        AlertDialog.Builder(this).setTitle("Nova Settings").setItems(items) { _, which ->
            when (which) {
                0 -> chooseSearchEngine()
                1 -> {
                    val show = !prefs.getBoolean("show_home_shortcuts", true)
                    prefs.edit().putBoolean("show_home_shortcuts", show).apply()
                    homeShortcuts.visibility = if (show) View.VISIBLE else View.GONE
                    toast(if (show) "Home shortcuts shown" else "Home shortcuts hidden")
                }
                2 -> { prefs.edit().remove("history").remove("history_v2").apply(); toast("Browsing history cleared") }
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
                6 -> AlertDialog.Builder(this).setMessage("Nova Browser · GeckoView engine")
                    .setPositiveButton("OK", null).show()
            }
        }.show()
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
            }.onFailure { toast("Bookmark transfer failed: ${it.localizedMessage}") }
        }
    }

    override fun onBackPressed() {
        val tab = currentTab()
        if (tab?.canGoBack == true) tab.session?.goBack() else super.onBackPressed()
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

    companion object { private const val HOME_URL = "about:blank" }
}
