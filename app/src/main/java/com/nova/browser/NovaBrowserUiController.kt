package com.nova.browser

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.mozilla.geckoview.GeckoView

internal data class NovaBrowserUiActions(
    val goHome: () -> Unit,
    val submitAddressBar: () -> Unit,
    val chooseSearchEngine: () -> Unit,
    val goBack: () -> Unit,
    val goForward: () -> Unit,
    val reloadPage: () -> Unit,
    val newTab: () -> Unit,
    val showTabs: () -> Unit,
    val chooseSplitTab: () -> Unit,
    val newPrivateTab: () -> Unit,
    val newGuestTab: () -> Unit,
    val toggleBookmark: () -> Unit,
    val showBookmarks: () -> Unit,
    val showHistory: () -> Unit,
    val showDownloads: () -> Unit,
    val toggleDesktopSite: () -> Unit,
    val openReaderMode: () -> Unit,
    val translatePage: () -> Unit,
    val sharePage: () -> Unit,
    val showSettings: () -> Unit,
    val showQuickSettings: () -> Unit,
    val showShieldSettings: () -> Unit,
    val showAiAssistant: () -> Unit,
    val showExtensions: () -> Unit,
    val showPasswordVault: () -> Unit,
    val showSiteControls: () -> Unit,
    val secondaryTab: () -> BrowserTab?,
    val navigateSecondary: (String) -> Unit,
    val exitSplitScreen: () -> Unit,
    val navigate: (String) -> Unit
)

internal data class NovaBrowserUiViews(
    val root: LinearLayout,
    val addressBar: EditText,
    val searchEngineButton: Button,
    val tabLabel: TextView,
    val pageStatus: TextView,
    val browserView: GeckoView,
    val secondaryBrowserView: GeckoView,
    val secondaryAddressBar: EditText,
    val secondaryPane: LinearLayout,
    val browserPane: LinearLayout,
    val homePanel: LinearLayout,
    val homeShortcuts: LinearLayout
)

