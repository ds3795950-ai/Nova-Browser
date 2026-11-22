package com.nova.browser.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CefriumSitePermissionPolicyTest {
    @Test fun allowsKnownPermissionsFromHttpsOrigins() {
        assertEquals(
            listOf("CAMERA", "MICROPHONE"),
            CefriumSitePermissionPolicy.normalize("https://camera.example", listOf("CefriumPermission.CAMERA", "MICROPHONE"))
        )
    }

    @Test fun allowsLocalhostDevelopmentOrigins() {
        assertEquals(
            listOf("GEOLOCATION"),
            CefriumSitePermissionPolicy.normalize("http://localhost:8080", listOf("GEOLOCATION"))
        )
    }

    @Test fun rejectsInsecureRemoteOrigins() {
        assertNull(CefriumSitePermissionPolicy.normalize("http://example.com", listOf("CAMERA")))
    }

    @Test fun rejectsOpaqueMalformedAndCredentialBearingOrigins() {
        assertNull(CefriumSitePermissionPolicy.normalize("data:text/html,hello", listOf("CAMERA")))
        assertNull(CefriumSitePermissionPolicy.normalize("https://user:pass@example.com", listOf("CAMERA")))
        assertNull(CefriumSitePermissionPolicy.normalize("not a url", listOf("CAMERA")))
    }

    @Test fun rejectsUnknownOrEmptyPermissionRequests() {
        assertNull(CefriumSitePermissionPolicy.normalize("https://example.com", listOf("CAMERA", "UNKNOWN")))
        assertNull(CefriumSitePermissionPolicy.normalize("https://example.com", emptyList()))
    }

    @Test fun normalizesAndDeduplicatesNames() {
        assertEquals(
            listOf("NFC", "CLIPBOARD_READ"),
            CefriumSitePermissionPolicy.normalize("https://example.com", listOf("nfc", "NFC", "CefriumPermission.CLIPBOARD_READ"))
        )
    }
}
