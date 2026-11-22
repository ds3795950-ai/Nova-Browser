package com.nova.browser

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * The same deterministic DOM/form/storage/history fixture is exercised by both rendering engines.
 * The Cefrium test supplies this page directly; GeckoView navigates to a stable HTTPS origin and
 * runs the same script there so browser storage APIs have a non-opaque origin.
 */
internal object EngineWebCompatibilityFixture {
    const val BASE_URL = "https://compat.nova.test/"

    const val HTML = """<!doctype html>
        <html><head><meta charset="utf-8"><title>Nova Compatibility Fixture</title></head>
        <body><main id="nova-fixture"></main></body></html>"""

    const val SCRIPT = """(() => {
        document.title = 'Nova Compatibility Fixture';
        const root = document.getElementById('nova-fixture') || document.body;
        root.innerHTML = '<form id="login"><input id="username" name="username"><button type="submit">Submit</button></form><output id="result"></output>';
        const form = document.getElementById('login');
        form.addEventListener('submit', event => {
          event.preventDefault();
          document.getElementById('result').textContent = 'submitted:' + document.getElementById('username').value;
        });
        document.getElementById('username').value = 'nova-user';
        form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
        localStorage.setItem('nova-compat', 'local-value');
        sessionStorage.setItem('nova-compat-session', 'session-value');
        document.cookie = 'nova_compat=1; path=/; SameSite=Lax';
        history.pushState({ nova: true }, '', '/compat-route');
        return JSON.stringify({
          ready: document.readyState,
          title: document.title,
          form: document.getElementById('result').textContent,
          localStorage: localStorage.getItem('nova-compat'),
          sessionStorage: sessionStorage.getItem('nova-compat-session'),
          cookie: document.cookie,
          path: location.pathname
        });
      })()"""

    fun assertCompatibleResult(result: String?) {
        assertNotNull("Engine did not return a compatibility result", result)
        val value = result!!
        assertTrue("DOM/form event handling failed: $value", value.contains("submitted:nova-user"))
        assertTrue("localStorage failed: $value", value.contains("local-value"))
        assertTrue("sessionStorage failed: $value", value.contains("session-value"))
        assertTrue("Cookie handling failed: $value", value.contains("nova_compat=1"))
        assertTrue("History API failed: $value", value.contains("/compat-route"))
        assertTrue("Document did not finish loading: $value", value.contains("complete"))
    }
}
