package com.nova.browser

/**
 * Privacy boundary for Guest and Private tabs. These modes may use engine-managed
 * ephemeral storage, but must never read or write Nova's owner browsing records.
 */
internal object GuestModePolicy {
    fun mayPersistBrowsingData(isPrivate: Boolean, isGuest: Boolean): Boolean =
        !isPrivate && !isGuest

    fun mayReadOwnerData(isGuest: Boolean): Boolean = !isGuest

    fun requiresEphemeralStorage(isGuest: Boolean): Boolean = isGuest

    /** Runtime-level credential callbacks must stay disabled while any ephemeral tab exists. */
    fun mayUseOwnerCredentials(hasPrivateOrGuestTab: Boolean): Boolean = !hasPrivateOrGuestTab
}
