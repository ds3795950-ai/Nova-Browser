package com.nova.browser

import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cefrium.CefriumProfile
import com.nova.browser.engine.BrowserEngineListener
import com.nova.browser.engine.BrowserEngineType
import com.nova.browser.engine.CefriumBrowserEngine
import com.nova.browser.engine.EngineSelectionPolicy
import com.nova.browser.engine.EngineSelectionRequest
import com.nova.browser.engine.BrowserProfileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CefriumEmulatorSmokeTest {
    @Test fun appLaunchesAndEnginePolicyKeepsGuestOnGecko() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity.window.decorView)
                val normal = EngineSelectionPolicy.resolve(
                    EngineSelectionRequest(profile = BrowserProfileKind.NORMAL, cefriumAvailable = true)
                )
                val private = EngineSelectionPolicy.resolve(
                    EngineSelectionRequest(profile = BrowserProfileKind.PRIVATE, cefriumAvailable = true)
                )
                val guest = EngineSelectionPolicy.resolve(
                    EngineSelectionRequest(profile = BrowserProfileKind.GUEST, cefriumAvailable = true)
                )
                assertEquals(BrowserEngineType.CEFRIUM, normal.engine)
                assertEquals(BrowserEngineType.GECKOVIEW, private.engine)
                assertEquals(BrowserEngineType.GECKOVIEW, guest.engine)
            }
        }
    }

    @Test fun cefriumCreatesSurfaceAndLoadsLocalPageInDesktopMode() {
        val pageLoaded = CountDownLatch(1)
        val titleLoaded = CountDownLatch(1)
        var engine: CefriumBrowserEngine? = null
        var sawSmokePageLoadStart = false
        var createDurationMs = 0L
        var loadDurationMs = 0L
        var pssBeforeKb = 0
        var pssAfterLoadKb = 0
        var nativeHeapAfterLoadKb = 0L
        var loadStartedAtMs = 0L
        val smokeUrlPrefix = "data:text/html;base64,"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val profile = CefriumProfile.get("nova-emulator-smoke")
                pssBeforeKb = Debug.getPss()
                val createStartedAtMs = SystemClock.elapsedRealtime()
                engine = CefriumBrowserEngine(
                    activity = activity,
                    profile = profile,
                    listener = BrowserEngineListener { state ->
                        val isSmokePage = state.url?.startsWith(smokeUrlPrefix) == true
                        if (state.isLoading && isSmokePage) {
                            sawSmokePageLoadStart = true
                        }
                        if (sawSmokePageLoadStart && !state.isLoading && isSmokePage) {
                            pageLoaded.countDown()
                        }
                        if (state.title == "Nova Reader Smoke") {
                            titleLoaded.countDown()
                        }
                    }
                )
                createDurationMs = SystemClock.elapsedRealtime() - createStartedAtMs
                engine!!.setDesktopMode(true)
                val html = """
                    <!doctype html>
                    <html>
                      <head><title>Nova Reader Smoke</title></head>
                      <body><main><h1>Nova Reader Smoke</h1><p>Cefrium desktop-mode smoke test.</p></main></body>
                    </html>
                """.trimIndent()
                val encoded = Base64.encodeToString(html.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val contentRoot = activity.findViewById<FrameLayout>(android.R.id.content)
                contentRoot.addView(
                    engine!!.view,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                loadStartedAtMs = SystemClock.elapsedRealtime()
                engine!!.loadUrl(smokeUrlPrefix + encoded)
            }
            assertTrue("Cefrium did not finish loading its local smoke-test page in desktop mode", pageLoaded.await(30, TimeUnit.SECONDS))
            loadDurationMs = SystemClock.elapsedRealtime() - loadStartedAtMs
            assertTrue("Cefrium did not publish the local smoke-test page title", titleLoaded.await(10, TimeUnit.SECONDS))
            scenario.onActivity {
                assertEquals("Nova Reader Smoke", engine?.state?.title)
                pssAfterLoadKb = Debug.getPss()
                nativeHeapAfterLoadKb = Debug.getNativeHeapAllocatedSize() / 1024L
                Log.i(
                    "NovaPerf",
                    "cefrium_smoke sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL} abi=${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()} " +
                        "create_ms=$createDurationMs load_ms=$loadDurationMs pss_before_kb=$pssBeforeKb " +
                        "pss_after_load_kb=$pssAfterLoadKb native_heap_after_load_kb=$nativeHeapAfterLoadKb"
                )
                engine?.close()
            }
        }
    }

    @Test fun cefriumReaderModeExtractsArticleFromLocalPage() {
        val pageLoaded = CountDownLatch(1)
        val readerFinished = CountDownLatch(1)
        var readerSuccess = false
        var readerTitle = ""
        var engine: CefriumBrowserEngine? = null
        val articleUrl = "file:///android_asset/nova_reader_smoke.html"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                engine = CefriumBrowserEngine(
                    activity = activity,
                    profile = CefriumProfile.get("nova-reader-smoke"),
                    listener = BrowserEngineListener { state ->
                        if (!state.isLoading && state.url == articleUrl) pageLoaded.countDown()
                    }
                )
                val contentRoot = activity.findViewById<FrameLayout>(android.R.id.content)
                contentRoot.addView(
                    engine!!.view,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                engine!!.loadUrl(articleUrl)
            }
            assertTrue("Cefrium did not load the local article", pageLoaded.await(30, TimeUnit.SECONDS))
            scenario.onActivity {
                engine!!.loadReaderView(articleUrl) { success, title ->
                    readerSuccess = success
                    readerTitle = title
                    readerFinished.countDown()
                }
            }
            assertTrue("Cefrium Reader Mode did not finish extraction", readerFinished.await(15, TimeUnit.SECONDS))
            assertTrue("Reader Mode should extract article content", readerSuccess)
            assertEquals("Nova Reader Smoke", readerTitle)
            scenario.onActivity { engine?.close() }
        }
    }
}
