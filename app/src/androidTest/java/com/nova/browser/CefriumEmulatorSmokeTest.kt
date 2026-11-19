package com.nova.browser

import android.util.Base64
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
                assertEquals(BrowserEngineType.CEFRIUM, private.engine)
                assertEquals(BrowserEngineType.GECKOVIEW, guest.engine)
            }
        }
    }

    @Test fun cefriumCreatesSurfaceAndLoadsLocalPageInDesktopMode() {
        val loaded = CountDownLatch(1)
        var engine: CefriumBrowserEngine? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val profile = CefriumProfile.get("nova-emulator-smoke")
                engine = CefriumBrowserEngine(
                    activity = activity,
                    profile = profile,
                    listener = BrowserEngineListener { state ->
                        if (!state.isLoading && state.url?.startsWith("data:text/html") == true) loaded.countDown()
                    }
                )
                engine!!.setDesktopMode(true)
                val html = "<!doctype html><html><body><article><h1>Nova smoke test</h1><p>Cefrium is rendering.</p></article></body></html>"
                val encoded = Base64.encodeToString(html.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val contentRoot = activity.findViewById<FrameLayout>(android.R.id.content)
                contentRoot.addView(
                    engine!!.view,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                engine!!.loadUrl("data:text/html;base64," + encoded)
            }
            assertTrue("Cefrium did not finish loading its local smoke-test page", loaded.await(30, TimeUnit.SECONDS))
            scenario.onActivity { engine?.close() }
        }
    }
}
