package com.nova.browser

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cefrium.CefriumProfile
import com.nova.browser.engine.BrowserEngineListener
import com.nova.browser.engine.CefriumBrowserEngine
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class CefriumMajorWebsiteSmokeTest {
    @Test fun majorWebsiteEntryPointsReachAnHttpsDocumentWhenNetworkIsAvailable() {
        val loading = AtomicBoolean(false)
        val started = AtomicReference(CountDownLatch(1))
        val finished = AtomicReference(CountDownLatch(1))
        var engine: CefriumBrowserEngine? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                engine = CefriumBrowserEngine(
                    activity = activity,
                    profile = CefriumProfile.get("nova-major-sites-${UUID.randomUUID()}"),
                    listener = BrowserEngineListener { state ->
                        if (state.isLoading) {
                            loading.set(true)
                            started.get().countDown()
                        } else if (loading.get()) {
                            finished.get().countDown()
                        }
                    }
                )
                val root = activity.findViewById<FrameLayout>(android.R.id.content)
                root.addView(engine!!.view, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ))
            }

            fun loadAndProbe(url: String, optionalNetworkProbe: Boolean = false) {
                loading.set(false)
                started.set(CountDownLatch(1))
                finished.set(CountDownLatch(1))
                scenario.onActivity { engine!!.loadUrl(url) }
                val began = started.get().await(20, TimeUnit.SECONDS)
                val completed = began && finished.get().await(30, TimeUnit.SECONDS)
                if (optionalNetworkProbe) assumeTrue("Network unavailable in this emulator", completed)
                assertTrue("Cefrium did not finish loading $url", completed)
                val evaluated = CountDownLatch(1)
                var probe: String? = null
                scenario.onActivity {
                    engine!!.evaluateJavaScript(
                        "document.readyState + '|' + location.protocol + '|' + document.documentElement.tagName"
                    ) { value -> probe = value; evaluated.countDown() }
                }
                assertTrue("Cefrium JavaScript probe timed out for $url", evaluated.await(10, TimeUnit.SECONDS))
                assertTrue("Page did not expose a complete HTTPS document for $url: $probe",
                    probe?.contains("complete") == true && probe?.contains("https:") == true)
            }

            // A single neutral probe distinguishes network-disabled runners from site failures.
            loadAndProbe("https://example.com/", optionalNetworkProbe = true)
            listOf(
                "https://www.google.com/",
                "https://www.youtube.com/",
                "https://github.com/",
                "https://www.instagram.com/",
                "https://discord.com/app",
                "https://web.whatsapp.com/",
                "https://www.microsoft365.com/"
            ).forEach { loadAndProbe(it) }
            scenario.onActivity { engine?.close() }
        }
    }
}
