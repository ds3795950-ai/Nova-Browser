package com.nova.browser

/** Small synchronized cache for decrypted vault entries; callers invalidate it after every write. */
internal class VaultEntriesCache<T> {
    private var cached: T? = null

    @Synchronized fun getOrLoad(loader: () -> T): T {
        cached?.let { return it }
        return loader().also { cached = it }
    }

    @Synchronized fun replace(value: T) { cached = value }
    @Synchronized fun invalidate() { cached = null }
}
