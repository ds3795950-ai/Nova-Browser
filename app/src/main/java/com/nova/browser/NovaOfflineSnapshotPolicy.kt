package com.nova.browser

/** Offline reading is link-first; full snapshots require engine archive support. */
internal object NovaOfflineSnapshotPolicy {
    const val MAX_READING_LIST = 200

    fun canAddLink(url: String?): Boolean {
        val safe = url?.trim().orEmpty()
        if (safe.isEmpty()) return false
        val lower = safe.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    /** Engines do not currently expose a verified common page-archive API. */
    fun snapshotSupported(engineName: String?): Boolean = false

    fun userMessageForSnapshotAttempt(engineName: String?): String =
        "Full offline page snapshots are not available for ${engineName ?: "this engine"}. The link was saved to your reading list instead."
}
