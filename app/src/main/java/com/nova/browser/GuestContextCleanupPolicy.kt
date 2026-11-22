package com.nova.browser

/**
 * Persistent retry set for ephemeral Gecko contexts. GeckoView's clearDataForSessionContext
 * API is fire-and-forget (no completion result), so IDs are retained and retried on every launch
 * instead of being removed merely because the request was issued.
 */
internal object GuestContextCleanupPolicy {
    fun queue(pending: Set<String>, contextId: String): Set<String> =
        if (contextId.isBlank()) pending.toSet() else pending + contextId
}
