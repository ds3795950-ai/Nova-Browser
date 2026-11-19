package com.nova.browser

data class NovaVpnLocation(val id: String, val name: String, val country: String, val free: Boolean)

object NovaVpnLocations {
    const val DEFAULT_ID = "sgp"
    val all = listOf(
        NovaVpnLocation("sgp", "Singapore", "Singapore", true),
        NovaVpnLocation("ams", "Amsterdam", "Netherlands", true),
        NovaVpnLocation("lax", "Los Angeles", "United States", true)
    )
    fun find(id: String?): NovaVpnLocation? = all.firstOrNull { it.id == id }
}
