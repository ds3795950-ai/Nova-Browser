package com.nova.browser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NovaVpnBridgeAssetTest {
    private fun generatedAsset(path: String): File {
        val candidates = listOf(
            File("build/generated/assets/nova_1vpn_runtime/$path"),
            File("app/build/generated/assets/nova_1vpn_runtime/$path")
        )
        return candidates.firstOrNull { it.isFile } ?: error("Missing generated 1VPN asset: $path")
    }

    @Test
    fun generatedManifestMatchesTheNativeBridgeAndRequiredPermissions() {
        val manifest = JSONObject(generatedAsset("manifest.json").readText())
        val gecko = manifest.getJSONObject("browser_specific_settings").getJSONObject("gecko")
        assertEquals(NovaBrowserVpnController.EXTENSION_ID, gecko.getString("id"))
        val permissions = manifest.getJSONArray("permissions").let { array ->
            (0 until array.length()).map { array.getString(it) }.toSet()
        }
        assertTrue(permissions.contains("proxy"))
        assertTrue(permissions.contains("webRequestBlocking"))
        assertTrue(permissions.contains("nativeMessaging"))
        assertTrue(permissions.contains("geckoViewAddons"))
    }

    @Test
    fun generatedUpstreamBridgeIncludesProxyAuthAndGuestIndependentNativeControl() {
        val background = generatedAsset("background.bundle.js").readText()
        assertTrue(background.contains("browser.proxy.onRequest.addListener"))
        assertTrue(background.contains("chrome.webRequest.onAuthRequired.addListener"))
        assertTrue(background.contains("browser.runtime.connectNative(\"com.nova.browser.vpn\")"))
        assertTrue(background.contains("globalThis.__novaProxyReady=true"))
        assertTrue(background.contains("globalThis.__novaAuthReady=true"))
        assertTrue(background.contains("peerConnectionEnabled"))
        assertTrue(background.contains("\"sgp\""))
        assertTrue(background.contains("\"ams\""))
        assertTrue(background.contains("\"lax\""))
    }
}
