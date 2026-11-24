package com.nova.browser

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nova.browser.engine.BrowserEngineListener
import com.nova.browser.engine.GeckoViewBrowserEngine
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import java.util.concurrent.CountDownLatch
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class GeckoViewWebCompatibilitySmokeTest {
    private fun hasNetworkAccess(): Boolean = runCatching {
        val connection = URL("https://example.com/").openConnection() as HttpsURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.requestMethod = "GET"
        val code = connection.responseCode
        if (code in 200..399) connection.inputStream.use { it.read() }
        connection.disconnect()
        code in 200..399
    }.getOrDefault(false)

    @Test fun geckoViewLoadsStableHttpsOrigin() {
        assumeTrue("Network unavailable; GeckoView HTTPS smoke test skipped", hasNetworkAccess())
        val pageStarted = CountDownLatch(1)
        val pageLoaded = CountDownLatch(1)
        var pageLoadSucceeded = false
        var engine: GeckoViewBrowserEngine? = null

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    val runtimeField = MainActivity::class.java.getDeclaredField("runtime").apply {
                        isAccessible = true
                    }
                    val runtime = runtimeField.get(activity) as GeckoRuntime
                    val view = GeckoView(activity)
                    val root = activity.findViewById<FrameLayout>(android.R.id.content)
                    root.addView(view, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    ))

                    val session = GeckoSession(GeckoSessionSettings.Builder().build())
                    engine = GeckoViewBrowserEngine(session, view, BrowserEngineListener { })
                    engine!!.installDelegates { configuredSession ->
                        configuredSession.progressDelegate = object : GeckoSession.ProgressDelegate {
                            override fun onPageStart(session: GeckoSession, url: String) {
                                if (url != "about:blank") pageStarted.countDown()
                            }
                            override fun onPageStop(session: GeckoSession, success: Boolean) {
                                if (pageStarted.count == 0L) {
                                    pageLoadSucceeded = success
                                    pageLoaded.countDown()
                                }
                            }
                        }
                    }
                    session.open(runtime)
                    engine!!.attachTo(view)
                    engine!!.setActive(true, true)
                    engine!!.loadUrl("https://example.com/")
                }

                val began = pageStarted.await(20, TimeUnit.SECONDS)
                val loaded = began && pageLoaded.await(45, TimeUnit.SECONDS)
                assertTrue("GeckoView did not start the HTTPS navigation", began)
                assertTrue("GeckoView did not finish loading the HTTPS origin", loaded)
                assertTrue("GeckoView reported a failed HTTPS page load", pageLoadSucceeded)
                assertTrue("GeckoView did not preserve the HTTPS URL", engine?.state?.url?.startsWith("https://") == true)
            } finally {
                scenario.onActivity { engine?.close() }
            }
        }
    }
}
