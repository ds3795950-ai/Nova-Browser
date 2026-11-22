package com.nova.browser

/** Pure queue transitions for crash-safe deletion of ephemeral Gecko session contexts. */
internal object GuestContextCleanupPolicy {
    fun queue(pending: Set<String>, contextId: String): Set<String> =
        if (contextId.isBlank()) pending.toSet() else pending + contextId

    fun complete(pending: Set<String>, contextId: String): Set<String> = pending - contextId
}
