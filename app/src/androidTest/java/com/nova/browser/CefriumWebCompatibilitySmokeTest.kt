package com.nova.browser

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cefrium.CefriumProfile
import com.nova.browser.engine.BrowserEngineListener
import com.nova.browser.engine.CefriumBrowserEngine
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CefriumWebCompatibilitySmokeTest {
    @Test fun cefriumRunsSharedDomFormsHistoryCookiesAndStorageFixture() {
        val pageLoaded = CountDownLatch(1)
        val scriptFinished = CountDownLatch(1)
        var scriptResult: String? = null
        var engine: CefriumBrowserEngine? = null

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    engine = CefriumBrowserEngine(
                        activity = activity,
                        profile = CefriumProfile.get("nova-compat-${UUID.randomUUID()}"),
                        listener = BrowserEngineListener { state ->
                            if (!state.isLoading && state.url?.contains("compat.nova.test") == true) {
                                pageLoaded.countDown()
                            }
                        }
                    )
                    val root = activity.findViewById<FrameLayout>(android.R.id.content)
                    root.addView(engine!!.view, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                    engine!!.loadHtmlWithBaseUrl(EngineWebCompatibilityFixture.HTML, EngineWebCompatibilityFixture.BASE_URL)
                }
                assertTrue("Cefrium did not load the local compatibility fixture", pageLoaded.await(30, TimeUnit.SECONDS))

                scenario.onActivity {
                    engine!!.evaluateJavaScript(EngineWebCompatibilityFixture.SCRIPT) { value ->
                        scriptResult = value
                        scriptFinished.countDown()
                    }
                }
                assertTrue("Cefrium did not execute the compatibility fixture", scriptFinished.await(15, TimeUnit.SECONDS))
                EngineWebCompatibilityFixture.assertCompatibleResult(scriptResult)
            } finally {
                scenario.onActivity { engine?.close() }
            }
        }
    }
}
