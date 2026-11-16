package com.nova.browser

/** Pure validation and filename rules shared by the download pipeline and JVM tests. */
object DownloadProtocol {
    data class ContentRange(val start: Long, val end: Long, val total: Long?)

    fun parseContentRange(value: String?): ContentRange? {
        if (value.isNullOrBlank()) return null
        val match = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$", RegexOption.IGNORE_CASE).matchEntire(value.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
        if (end < start || (total != null && (total <= end || total <= 0))) return null
        return ContentRange(start, end, total)
    }

    fun safeFileName(value: String): String {
        val leaf = value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\p{Cntrl}]"), "_")
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim().trim('.')
            .take(120).trimEnd('.')
        return leaf.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "nova-download.bin"
    }
}
