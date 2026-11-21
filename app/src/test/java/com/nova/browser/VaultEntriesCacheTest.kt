package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultEntriesCacheTest {
    @Test fun decryptsOnlyOnceDuringCacheLifetime() {
        var now = 0L
        val cache = VaultEntriesCache<List<String>>(ttlNanos = 100, clock = { now })
        var loads = 0
        val first = cache.getOrLoad { loads++; listOf("login") }
        val second = cache.getOrLoad { loads++; listOf("unexpected") }
        assertEquals(listOf("login"), first)
        assertEquals(first, second)
        assertEquals(1, loads)
        now = 101L
        assertEquals(listOf("updated"), cache.getOrLoad { loads++; listOf("updated") })
        assertEquals(2, loads)
    }

    @Test fun replaceUpdatesCacheWithoutReloading() {
        val cache = VaultEntriesCache<List<String>>()
        var loads = 0
        cache.replace(listOf("saved"))
        assertEquals(listOf("saved"), cache.getOrLoad { loads++; emptyList() })
        assertEquals(0, loads)
    }

    @Test fun emptyListsAreCached() {
        val cache = VaultEntriesCache<List<String>>()
        var loads = 0
        cache.getOrLoad { loads++; emptyList() }
        cache.getOrLoad { loads++; emptyList() }
        assertTrue(loads == 1)
    }
}
