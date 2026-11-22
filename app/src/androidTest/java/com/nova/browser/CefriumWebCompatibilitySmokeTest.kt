package com.nova.browser

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cefrium.CefriumProfile
import com.nova.browser.engine.BrowserEngineListener
import com.nova.browser.engine.CefriumBrowserEngine
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CefriumWebCompatibilitySmokeTest {
    @Test fun cefriumRunsModernDomFormsHistoryCookiesAndStorage() {
        val pageLoaded = CountDownLatch(1)
        val scriptFinished = CountDownLatch(1)
        var scriptResult: String? = null
        var engine: CefriumBrowserEngine? = null
        val html = """<!doctype html>
            <html><head><meta charset="utf-8"><title>Nova Compatibility Fixture</title></head>
            <body>
              <form id="login"><input id="username" name="username"><button type="submit">Submit</button></form>
              <output id="result"></output>
              <script>
                document.getElementById('login').addEventListener('submit', event => {
                  event.preventDefault();
                  document.getElementById('result').textContent = 'submitted:' + document.getElementById('username').value;
                });
              </script>
            </body></html>""".trimIndent()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                engine = CefriumBrowserEngine(
                    activity = activity,
                    profile = CefriumProfile.get("nova-compat-${UUID.randomUUID()}"),
                    listener = BrowserEngineListener { state ->
                        if (!state.isLoading && state.url?.contains("compat.nova.test") == true) pageLoaded.countDown()
                    }
                )
                val root = activity.findViewById<FrameLayout>(android.R.id.content)
                root.addView(engine!!.view, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ))
                engine!!.loadHtmlWithBaseUrl(html, "https://compat.nova.test/")
            }
            assertTrue("Cefrium did not load the local compatibility fixture", pageLoaded.await(30, TimeUnit.SECONDS))
            scenario.onActivity {
                val script = """(() => {
                    const input = document.getElementById('username');
                    input.value = 'nova-user';
                    document.getElementById('login').dispatchEvent(new Event('submit', {bubbles:true, cancelable:true}));
                    localStorage.setItem('nova-compat', 'stored-value');
                    document.cookie = 'nova_compat=1; path=/';
                    history.pushState({nova:true}, '', '/compat-route');
                    return JSON.stringify({ready:document.readyState, title:document.title,
                      form:document.getElementById('result').textContent,
                      storage:localStorage.getItem('nova-compat'), cookie:document.cookie,
                      path:location.pathname});
                  })()""".trimIndent()
                engine!!.evaluateJavaScript(script) { value ->
                    scriptResult = value
                    scriptFinished.countDown()
                }
            }
            assertTrue("Cefrium did not execute the compatibility script", scriptFinished.await(15, TimeUnit.SECONDS))
            val result = scriptResult
            assertNotNull(result)
            assertTrue("DOM/form event handling failed: $result", result!!.contains("submitted:nova-user"))
            assertTrue("localStorage failed: $result", result.contains("stored-value"))
            assertTrue("cookie handling failed: $result", result.contains("nova_compat=1"))
            assertTrue("History API failed: $result", result.contains("/compat-route"))
            assertTrue("document did not finish loading: $result", result.contains("complete"))
            scenario.onActivity { engine?.close() }
        }
    }
}
