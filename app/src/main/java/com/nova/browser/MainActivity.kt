package com.nova.browser

import android.app.Activity
import android.app.AlertDialog
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

    private data class BrowserTab(
        var session: GeckoSession?,
        val isPrivate: Boolean,
        val id: String = UUID.randomUUID().toString(),
        var lastUrl: String = HOME_URL,
        var group: String = "Ungrouped",
        var title: String = "New tab",
        var canGoBack: Boolean = false,
        var canGoForward: Boolean = false,
        var lastAccess: Long = 0L,
        var needsReload: Boolean = false
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        searchEngine = prefs.getString("search_engine", "Google") ?: "Google"
        runtime = GeckoRuntime.create(this)
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
        top.addView(controls)

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tools.addView(actionButton("☆") { toggleBookmark() })
        tools.addView(actionButton("Bookmarks") { showBookmarks() })
        tools.addView(actionButton("History") { showHistory() })
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

    private fun createTab(isPrivate: Boolean, url: String) {
        currentTab()?.session?.setActive(false)
        currentTab()?.session?.setFocused(false)
        val tab = BrowserTab(null, isPrivate, lastUrl = url)
        tabs.add(tab)
        activeIndex = tabs.lastIndex
        val session = ensureSession(tab)
        browserView.setSession(session)
        session.setActive(true)
        session.setFocused(true)
        tab.lastAccess = System.nanoTime()
        addressBar.setText(url)
        tabLabel.text = if (isPrivate) "Private tab · ${tabs.size} total" else "Tab ${tabs.size}"
        if (!isPrivate) persistTabs()
        if (url == HOME_URL) showHomeScreen() else navigate(url)
        enforceSessionBudget()
    }

    private fun ensureSession(tab: BrowserTab): GeckoSession {
        tab.session?.let { return it }
        val session = GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(tab.isPrivate).build())
        session.open(runtime)
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { tab.canGoBack = canGoBack }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) { tab.canGoForward = canGoForward }
            override fun onLocationChange(session: GeckoSession, url: String?, perms: List<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                if (url.isNullOrBlank()) return
                tab.lastUrl = url
                tab.title = Uri.parse(url).host ?: url.take(48)
                if (!tab.isPrivate) { addHistory(url); persistTabs() }
                if (currentTab() === tab) {
                    addressBar.setText(url)
                    pageStatus.text = if (tab.isPrivate) "Private session" else "Secure browsing session"
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
        tabLabel.text = (if (tab.isPrivate) "Private tab" else "Tab") + " · ${index + 1}/${tabs.size}"
        pageStatus.text = if (tab.isPrivate) "Private session" else "Normal session · ${tab.group}"
        if (tab.lastUrl == HOME_URL) showHomeScreen() else showBrowserPage()
        enforceSessionBudget()
        persistTabs()
    }

    private fun chooseSplitTab() {
        val candidates = tabs.filter { !it.isPrivate && it !== currentTab() }
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
        tab.lastUrl = target
        tab.title = Uri.parse(target).host ?: target.take(48)
        ensureSession(tab).loadUri(target)
        secondaryAddressBar.setText(target)
        if (!tab.isPrivate) persistTabs()
    }

    private fun navigate(input: String) {
        val target = BrowserUrlResolver.resolve(input, searchEngine) ?: return
        val tab = currentTab() ?: return
        tab.lastUrl = target
        tab.title = Uri.parse(target).host ?: target.take(48)
        if (!tab.isPrivate) persistTabs()
        showBrowserPage()
        addressBar.setText(target)
        pageStatus.text = if (tab.isPrivate) "Private browsing · history disabled" else "Loading…"
        try {
            ensureSession(tab).loadUri(target)
            if (!tab.isPrivate) addHistory(target)
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
        actions.addView(actionButton("Save session") { saveNamedSession() })
        actions.addView(actionButton("Workspaces") { showSavedWorkspaces() })
        actions.addView(actionButton("Expand all") { collapsedGroups.clear(); tabDialog?.dismiss(); showTabs() })
        actions.addView(actionButton("Collapse all") { tabs.map { it.group }.distinct().forEach { collapsedGroups.add("NORMAL TABS::$it"); collapsedGroups.add("PRIVATE TABS::$it") }; tabDialog?.dismiss(); showTabs() })
        content.addView(actions)
        val scroll = ScrollView(this)
        val grid = GridLayout(this).apply { columnCount = 2; useDefaultMargins = true }
        val normal = tabs.indices.filter { !tabs[it].isPrivate }
        val privateIndices = tabs.indices.filter { tabs[it].isPrivate }
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
                tabs.filter { !it.isPrivate }.forEach { urls.put(JSONObject().put("url", it.lastUrl).put("group", it.group).put("title", it.title)) }
                val snapshots = prefs.getStringSet("saved_workspaces", emptySet())?.toMutableSet() ?: mutableSetOf()
                snapshots.add(JSONObject().put("name", name).put("tabs", urls).toString())
                prefs.edit().putStringSet("saved_workspaces", snapshots).apply()
                toast("Workspace saved")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun persistTabs() {
        val saved = JSONArray()
        tabs.filter { !it.isPrivate }.forEach { tab ->
            saved.put(JSONObject().put("url", tab.lastUrl).put("group", tab.group).put("title", tab.title))
        }
        prefs.edit().putString("open_normal_tabs", saved.toString()).putInt("active_normal_tab", tabs.take(activeIndex + 1).count { !it.isPrivate } - 1).apply()
    }

    private fun restoreTabs() {
        val raw = prefs.getString("open_normal_tabs", null) ?: return
        try {
            val saved = JSONArray(raw)
            for (i in 0 until saved.length()) {
                val item = saved.getJSONObject(i)
                createTab(false, item.optString("url", HOME_URL))
                tabs.last().group = item.optString("group", "Ungrouped")
                tabs.last().title = item.optString("title", "New tab")
            }
            if (tabs.isNotEmpty()) {
                val index = prefs.getInt("active_normal_tab", 0).coerceIn(tabs.indices)
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
                }
                persistTabs(); toast("Workspace restored")
            }.onFailure { toast("Could not restore workspace") }
        }.setNegativeButton("Close", null).show()
    }

    private fun closeCurrentTab() = closeTabAt(activeIndex)

    private fun toggleBookmark() {
        val tab = currentTab() ?: return
        if (tab.isPrivate) { toast("Private tabs cannot save bookmarks"); return }
        val saved = prefs.getStringSet("bookmarks", emptySet())?.toMutableSet() ?: mutableSetOf()
        val message = if (saved.remove(tab.lastUrl)) "Bookmark removed" else {
            saved.add(tab.lastUrl); "Bookmark saved"
        }
        prefs.edit().putStringSet("bookmarks", saved).apply()
        toast(message)
    }

    private fun showBookmarks() {
        val saved = prefs.getStringSet("bookmarks", emptySet())?.toList().orEmpty()
        if (saved.isEmpty()) { toast("No bookmarks yet"); return }
        AlertDialog.Builder(this).setTitle("Bookmarks")
            .setItems(saved.toTypedArray()) { _, which -> navigate(saved[which]) }
            .setNegativeButton("Close", null).show()
    }

    private fun addHistory(url: String) {
        val history = prefs.getStringSet("history", emptySet())?.toMutableSet() ?: mutableSetOf()
        history.remove(url)
        history.add(url)
        prefs.edit().putStringSet("history", history.toList().takeLast(300).toSet()).apply()
    }

    private fun showHistory() {
        if (currentTab()?.isPrivate == true) { toast("History is unavailable in a private tab"); return }
        val history = prefs.getStringSet("history", emptySet())?.toList()?.reversed().orEmpty()
        if (history.isEmpty()) { toast("No history yet"); return }
        AlertDialog.Builder(this).setTitle("History")
            .setItems(history.toTypedArray()) { _, which -> navigate(history[which]) }
            .setNeutralButton("Clear history") { _, _ ->
                prefs.edit().remove("history").apply(); toast("History cleared")
            }.show()
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
        val items = arrayOf("Choose search engine", "Toggle home shortcuts (currently $shortcutState)", "Clear browsing history", "Close private tabs", "About Nova")
        AlertDialog.Builder(this).setTitle("Nova Settings").setItems(items) { _, which ->
            when (which) {
                0 -> chooseSearchEngine()
                1 -> {
                    val show = !prefs.getBoolean("show_home_shortcuts", true)
                    prefs.edit().putBoolean("show_home_shortcuts", show).apply()
                    homeShortcuts.visibility = if (show) View.VISIBLE else View.GONE
                    toast(if (show) "Home shortcuts shown" else "Home shortcuts hidden")
                }
                2 -> { prefs.edit().remove("history").apply(); toast("Browsing history cleared") }
                3 -> {
                    val privateTabs = tabs.indices.filter { tabs[it].isPrivate }.reversed()
                    privateTabs.forEach { index ->
                        val removed = tabs.removeAt(index)
                        if (removed.id == splitTabId) exitSplitScreen()
                        removed.session?.close()
                        if (index <= activeIndex) activeIndex = (activeIndex - 1).coerceAtLeast(0)
                    }
                    if (tabs.isEmpty()) createTab(false, HOME_URL) else switchTab(activeIndex)
                    toast("Private tabs closed")
                }
                4 -> AlertDialog.Builder(this).setMessage("Nova Browser · GeckoView engine")
                    .setPositiveButton("OK", null).show()
            }
        }.show()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onPause() {
        persistTabs()
        super.onPause()
    }

    override fun onBackPressed() {
        val tab = currentTab()
        if (tab?.canGoBack == true) tab.session?.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        persistTabs()
        tabs.forEach { it.session?.close() }
        tabs.clear()
        if (::runtime.isInitialized) runtime.shutdown()
        super.onDestroy()
    }

    companion object { private const val HOME_URL = "about:blank" }
}
