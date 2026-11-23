package com.nova.browser

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.net.URI

/** Pure validation and filename rules shared by the download pipeline and JVM tests. */
object DownloadProtocol {
    data class ContentRange(val start: Long, val end: Long, val total: Long?)


    /** Rejects non-HTTP redirects, credential-bearing targets, invalid ports and HTTPS downgrades. */
    fun isRedirectAllowed(fromUrl: String, toUrl: String): Boolean {
        val from = runCatching { URI(fromUrl) }.getOrNull() ?: return false
        val to = runCatching { URI(toUrl) }.getOrNull() ?: return false
        val fromScheme = from.scheme?.lowercase() ?: return false
        val toScheme = to.scheme?.lowercase() ?: return false
        if (fromScheme !in setOf("http", "https") || toScheme !in setOf("http", "https")) return false
        if (from.rawUserInfo != null || to.rawUserInfo != null) return false
        if (to.rawAuthority.isNullOrBlank() || to.port !in -1..65535) return false
        return fromScheme != "https" || toScheme != "http"
    }

    fun parseContentRange(value: String?): ContentRange? {
        if (value.isNullOrBlank()) return null
        val match = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$", RegexOption.IGNORE_CASE).matchEntire(value.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
        if (end < start || (total != null && (total <= end || total <= 0))) return null
        return ContentRange(start, end, total)
    }

    /** Keeps letters, combining marks and digits of any script (Hindi, Bengali, ...) and never cuts off the extension. */
    fun safeFileName(value: String): String {
        val leaf = value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\p{Cntrl}]"), "_")
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}._ %+\\-]"), "_")
            .trim().trim('.')
        val limited = limitLength(leaf, 120).trimEnd('.')
        return limited.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "nova-download.bin"
    }

    private fun limitLength(name: String, max: Int): String {
        if (name.length <= max) return name
        val dot = name.lastIndexOf('.')
        val extension = if (dot > 0 && name.length - dot <= 16) name.substring(dot) else ""
        return name.substring(0, max - extension.length) + extension
    }

    /** Prefers Content-Disposition, then the percent-decoded last path segment without query or fragment. */
    fun fileNameFromResponse(uri: String, contentDisposition: String?): String {
        parseContentDispositionName(contentDisposition)?.let { return safeFileName(it) }
        val leaf = uri.substringBefore('#').substringBefore('?').substringAfterLast('/')
        val decoded = percentDecode(leaf)
        return if (decoded.isBlank()) "" else safeFileName(decoded)
    }

    internal fun parseContentDispositionName(header: String?): String? {
        if (header.isNullOrBlank()) return null
        Regex("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(header)?.let { match ->
            val charset = runCatching { Charset.forName(match.groupValues[1].ifBlank { "UTF-8" }) }.getOrDefault(Charsets.UTF_8)
            val decoded = percentDecode(match.groupValues[2].trim().trim('"'), charset)
            if (decoded.isNotBlank()) return decoded
        }
        Regex("filename\\s*=\\s*(\"([^\"]*)\"|[^;]+)", RegexOption.IGNORE_CASE).find(header)?.let { match ->
            val value = (match.groups[2]?.value ?: match.groupValues[1]).trim().trim('"')
            if (value.isNotBlank()) return value
        }
        return null
    }

    /** Decodes %XX sequences only ('+' stays '+', malformed escapes stay literal). */
    internal fun percentDecode(value: String, charset: Charset = Charsets.UTF_8): String {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val hex = if (c == '%' && i + 2 < value.length) value.substring(i + 1, i + 3) else null
            val byte = if (hex != null && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) hex.toInt(16) else null
            if (byte != null) {
                out.write(byte)
                i += 3
            } else {
                val cp = value.codePointAt(i)
                val len = Character.charCount(cp)
                out.write(value.substring(i, i + len).toByteArray(charset))
                i += len
            }
        }
        return String(out.toByteArray(), charset)
    }
}
