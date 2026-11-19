package com.nova.browser.engine

import org.junit.Assert.*
import org.junit.Test

class RelatedDomainAffinityPolicyTest {
    @Test fun acceptsExactHostnamesAndOrigins() {
        assertEquals("https://accounts.example.com", RelatedDomainAffinityPolicy.normalizeOrigin("accounts.example.com"))
        assertEquals("https://login.example.com:8443", RelatedDomainAffinityPolicy.normalizeOrigin("https://login.example.com:8443/"))
    }

    @Test fun normalizesCaseAndInternationalHostnames() {
        assertEquals("https://login.example.com", RelatedDomainAffinityPolicy.normalizeOrigin("HTTPS://LOGIN.EXAMPLE.COM"))
        assertEquals("https://xn--bcher-kva.example", RelatedDomainAffinityPolicy.normalizeOrigin("bücher.example"))
    }

    @Test fun rejectsWildcardsAndNonOriginUrls() {
        listOf("*.example.com", "https://example.com/path", "https://example.com?next=login", "https://user@example.com", "file:///tmp", "https://example.com#fragment", "bad host").forEach {
            assertNull(it, RelatedDomainAffinityPolicy.normalizeOrigin(it))
        }
    }

    @Test fun deduplicatesOnlyExplicitHostsWithoutAddingSiblingDomains() {
        val normalized = RelatedDomainAffinityPolicy.normalizeOrigins(listOf("login.example.com", "LOGIN.EXAMPLE.COM", "accounts.example.com"))
        assertEquals(setOf("https://login.example.com", "https://accounts.example.com"), normalized)
        assertFalse("https://www.example.com" in normalized)
    }
}
