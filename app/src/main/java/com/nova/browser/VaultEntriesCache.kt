package com.nova.browser

/** Short-lived synchronized cache for decrypted vault entries; callers replace or invalidate it after writes. */
internal class VaultEntriesCache<T>(
    private val ttlNanos: Long = DEFAULT_TTL_NANOS,
    private val clock: () -> Long = System::nanoTime
) {
    private var cached: T? = null
    private var cachedAtNanos: Long = 0L

    @Synchronized fun getOrLoad(loader: () -> T): T {
        val now = clock()
        val existing = cached
        if (existing != null && now - cachedAtNanos in 0 until ttlNanos) return existing
        return loader().also { cached = it; cachedAtNanos = now }
    }

    @Synchronized fun replace(value: T) { cached = value; cachedAtNanos = clock() }
    @Synchronized fun invalidate() { cached = null; cachedAtNanos = 0L }

    companion object { private const val DEFAULT_TTL_NANOS = 30_000_000_000L }
}
