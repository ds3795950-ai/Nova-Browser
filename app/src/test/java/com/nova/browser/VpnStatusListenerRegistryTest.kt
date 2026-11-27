package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnStatusListenerRegistryTest {
    @Test fun closingOneOwnerDoesNotRemoveAnotherOwnersObserver() {
        val registry = OwnerScopedListenerRegistry()
        val browserActivity = Any()
        val settingsActivity = Any()
        var browserCalls = 0
        var settingsCalls = 0

        registry.set(browserActivity) { browserCalls++ }
        registry.set(settingsActivity) { settingsCalls++ }
        registry.remove(settingsActivity)
        registry.dispatch()

        assertEquals(1, browserCalls)
        assertEquals(0, settingsCalls)
    }

    @Test fun ownersAreComparedByIdentityAndMayUnregisterDuringDispatch() {
        val registry = OwnerScopedListenerRegistry()
        val owner = Any()
        val equalLookingOwner = Any()
        var calls = 0
        registry.set(owner) {
            calls++
            registry.remove(owner)
        }
        registry.set(equalLookingOwner) { calls += 10 }

        registry.dispatch()
        registry.dispatch()

        assertEquals(11, calls)
    }
}
