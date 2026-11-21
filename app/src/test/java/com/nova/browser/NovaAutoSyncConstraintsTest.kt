package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.work.NetworkType

class NovaAutoSyncConstraintsTest {
    @Test fun syncRequiresNetworkChargingAndBatteryNotLow() {
        val constraints = NovaAutoSync.buildConstraints()
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresCharging())
        assertTrue(constraints.requiresBatteryNotLow())
    }
}
