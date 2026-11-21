package com.nova.browser

/** Assigns isolated Gecko storage contexts to private tabs and a shared context to guest tabs. */
internal object SessionContextPolicy {
    fun contextId(tabId: String, isPrivate: Boolean, isGuest: Boolean, guestContextId: String?): String? = when {
        isGuest -> guestContextId?.takeIf(String::isNotBlank)
        isPrivate -> "nova-private-$tabId"
        else -> null
    }
}