/** Builds the browser chrome and home panel; browser behavior is injected from the Activity. */
internal class NovaBrowserUiController(
    private val context: Context,
    private val dp: (Int) -> Int,
    private val makeButton: (String, String, () -> Unit) -> Button,
    private val searchEngine: () -> String,
    private val actions: NovaBrowserUiActions
) {
    fun build(): NovaBrowserUiViews {
        val palette = NovaThemeManager.palette(context)
        fun actionButton(label: String, description: String = label, action: () -> Unit) =
            makeButton(label, description, action)
        fun toolbarScroll(row: LinearLayout) =
            android.widget.HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                isFillViewport = false
                addView(row, android.view.ViewGroup.LayoutParams(-2, dp(48)))
            }

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(4))
            setBackgroundColor(palette.toolbar)
        }
        val addressRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val addressBar = EditText(context).apply {
            hint = "Search or enter address"
            isSingleLine = true
            textSize = 15f
            setTextColor(palette.text)
            setHintTextColor(palette.muted)
            setSelectAllOnFocus(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
            setBackgroundColor(palette.input)
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
                    actions.submitAddressBar()
                    true
                } else false
            }
        }
        val homeButton = actionButton("⌂", "Home") { actions.goHome() }.apply {
            contentDescription = "Home"
            textSize = 18f
            minWidth = 40
            minimumWidth = 40
        }
        homeButton.minimumWidth = dp(48)
        homeButton.minimumHeight = dp(48)
        addressRow.addView(homeButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        addressRow.addView(addressBar, LinearLayout.LayoutParams(0, dp(48), 1f))
        val searchEngineButton = actionButton("${searchEngine()} ▾") { actions.chooseSearchEngine() }
        searchEngineButton.contentDescription = "Choose search engine. Current: ${searchEngine()}"
        searchEngineButton.minWidth = dp(64)
        searchEngineButton.minimumWidth = dp(64)
        addressRow.addView(searchEngineButton, LinearLayout.LayoutParams(dp(72), dp(48)))
        val goButton = actionButton("Go") { actions.submitAddressBar() }
        goButton.minWidth = dp(48)
        goButton.minimumWidth = dp(48)
        addressRow.addView(goButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        val quickSettingsButton = actionButton("⋮", "Quick settings") { actions.showQuickSettings() }.apply { textSize = 22f; minWidth = dp(44); minimumWidth = dp(44) }
        addressRow.addView(quickSettingsButton, LinearLayout.LayoutParams(dp(44), dp(48)))
        top.addView(addressRow)

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controls.addView(actionButton("←", "Back") { actions.goBack() })
        controls.addView(actionButton("→", "Forward") { actions.goForward() })
        controls.addView(actionButton("↻", "Reload") { actions.reloadPage() })
        controls.addView(actionButton("＋") { actions.newTab() })
        controls.addView(actionButton("Tabs") { actions.showTabs() })
        controls.addView(actionButton("Split") { actions.chooseSplitTab() })
        controls.addView(actionButton("Private") { actions.newPrivateTab() })
        controls.addView(actionButton("Guest") { actions.newGuestTab() })
        top.addView(toolbarScroll(controls), LinearLayout.LayoutParams(-1, dp(48)))

        val tools = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tools.addView(actionButton("☆") { actions.toggleBookmark() })
        tools.addView(actionButton("Bookmarks") { actions.showBookmarks() })
        tools.addView(actionButton("History") { actions.showHistory() })
        tools.addView(actionButton("Downloads") { actions.showDownloads() })
        tools.addView(actionButton("Desktop") { actions.toggleDesktopSite() })
        tools.addView(actionButton("Reader") { actions.openReaderMode() })
        tools.addView(actionButton("Translate") { actions.translatePage() })
        tools.addView(actionButton("Share") { actions.sharePage() })
        tools.addView(actionButton("Settings") { actions.showSettings() })
        top.addView(toolbarScroll(tools), LinearLayout.LayoutParams(-1, dp(48)))

        val advancedTools = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        advancedTools.addView(actionButton("Shield") { actions.showShieldSettings() })
        advancedTools.addView(actionButton("AI") { actions.showAiAssistant() })
        advancedTools.addView(actionButton("Add-ons") { actions.showExtensions() })
        advancedTools.addView(actionButton("Passwords") { actions.showPasswordVault() })
        advancedTools.addView(actionButton("Site") { actions.showSiteControls() })
        top.addView(toolbarScroll(advancedTools), LinearLayout.LayoutParams(-1, dp(48)))

        val tabLabel = TextView(context).apply {
            text = "Nova Browser"
            textSize = 12f
            setTextColor(palette.secondary)
            setPadding(dp(4), dp(5), dp(4), dp(5))
        }
        val pageStatus = TextView(context).apply {
            text = "Ready"
            contentDescription = "Nova session status"
            textSize = 11f
            setTextColor(palette.muted)
            setPadding(dp(4), 0, dp(4), dp(4))
        }
        top.addView(tabLabel)
        top.addView(pageStatus)

        val browserView = GeckoView(context)
        val secondaryBrowserView = GeckoView(context)
        val secondaryAddressBar = EditText(context).apply {
            hint = "Second pane address"
            isSingleLine = true
            textSize = 12f
            setTextColor(palette.text)
            setHintTextColor(palette.muted)
            setBackgroundColor(palette.input)
            setPadding(dp(12), 0, dp(12), 0)
        }
        val secondaryControls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(actionButton("Back", "Secondary pane back") {
                actions.secondaryTab()?.let { if (it.canGoBack) it.session?.goBack() }
            })
            addView(actionButton("Forward", "Secondary pane forward") {
                actions.secondaryTab()?.let { if (it.canGoForward) it.session?.goForward() }
            })
            addView(actionButton("Reload", "Reload secondary pane") {
                actions.secondaryTab()?.session?.reload()
            })
            addView(secondaryAddressBar, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(actionButton("Go") { actions.navigateSecondary(secondaryAddressBar.text.toString()) })
            addView(actionButton("Close split") { actions.exitSplitScreen() })
        }
        val secondaryPane = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(secondaryControls, LinearLayout.LayoutParams(-1, -2))
            addView(secondaryBrowserView, LinearLayout.LayoutParams(-1, 0, 1f))
            visibility = View.GONE
        }
        val browserPane = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(browserView, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(secondaryPane, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val homeShortcuts = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        fun addHomeShortcut(label: String, symbol: String, url: String) {
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(4), dp(8), dp(4), dp(8))
                setOnClickListener { actions.navigate(url) }
            }
            val icon = TextView(context).apply {
                text = symbol
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(palette.text)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(palette.input)
                }
            }
            item.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)))
            item.addView(TextView(context).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(palette.secondary)
                maxLines = 1
                setPadding(0, dp(6), 0, 0)
            })
            homeShortcuts.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        addHomeShortcut("Google", "G", "https://www.google.com")
        addHomeShortcut("YouTube", "▶", "https://m.youtube.com")
        addHomeShortcut("Wikipedia", "W", "https://www.wikipedia.org")
        addHomeShortcut("GitHub", "⌘", "https://github.com")
        val homePanel = createHomePanel(homeShortcuts)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            addView(top, LinearLayout.LayoutParams(-1, -2))
            addView(homePanel, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(browserPane, LinearLayout.LayoutParams(-1, 0, 1f))
            browserPane.visibility = View.GONE
        }
        root.isFocusableInTouchMode = true
        return NovaBrowserUiViews(
            root, addressBar, searchEngineButton, tabLabel, pageStatus, browserView,
            secondaryBrowserView, secondaryAddressBar, secondaryPane, browserPane, homePanel, homeShortcuts
        )
    }

    private fun createHomePanel(homeShortcuts: LinearLayout): LinearLayout {
        val palette = NovaThemeManager.palette(context)
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(34), dp(22), dp(24))
        }
        val brand = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(24))
        }
        brand.addView(TextView(context).apply {
            text = "🐉"
            textSize = 46f
            gravity = Gravity.CENTER
        })
        brand.addView(TextView(context).apply {
            text = "NOVA"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.16f
            setTextColor(palette.text)
            gravity = Gravity.CENTER
        })
        brand.addView(TextView(context).apply {
            text = "Your browser. Your space."
            textSize = 12f
            setTextColor(palette.muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        })
        content.addView(brand)

        val search = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(palette.input)
                cornerRadius = dp(30).toFloat()
                setStroke(dp(1), palette.stroke)
            }
            setPadding(dp(16), 0, dp(14), 0)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        search.addView(TextView(context).apply {
            text = "⌕"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(palette.muted)
        }, LinearLayout.LayoutParams(dp(30), dp(52)))
        val searchInput = EditText(context).apply {
            hint = "Search or type a web address"
            textSize = 14f
            singleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(palette.text)
            setHintTextColor(palette.muted)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setPadding(dp(6), 0, dp(6), 0)
            setOnEditorActionListener { _, actionId, event ->
                val enter = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                    event.action == android.view.KeyEvent.ACTION_DOWN
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || enter) {
                    val query = text.toString().trim()
                    if (query.isNotEmpty()) actions.navigate(query)
                    true
                } else false
            }
        }
        search.addView(searchInput, LinearLayout.LayoutParams(0, dp(52), 1f))
        content.addView(search, LinearLayout.LayoutParams(-1, -2))

        val shortcutsTitle = TextView(context).apply {
            text = "SHORTCUTS"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
            setTextColor(palette.muted)
            setPadding(dp(4), dp(30), dp(4), dp(4))
        }
        content.addView(shortcutsTitle, LinearLayout.LayoutParams(-1, -2))
        content.addView(homeShortcuts, LinearLayout.LayoutParams(-1, -2))

        val discoverHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(22), dp(4), dp(10))
        }
        discoverHeader.addView(TextView(context).apply {
            text = "Discover"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.text)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        discoverHeader.addView(TextView(context).apply {
            text = "Explore the web"
            textSize = 11f
            setTextColor(palette.muted)
        })
        content.addView(discoverHeader)
        val discoverCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(palette.homeCard)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), palette.stroke)
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        discoverCard.addView(TextView(context).apply {
            text = "Make the web yours"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.text)
        })
        discoverCard.addView(TextView(context).apply {
            text = "Your Discover feed can be added here. For now, use search or your shortcuts to explore."
            textSize = 12f
            setTextColor(palette.muted)
            setPadding(0, dp(6), 0, 0)
        })
        content.addView(discoverCard, LinearLayout.LayoutParams(-1, -2))

        val scroll = android.widget.ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(content, android.widget.ScrollView.LayoutParams(-1, -2))
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }
}
