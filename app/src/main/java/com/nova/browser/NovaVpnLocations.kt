package com.nova.browser

data class NovaVpnLocation(
    val id: String,
    val city: String,
    val country: String,
    val countryCode: String
)

object NovaVpnLocations {
    const val DEFAULT_ID = "sgp"
    val free: List<NovaVpnLocation> = listOf(
        NovaVpnLocation("sgp", "Singapore", "Singapore", "SG"),
        NovaVpnLocation("ams", "Amsterdam", "Netherlands", "NL"),
        NovaVpnLocation("lax", "Los Angeles", "United States", "US")
    )

    fun find(id: String?): NovaVpnLocation? = free.firstOrNull { it.id == id }
}
