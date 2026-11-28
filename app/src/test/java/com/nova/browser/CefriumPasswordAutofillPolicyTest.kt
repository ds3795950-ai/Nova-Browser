package com.nova.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CefriumPasswordAutofillPolicyTest {
    @Test fun sameOriginAllowsSavedLoginAndSameOriginFormAction() {
        assertTrue(CefriumPasswordAutofillPolicy.isAllowed(
            "https://example.com/login", "https://example.com/", "https://example.com/session"
        ))
    }

    @Test fun differentSubdomainIsRejected() {
        assertFalse(CefriumPasswordAutofillPolicy.isAllowed(
            "https://login.example.com", "https://example.com", null
        ))
    }

    @Test fun crossOriginFormActionIsRejected() {
        assertFalse(CefriumPasswordAutofillPolicy.isAllowed(
            "https://example.com", "https://example.com", "https://collector.example.net"
        ))
    }

    @Test fun schemeDowngradeIsRejected() {
        assertFalse(CefriumPasswordAutofillPolicy.isAllowed(
            "https://example.com", "http://example.com", null
        ))
    }

    @Test fun defaultPortsAreNormalizedButNonDefaultPortsAreNot() {
        assertTrue(CefriumPasswordAutofillPolicy.isAllowed(
            "https://example.com:443/login", "https://example.com", null
        ))
        assertFalse(CefriumPasswordAutofillPolicy.isAllowed(
            "https://example.com:8443", "https://example.com", null
        ))
    }

    @Test fun credentialBearingOrNonWebOriginsAreRejected() {
        assertFalse(CefriumPasswordAutofillPolicy.isAllowed(
            "https://user:pass@example.com", "https://example.com", null
        ))
        assertFalse(CefriumPasswordAutofillPolicy.isAllowed(
            "file:///android_asset/login.html", "https://example.com", null
        ))
    }
}
