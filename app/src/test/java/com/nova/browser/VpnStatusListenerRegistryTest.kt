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
        class EqualOwner {
            override fun equals(other: Any?): Boolean = other is EqualOwner
            override fun hashCode(): Int = 1
        }
        val owner = EqualOwner()
        val equalLookingOwner = EqualOwner()
        var calls = 0
        registry.set(owner) {
            calls++
            registry.remove(owner)
        }
        registry.set(equalLookingOwner) { calls += 10 }

        registry.dispatch()
        registry.dispatch()

        assertEquals(21, calls)
    }
}
