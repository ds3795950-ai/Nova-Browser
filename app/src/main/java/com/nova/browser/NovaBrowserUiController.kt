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
            setBackgroundColor(0xFF171020.toInt())
        }
        val addressRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val addressBar = EditText(context).apply {
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
            setTextColor(0xFFD8CBE8.toInt())
            setPadding(dp(4), dp(5), dp(4), dp(5))
        }
        val pageStatus = TextView(context).apply {
            text = "Ready"
            contentDescription = "Nova session status"
            textSize = 11f
            setTextColor(0xFFB9AEC8.toInt())
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
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFFB9AEC8.toInt())
            setBackgroundColor(0xFF30243D.toInt())
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
        }
        homeShortcuts.addView(actionButton("Google") { actions.navigate("https://www.google.com") })
        homeShortcuts.addView(actionButton("YouTube") { actions.navigate("https://m.youtube.com") })
        homeShortcuts.addView(actionButton("Wikipedia") { actions.navigate("https://www.wikipedia.org") })
        homeShortcuts.addView(actionButton("GitHub") { actions.navigate("https://github.com") })
        val homePanel = createHomePanel(homeShortcuts)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF100D15.toInt())
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
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF171020.toInt(), 0xFF241532.toInt(), 0xFF100D15.toInt())
            )
        }
        val dragon = TextView(context).apply {
            text = "🐉"
            textSize = 58f
            gravity = Gravity.CENTER
            contentDescription = "Nova dragon emblem"
            setPadding(0, 0, 0, dp(8))
        }
        val title = TextView(context).apply {
            text = "NOVA"
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.18f
            setTextColor(0xFFF1E9FA.toInt())
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(context).apply {
            text = "Your browser. Your space."
            textSize = 13f
            setTextColor(0xFFB9AEC8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(24))
        }
        val shortcutTitle = TextView(context).apply {
            text = "QUICK ACCESS"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            setTextColor(0xFFD8CBE8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        panel.addView(dragon)
        panel.addView(title)
        panel.addView(subtitle)
        panel.addView(shortcutTitle)
        panel.addView(homeShortcuts)
        return panel
    }
}
