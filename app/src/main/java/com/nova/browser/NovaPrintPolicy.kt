package com.nova.browser

/** Pure rules for print / Save as PDF entry points. */
internal object NovaPrintPolicy {
    fun canRequestPrint(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        if (lower.startsWith("about:") || lower.startsWith("data:") || lower.startsWith("blob:")) return false
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    fun jobName(title: String?, url: String?): String {
        val base = title?.trim()?.takeIf { it.isNotEmpty() }
            ?: url?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Nova page"
        return base.take(80)
    }
}
