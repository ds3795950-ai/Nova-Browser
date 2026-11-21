package com.nova.browser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NovaVpnBridgeAssetTest {
    private fun asset(path: String): File {
        val candidates = listOf(File("src/main/assets/nova_1vpn/$path"), File("app/src/main/assets/nova_1vpn/$path"))
        return candidates.firstOrNull { it.isFile } ?: error("Missing bundled 1VPN asset: $path")
    }

    @Test
    fun bundledManifestMatchesTheNativeBridgeAndRequiredPermissions() {
        val manifest = JSONObject(asset("manifest.json").readText())
        val gecko = manifest.getJSONObject("browser_specific_settings").getJSONObject("gecko")
        assertEquals(NovaBrowserVpnController.EXTENSION_ID, gecko.getString("id"))
        val permissions = manifest.getJSONArray("permissions").let { array ->
            (0 until array.length()).map { array.getString(it) }.toSet()
        }
        assertTrue(permissions.contains("proxy"))
        assertTrue(permissions.contains("webRequestBlocking"))
        assertTrue(permissions.contains("nativeMessaging"))
        assertEquals("background.js", manifest.getJSONObject("background").getJSONArray("scripts").getString(0))
    }

    @Test
    fun bundledBridgeUsesOnlyTheThreeNoAccountFreeRegions() {
        val source = asset("background.js").readText()
        NovaVpnLocations.free.forEach { location ->
            assertTrue("Missing 1VPN region ${location.id}", source.contains("${location.id}: ["))
        }
        assertTrue(source.contains("FREE_CREDENTIALS"))
        assertTrue(source.contains("browser.runtime.connectNative"))
        assertTrue(source.contains("browser.proxy.onRequest"))
        assertTrue(source.contains("browser.proxy.onError"))
        assertTrue(source.contains("webRTCIPHandlingPolicy"))
    }
}
