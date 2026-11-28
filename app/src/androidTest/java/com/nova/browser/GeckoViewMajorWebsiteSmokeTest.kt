package com.nova.browser

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nova.browser.engine.GeckoViewBrowserEngine
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoView
import java.net.URL
import javax.net.ssl.HttpsURLConnection

@RunWith(AndroidJUnit4::class)
class GeckoViewMajorWebsiteSmokeTest {
    private fun prepareGeckoNormalTab() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("nova_local", Context.MODE_PRIVATE).edit()
            .putString("shield_mode", "STANDARD")
            .putBoolean("nova_vpn_enabled", false)
            .apply()
    }

    private fun activeGeckoEngine(activity: MainActivity): GeckoViewBrowserEngine {
        val method = MainActivity::class.java.getDeclaredMethod("currentTab").apply { isAccessible = true }
        val tab = method.invoke(activity) as? BrowserTab
            ?: throw AssertionError("MainActivity did not create an active browser tab")
        return tab.browserEngine as? GeckoViewBrowserEngine
            ?: throw AssertionError("The active Normal tab did not select GeckoView for the Shield-enabled test")
    }

    private fun hasNetworkAccess(url: String): Boolean = runCatching {
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.requestMethod = "GET"
        val code = connection.responseCode
        if (code in 200..399) connection.inputStream.use { it.read() }
        connection.disconnect()
        code in 200..399
    }.getOrDefault(false)

    private fun waitForPage(
        scenario: ActivityScenario<MainActivity>,
        engine: GeckoViewBrowserEngine,
        timeoutSeconds: Long
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutSeconds * 1000
        while (SystemClock.uptimeMillis() < deadline) {
            var loading = true
            var url: String? = null
            var error: String? = null
            scenario.onActivity {
                loading = engine.state.isLoading
                url = engine.state.url
                error = engine.state.error
            }
            if (!loading && url?.startsWith("https://") == true && error == null) return true
            SystemClock.sleep(100)
        }
        return false
    }

    @Test fun majorWebsiteEntryPointsLoadInGeckoViewWhenNetworkIsAvailable() {
        val candidates = listOf(
            "https://example.com/",
            "https://www.google.com/",
            "https://www.youtube.com/"
        )
        val reachable = candidates.filter(::hasNetworkAccess)
        assumeTrue("Fewer than two external HTTPS sites are reachable from this emulator", reachable.size >= 2)

        prepareGeckoNormalTab()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var engine: GeckoViewBrowserEngine? = null
            scenario.onActivity { activity -> engine = activeGeckoEngine(activity) }
            val activeEngine = engine ?: throw AssertionError("GeckoView engine was not created")
            reachable.forEach { url ->
                scenario.onActivity { activeEngine.loadUrl(url) }
                assertTrue(
                    "GeckoView did not finish loading $url",
                    waitForPage(scenario, activeEngine, 35)
                )
                var finalUrl: String? = null
                scenario.onActivity { finalUrl = activeEngine.state.url }
                assertTrue("GeckoView did not keep HTTPS for $url", finalUrl?.startsWith("https://") == true)
            }
        }
    }

    @Test fun engineAdapterCanDetachReattachPauseResumeAndCloseIdempotently() {
        prepareGeckoNormalTab()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val engine = activeGeckoEngine(activity)
                val secondView = GeckoView(activity)
                val root = activity.findViewById<FrameLayout>(android.R.id.content)
                root.addView(
                    secondView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )

                // Reuse the one session already owned by MainActivity instead of allocating
                // a second GeckoRuntime/GeckoSession beside the browser's active session.
                engine.detach()
                engine.attachTo(secondView)
                engine.onHostPause()
                engine.onHostResume()
                engine.setActive(false, false)
                engine.close()
                engine.close()

                assertTrue("Adapter should own the newly attached pane after reattachment", engine.view === secondView)
            }
        }
    }
}
