package com.nova.browser.engine

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CefriumRequestPolicyInstrumentationTest {
    @Test
    fun malformedOrNonHttpImageUrlUsesGlobalDefaultAndValidOriginKeepsSiteOverride() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("nova_cefrium_policy_test", Context.MODE_PRIVATE)
        val origin = "https://example.com"
        val siteKey = "site_images_cefrium_${Uri.encode(origin)}"
        preferences.edit().clear()
            .putBoolean("site_default_cefrium_images", false)
            .putBoolean(siteKey, true)
            .commit()
        try {
            val policy = CefriumRequestPolicy(preferences)

            // Global false means images are blocked when there is no valid HTTP(S) origin.
            assertTrue(policy.imagesBlockedFor("file:///android_asset/page.html"))
            assertTrue(policy.imagesBlockedFor("data:image/png;base64,AAAA"))
            assertTrue(policy.imagesBlockedFor("not a URL"))

            // A valid exact origin retains its explicit per-site allow override.
            assertFalse(policy.imagesBlockedFor("https://example.com/article"))
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
