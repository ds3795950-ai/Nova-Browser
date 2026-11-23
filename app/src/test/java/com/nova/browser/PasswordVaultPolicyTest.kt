package com.nova.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordVaultPolicyTest {
    @Test fun acceptsHttpAndHttpsOrigins() {
        assertTrue(PasswordVaultPolicy.isAllowedOrigin("https://example.com"))
        assertTrue(PasswordVaultPolicy.isAllowedOrigin("http://example.com:8080/"))
    }
    @Test fun rejectsCredentialsPathsQueriesAndNonWebSchemes() {
        assertFalse(PasswordVaultPolicy.isAllowedOrigin("https://user:pass@example.com"))
        assertFalse(PasswordVaultPolicy.isAllowedOrigin("https://example.com/login"))
        assertFalse(PasswordVaultPolicy.isAllowedOrigin("https://example.com/?next=/"))
        assertFalse(PasswordVaultPolicy.isAllowedOrigin("javascript:alert(1)"))
        assertFalse(PasswordVaultPolicy.isAllowedOrigin("https:///missing-host"))
        assertFalse(PasswordVaultPolicy.isAllowedOrigin("https://example.com:0"))
    }
    @Test fun optionalFormOriginMayBeEmptyButMustOtherwiseBeValid() {
        assertTrue(PasswordVaultPolicy.isAllowedOptionalOrigin(null)); assertTrue(PasswordVaultPolicy.isAllowedOptionalOrigin(""))
        assertFalse(PasswordVaultPolicy.isAllowedOptionalOrigin("file:///tmp/login"))
    }
}
