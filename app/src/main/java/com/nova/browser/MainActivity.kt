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
        val session: GeckoSession,
        val isPrivate: Boolean,
        var lastUrl: String = HOME_URL,
        var canGoBack: Boolean = false,
        var canGoForward: Boolean = false
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        searchEngine = prefs.getString("search_engine", "Google") ?: "Google"
        runtime = GeckoRuntime.create(this)
        buildBrowserUi()
        createTab(false, HOME_URL)
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
            singleLine = true
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
        homePanel = createHomePanel()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF100D15.toInt())
            addView(top, LinearLayout.LayoutParams(-1, -2))
            addView(homePanel, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(browserView, LinearLayout.LayoutParams(-1, 0, 1f))
            browserView.visibility = View.GONE
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
        browserView.visibility = View.GONE
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
        browserView.visibility = View.VISIBLE
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
        val settings = GeckoSessionSettings.Builder().usePrivateMode(isPrivate).build()
        val session = GeckoSession(settings)
        session.open(runtime)
        val tab = BrowserTab(session, isPrivate, url)
        tabs.add(tab)
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                tab.canGoBack = canGoBack
            }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                tab.canGoForward = canGoForward
            }
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                if (url.isNullOrBlank()) return
                tab.lastUrl = url
                if (!tab.isPrivate) addHistory(url)
                if (currentTab() === tab) {
                    addressBar.setText(url)
                    pageStatus.text = if (tab.isPrivate) "Private session" else "Secure browsing session"
                }
            }
        }
        activeIndex = tabs.lastIndex
        browserView.setSession(session)
        session.setActive(true)
        session.setFocused(true)
        addressBar.setText(url)
        tabLabel.text = if (isPrivate) "Private tab · ${tabs.size} total" else "Tab ${tabs.size}"
        if (url == HOME_URL) showHomeScreen() else navigate(url)
    }

    private fun currentTab(): BrowserTab? = tabs.getOrNull(activeIndex)

    private fun switchTab(index: Int) {
        if (index !in tabs.indices) return
        currentTab()?.session?.setActive(false)
        activeIndex = index
        val tab = tabs[index]
        browserView.setSession(tab.session)
        tab.session.setActive(true)
        tab.session.setFocused(true)
        addressBar.setText(tab.lastUrl)
        tabLabel.text = (if (tab.isPrivate) "Private tab" else "Tab") + " · ${index + 1}/${tabs.size}"
        pageStatus.text = if (tab.isPrivate) "Private session" else "Normal session"
        if (tab.lastUrl == HOME_URL) showHomeScreen() else showBrowserPage()
    }

    private fun navigate(input: String) {
        val target = BrowserUrlResolver.resolve(input, searchEngine) ?: return
        val tab = currentTab() ?: return
        tab.lastUrl = target
        showBrowserPage()
        addressBar.setText(target)
        pageStatus.text = if (tab.isPrivate) "Private browsing · history disabled" else "Loading…"
        try {
            tab.session.loadUri(target)
            if (!tab.isPrivate) addHistory(target)
        } catch (error: Exception) {
            pageStatus.text = "Navigation error"
            toast("Could not open page: ${error.localizedMessage}")
        }
    }

    private fun goBack() {
        val tab = currentTab() ?: return
        if (tab.canGoBack) tab.session.goBack() else if (tab.lastUrl == HOME_URL) toast("Already on the home screen") else toast("No previous page")
    }

    private fun goForward() {
        val tab = currentTab() ?: return
        if (tab.canGoForward) tab.session.goForward() else toast("No next page")
    }

    private fun reloadPage() { currentTab()?.session?.reload() }

    private fun showTabs() {
        if (tabs.isEmpty()) return
        val labels = tabs.mapIndexed { index, tab ->
            "${index + 1}. ${if (tab.isPrivate) "Private" else "Normal"} — ${tab.lastUrl}"
        }.toMutableList()
        labels.add("＋ New normal tab")
        labels.add("＋ New private tab")
        AlertDialog.Builder(this).setTitle("Open tabs")
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    tabs.size -> createTab(false, HOME_URL)
                    tabs.size + 1 -> createTab(true, HOME_URL)
                    else -> switchTab(which)
                }
            }.setNeutralButton("Close current") { _, _ -> closeCurrentTab() }.show()
    }

    private fun closeCurrentTab() {
        if (tabs.isEmpty()) return
        val removed = tabs.removeAt(activeIndex)
        removed.session.close()
        if (tabs.isEmpty()) createTab(false, HOME_URL)
        else switchTab((activeIndex - 1).coerceAtLeast(0))
    }

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
        prefs.edit().putStringSet("history", history.takeLast(300).toSet()).apply()
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
                        tabs.removeAt(index).session.close()
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

    override fun onBackPressed() {
        val tab = currentTab()
        if (tab?.canGoBack == true) tab.session.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        tabs.forEach { it.session.close() }
        tabs.clear()
        if (::runtime.isInitialized) runtime.shutdown()
        super.onDestroy()
    }

    companion object { private const val HOME_URL = "about:blank" }
}
