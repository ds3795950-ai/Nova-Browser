package com.nova.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaFirewallConfigTest {
    private val profile = """
        [Interface]
        PrivateKey = abc
        Address = 10.0.0.2/32
        DNS = 1.1.1.1

        [Peer]
        PublicKey = def
        AllowedIPs = 0.0.0.0/0
        Endpoint = vpn.example:51820
    """.trimIndent()

    @Test
    fun addsAppExclusionsAndAdGuardDnsAndRoutesDnsThroughPeer() {
        val result = NovaFirewallConfig.apply(profile, setOf("com.example.blocked"), 1)
        assertTrue(result.contains("ExcludedApplications = com.example.blocked"))
        assertTrue(result.contains("DNS = 94.140.14.14, 94.140.15.15"))
        assertTrue(result.contains("AllowedIPs = 0.0.0.0/0, 94.140.14.14/32, 94.140.15.15/32"))
        assertFalse(result.contains("DNS = 1.1.1.1"))
    }

    @Test
    fun mergesExistingExclusions() {
        val withExisting = profile.replace("DNS = 1.1.1.1", "DNS = 1.1.1.1\nExcludedApplications = com.example.old")
        val result = NovaFirewallConfig.apply(withExisting, setOf("com.example.new"), 0)
        assertTrue(result.contains("ExcludedApplications = com.example.new, com.example.old"))
        assertTrue(result.contains("DNS = 1.1.1.1"))
    }

    @Test
    fun addsDnsRouteWhenPeerHasNoAllowedIps() {
        val noRoutes = profile.replace("AllowedIPs = 0.0.0.0/0\n", "")
        val result = NovaFirewallConfig.apply(noRoutes, emptySet(), 2)
        assertTrue(result.contains("AllowedIPs = 94.140.14.15/32, 94.140.15.16/32"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsConflictingIncludedApplications() {
        val included = profile.replace("DNS = 1.1.1.1", "DNS = 1.1.1.1\nIncludedApplications = com.example.allowed")
        NovaFirewallConfig.apply(included, setOf("com.example.blocked"), 0)
    }
}
