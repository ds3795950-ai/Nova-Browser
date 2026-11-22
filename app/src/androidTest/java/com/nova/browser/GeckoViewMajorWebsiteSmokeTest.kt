package com.nova.browser

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import com.nova.browser.engine.BrowserEngineListener
import com.nova.browser.engine.GeckoViewBrowserEngine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class GeckoViewMajorWebsiteSmokeTest {
    @Test fun majorWebsiteEntryPointsLoadInGeckoViewWhenNetworkIsAvailable() {
        val started = AtomicReference(CountDownLatch(1))
        val finished = AtomicReference(CountDownLatch(1))
        val success = AtomicBoolean(false)
        var engine: GeckoViewBrowserEngine? = null

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val runtimeField = MainActivity::class.java.getDeclaredField("runtime").apply { isAccessible = true }
                val runtime = runtimeField.get(activity) as GeckoRuntime
                val session = GeckoSession(GeckoSessionSettings.Builder().build())
                val view = GeckoView(activity)
                val root = activity.findViewById<FrameLayout>(android.R.id.content)
                root.addView(view, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ))
                engine = GeckoViewBrowserEngine(session, view, BrowserEngineListener { })
                engine!!.installDelegates { geckoSession ->
                    geckoSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
                        override fun onLocationChange(
                            session: GeckoSession,
                            url: String?,
                            permissions: List<GeckoSession.PermissionDelegate.ContentPermission>,
                            hasUserGesture: Boolean
                        ) {
                            if (!url.isNullOrBlank()) engine?.updateState(engine!!.state.copy(url = url))
                        }
                    }
                    geckoSession.progressDelegate = object : GeckoSession.ProgressDelegate {
                        override fun onPageStart(session: GeckoSession, url: String) {
                            started.get().countDown()
                            engine?.updateState(engine!!.state.copy(url = url, isLoading = true, progress = 0, error = null))
                        }

                        override fun onPageStop(session: GeckoSession, pageSuccess: Boolean) {
                            success.set(pageSuccess)
                            engine?.updateState(engine!!.state.copy(isLoading = false, progress = 100,
                                error = if (pageSuccess) null else "GeckoView page load failed"))
                            finished.get().countDown()
                        }
                    }
                    geckoSession.open(runtime)
                }
                engine!!.attachTo(view)
                engine!!.setActive(true, true)
            }

            fun loadAndCheck(url: String, optionalNetworkProbe: Boolean = false) {
                success.set(false)
                started.set(CountDownLatch(1))
                finished.set(CountDownLatch(1))
                scenario.onActivity { engine!!.loadUrl(url) }
                val began = started.get().await(20, TimeUnit.SECONDS)
                val completed = began && finished.get().await(30, TimeUnit.SECONDS)
                if (optionalNetworkProbe) assumeTrue("Network unavailable in this emulator", completed && success.get())
                assertTrue("GeckoView did not finish loading $url", completed)
                assertTrue("GeckoView reported a failed page load for $url", success.get())
                assertTrue("GeckoView did not keep HTTPS for $url", engine?.state?.url?.startsWith("https://") == true)
            }

            try {
                loadAndCheck("https://example.com/", optionalNetworkProbe = true)
                listOf(
                    "https://www.google.com/",
                    "https://www.youtube.com/",
                    "https://github.com/",
                    "https://www.instagram.com/",
                    "https://discord.com/app",
                    "https://web.whatsapp.com/",
                    "https://www.microsoft365.com/"
                ).forEach { loadAndCheck(it) }
            } finally {
                scenario.onActivity { engine?.close() }
            }
        }
    }
}
