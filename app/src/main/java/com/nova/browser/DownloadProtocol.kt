package com.nova.browser

/** Pure validation and filename rules shared by the download pipeline and JVM tests. */
object DownloadProtocol {
    private const val MAX_NAME = 120

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
            .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
            .trim().trim('.')
        // Keep a short extension when shortening so long names still open with the right app.
        val shortened = if (leaf.length <= MAX_NAME) leaf else {
            val dot = leaf.lastIndexOf('.')
            val extension = if (dot > 0 && leaf.length - dot <= 16) leaf.substring(dot) else ""
            leaf.take(MAX_NAME - extension.length) + extension
        }
        val limited = shortened.trimEnd('.')
        return limited.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "nova-download.bin"
    }
}
