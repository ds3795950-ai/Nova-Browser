package com.nova.browser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PasswordVaultValidationTest {
    @Test
    fun acceptsWebOriginsAndOptionalFormRealm() {
        val fields = PasswordVaultValidation.fromJson(JSONObject()
            .put("guid", "login-1")
            .put("origin", "https://example.com")
            .put("formActionOrigin", "https://example.com")
            .put("httpRealm", "Private area")
            .put("username", "alice")
            .put("password", "secret"))
        assertEquals("https://example.com", fields.origin)
        assertEquals("https://example.com", fields.formActionOrigin)
        assertEquals("Private area", fields.httpRealm)
    }

    @Test
    fun blankOptionalFieldsBecomeNullAndMissingGuidIsGenerated() {
        val fields = PasswordVaultValidation.fromJson(JSONObject()
            .put("origin", "http://localhost:8080")
            .put("formActionOrigin", "")
            .put("httpRealm", "")
            .put("username", "")
            .put("password", ""))
        assertNull(fields.formActionOrigin)
        assertNull(fields.httpRealm)
        assertNotNull(fields.guid)
    }

    @Test
    fun rejectsNonWebOriginsAndOriginInjection() {
        listOf(
            "ftp://example.com",
            "https://user:pass@example.com",
            "https://example.com/login",
            "https://example.com?token=secret",
            "https://example.com#fragment",
            "https://example.com:70000",
            "https://"
        ).forEach { origin -> assertThrows(IllegalArgumentException::class.java) { PasswordVaultValidation.validateWebOrigin(origin) } }
    }

    @Test
    fun rejectsOversizedCredentialFields() {
        val oversized = "x".repeat(16_385)
        assertThrows(IllegalArgumentException::class.java) {
            PasswordVaultValidation.fromJson(JSONObject()
                .put("origin", "https://example.com")
                .put("username", "alice")
                .put("password", oversized))
        }
    }
}
