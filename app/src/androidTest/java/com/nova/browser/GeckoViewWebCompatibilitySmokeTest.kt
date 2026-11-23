package com.nova.browser

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nova.browser.engine.BrowserEngineListener
import com.nova.browser.engine.GeckoSessionDelegateInstaller
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
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class GeckoViewWebCompatibilitySmokeTest {
    // GeckoView has no public JavaScript-evaluation API, so the shared DOM fixture cannot run here
    // (it needs a WebExtension bridge). This smoke test covers page load and title reporting instead.
    @Test fun geckoViewLoadsAnHttpsPageAndReportsItsTitle() {
        val pageLoaded = CountDownLatch(1)
        val titleSeen = CountDownLatch(1)
        var pageTitle: String? = null
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
                    engine!!.installDelegates(GeckoSessionDelegateInstaller { configuredSession ->
                        configuredSession.contentDelegate = object : GeckoSession.ContentDelegate {
                            override fun onTitleChange(session: GeckoSession, title: String?) {
                                if (!title.isNullOrBlank()) {
                                    pageTitle = title
                                    titleSeen.countDown()
                                }
                            }
                        }
                        configuredSession.progressDelegate = object : GeckoSession.ProgressDelegate {
                            override fun onPageStop(session: GeckoSession, success: Boolean) {
                                if (success) pageLoaded.countDown() else pageLoaded.countDown()
                            }
                        }
                    })
                    session.open(runtime)
                    engine!!.attachTo(view)
                    engine!!.setActive(true, true)
                    engine!!.loadUrl("https://example.com/")
                }

                val loaded = pageLoaded.await(45, TimeUnit.SECONDS)
                assumeTrue("Network unavailable; GeckoView origin-backed compatibility test skipped", loaded)

                assertTrue("GeckoView did not report a page title", titleSeen.await(20, TimeUnit.SECONDS))
                assertTrue("Unexpected page title: $pageTitle", pageTitle?.contains("Example", ignoreCase = true) == true)
            } finally {
                scenario.onActivity { engine?.close() }
            }
        }
    }
}
