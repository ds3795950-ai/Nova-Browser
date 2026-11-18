package com.nova.browser

/**
 * Applies policies to the WireGuard configuration before the single GoBackend VPN is started.
 *
 * ExcludedApplications is an Android VPN routing rule, not a packet drop by itself. Android's
 * Always-on VPN lockdown is required to prevent excluded packages from using the normal network.
 */
internal object NovaFirewallConfig {
    private const val ADGUARD_DNS = "94.140.14.14, 94.140.15.15"
    private const val ADGUARD_FAMILY_DNS = "94.140.14.15, 94.140.15.16"

    fun apply(raw: String, blockedPackages: Set<String>, dnsMode: Int): String {
        require(dnsMode in 0..2) { "Unknown DNS protection mode." }
        val lines = raw.lines()
        val interfaceIndex = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
        require(interfaceIndex >= 0) { "WireGuard configuration is missing [Interface]." }
        val endIndex = (interfaceIndex + 1 until lines.size)
            .firstOrNull { lines[it].trim().startsWith("[") } ?: lines.size
        val interfaceLines = lines.subList(interfaceIndex + 1, endIndex)
        val included = interfaceLines.any { it.trim().substringBefore("=").trim().equals("IncludedApplications", true) }
        require(blockedPackages.isEmpty() || !included) {
            "This profile uses IncludedApplications. Remove that setting from the profile before enabling Nova app-block rules."
        }

        val existingExcluded = interfaceLines
            .filter { it.trim().substringBefore("=").trim().equals("ExcludedApplications", true) }
            .flatMap { it.substringAfter("=", "").split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val mergedExcluded = (existingExcluded + blockedPackages).toSortedSet()

        val updated = mutableListOf<String>()
        updated.addAll(lines.take(interfaceIndex + 1))
        interfaceLines.filterNot { line ->
            val key = line.trim().substringBefore("=").trim()
            key.equals("ExcludedApplications", true) || (dnsMode != 0 && key.equals("DNS", true))
        }.forEach(updated::add)
        if (dnsMode != 0) {
            val dns = if (dnsMode == 1) ADGUARD_DNS else ADGUARD_FAMILY_DNS
            updated.add("DNS = $dns")
        }
        if (mergedExcluded.isNotEmpty()) {
            updated.add("ExcludedApplications = ${mergedExcluded.joinToString(", ")}")
        }
        updated.addAll(lines.drop(endIndex))
        return updated.joinToString("\n").trimEnd() + "\n"
    }
}
