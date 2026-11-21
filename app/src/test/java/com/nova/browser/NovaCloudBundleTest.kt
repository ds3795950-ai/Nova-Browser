package com.nova.browser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class NovaCloudBundleTest {
    @Test fun browserAndVaultArePackedIntoOneRevision() {
        val browser = JSONObject().put("ciphertext", "browser-encrypted")
        val vault = JSONObject().put("ciphertext", "vault-encrypted")
        val bundle = NovaCloudBundle.pack(browser, vault)
        val (restoredBrowser, restoredVault) = NovaCloudBundle.unpack(bundle, null)
        assertEquals(browser.toString(), restoredBrowser.toString())
        assertEquals(vault.toString(), restoredVault.toString())
    }

    @Test fun legacyTwoRowBackupsRemainReadable() {
        val browser = JSONObject().put("ciphertext", "legacy-browser")
        val vault = JSONObject().put("ciphertext", "legacy-vault")
        val (restoredBrowser, restoredVault) = NovaCloudBundle.unpack(browser, vault)
        assertEquals(browser.toString(), restoredBrowser.toString())
        assertEquals(vault.toString(), restoredVault.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun incompleteBundleIsRejected() {
        NovaCloudBundle.unpack(JSONObject().put("format", NovaCloudBundle.FORMAT).put("browser", JSONObject()), null)
    }
}
