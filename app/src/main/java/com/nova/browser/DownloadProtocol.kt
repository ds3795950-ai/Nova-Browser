package com.nova.browser

import java.net.URLDecoder
import java.net.URI

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

    /** Rejects non-HTTP redirects and HTTPS-to-HTTP downgrade redirects. */
    fun isRedirectAllowed(fromUrl: String, toUrl: String): Boolean {
        val fromScheme = runCatching { URI(fromUrl).scheme?.lowercase() }.getOrNull() ?: return false
        val toScheme = runCatching { URI(toUrl).scheme?.lowercase() }.getOrNull() ?: return false
        if (fromScheme !in setOf("http", "https") || toScheme !in setOf("http", "https")) return false
        return fromScheme != "https" || toScheme != "http"
    }

    fun fileNameFromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val extended = Regex("filename\\*\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)?.trim()?.trim('"', '\'')
        val standard = Regex("filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]*))", RegexOption.IGNORE_CASE)
            .find(header)?.let { it.groupValues[1].ifBlank { it.groupValues[2] }.trim().trim('"', '\'') }
        val decoded = when {
            extended != null -> {
                val encoded = extended.substringAfter("''", extended)
                runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8") }.getOrDefault(encoded)
            }
            else -> standard
        } ?: return null
        return safeFileName(decoded)
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
