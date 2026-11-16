package com.nova.browser

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/** Minimal rebuild baseline. Advanced browser features are tracked in docs/ROADMAP.md. */
class MainActivity : Activity() {
    private lateinit var runtime: GeckoRuntime
    private lateinit var session: GeckoSession
    private lateinit var browserView: GeckoView
    private lateinit var addressBar: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        runtime = GeckoRuntime.create(this)
        session = GeckoSession()
        session.open(runtime)
        browserView = GeckoView(this).apply { setSession(session) }

        addressBar = EditText(this).apply {
            hint = "Search or enter address"
            singleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            setText("https://www.google.com")
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    navigate(addressBar.text.toString())
                    true
                } else false
            }
        }

        val goButton = Button(this).apply {
            text = "Go"
            setOnClickListener { navigate(addressBar.text.toString()) }
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 4, 8, 4)
            addView(addressBar, LinearLayout.LayoutParams(0, 52, 1f))
            addView(goButton, LinearLayout.LayoutParams(-2, 52))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(-1, -2))
            addView(browserView, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
        navigate("https://www.google.com")
    }

    private fun navigate(input: String) {
        val value = input.trim()
        if (value.isEmpty()) return
        val target = when {
            value.startsWith("https://", true) || value.startsWith("http://", true) -> value
            value.contains('.') && !value.contains(' ') -> "https://$value"
            else -> "https://www.google.com/search?q=" + android.net.Uri.encode(value)
        }
        addressBar.setText(target)
        try {
            session.loadUri(target)
        } catch (error: Exception) {
            Toast.makeText(this, "Could not open page: ${error.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        if (::session.isInitialized) session.close()
        if (::runtime.isInitialized) runtime.shutdown()
        super.onDestroy()
    }
}
