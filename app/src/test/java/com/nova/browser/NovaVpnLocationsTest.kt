package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NovaVpnLocationsTest {
    @Test fun freeCatalogueContainsOnlyTheThreeProviderFreeLocations() {
        assertEquals(listOf("sgp", "ams", "lax"), NovaVpnLocations.free.map { it.id })
    }

    @Test fun defaultLocationIsAvailableAndIndiaIsNotListedAsFree() {
        assertNotNull(NovaVpnLocations.find(NovaVpnLocations.DEFAULT_ID))
        assertNull(NovaVpnLocations.find("bom"))
        assertNull(NovaVpnLocations.find("del"))
        assertNull(NovaVpnLocations.find("blr"))
    }
}
