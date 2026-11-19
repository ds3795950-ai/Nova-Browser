package com.nova.browser

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.cefrium.CefriumProfile
import com.nova.browser.engine.BrowserEngineState
import com.nova.browser.engine.CefriumBrowserEngine

/** Debug-only smoke-test screen. It is not part of the production browser tab flow. */
class CefriumEngineProbeActivity : ComponentActivity() {
    private var engine: CefriumBrowserEngine? = null
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var back: Button
    private lateinit var forward: Button
    private lateinit var browserHost: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        val heading = TextView(this).apply {
            text = "Nova · Cefrium Engine Lab (debug only)"
            textSize = 18f
        }
        root.addView(heading, LinearLayout.LayoutParams(-1, -2))

        address = EditText(this).apply {
            hint = "Enter a website URL"
            setSingleLine(true)
            setText("https://example.com")
        }
        root.addView(address, LinearLayout.LayoutParams(-1, -2))

        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        back = Button(this).apply { text = "Back"; isEnabled = false; setOnClickListener { engine?.goBack() } }
        forward = Button(this).apply { text = "Forward"; isEnabled = false; setOnClickListener { engine?.goForward() } }
        controls.addView(back, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(forward, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(Button(this).apply { text = "Reload"; setOnClickListener { engine?.reload() } }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(Button(this).apply { text = "Go"; setOnClickListener { engine?.loadUrl(normalize(address.text.toString())) } }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(controls, LinearLayout.LayoutParams(-1, -2))

        status = TextView(this).apply { text = "Starting Cefrium…"; textSize = 12f }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        browserHost = FrameLayout(this)
        root.addView(browserHost, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        try {
            val isolatedProbeProfile = CefriumProfile.get("nova-engine-probe")
            val created = CefriumBrowserEngine(this, isolatedProbeProfile, listener = { state ->
                runOnUiThread { renderState(state) }
            })
            engine = created
            browserHost.addView(created.view, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            created.setActive(active = true, focused = true)
            created.loadUrl(address.text.toString())
        } catch (error: Throwable) {
            status.text = "Cefrium failed to initialize: " + (error.message ?: error.javaClass.simpleName)
        }
    }

    override fun onResume() {
        super.onResume()
        engine?.onHostResume()
    }

    override fun onPause() {
        engine?.onHostPause()
        super.onPause()
    }

    override fun onDestroy() {
        engine?.close()
        engine = null
        super.onDestroy()
    }

    private fun renderState(state: BrowserEngineState) {
        status.text = buildString {
            append("Cefrium · ")
            append(if (state.isLoading) "Loading " + state.progress + "%" else "Ready")
            append("\n")
            append(state.title ?: state.url ?: "No page loaded")
            if (!state.error.isNullOrBlank()) append("\n").append(state.error)
        }
        back.isEnabled = state.canGoBack
        forward.isEnabled = state.canGoForward
    }

    private fun normalize(raw: String): String {
        val value = raw.trim()
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        return "https://" + value
    }
}
