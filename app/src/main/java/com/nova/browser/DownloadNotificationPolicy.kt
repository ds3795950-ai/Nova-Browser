package com.nova.browser

internal data class DownloadNotificationDisplay(
    val title: String,
    val text: String,
    val progress: Int,
    val indeterminate: Boolean
)

internal object DownloadNotificationPolicy {
    fun display(activeCount: Int, fileName: String?, downloaded: Long, total: Long): DownloadNotificationDisplay {
        if (activeCount <= 0) return DownloadNotificationDisplay("Nova Browser", "No active downloads", 0, false)
        val name = fileName?.takeIf { it.isNotBlank() }?.take(80)
        val more = if (activeCount > 1) " · +${activeCount - 1} more" else ""
        if (total > 0) {
            val percent = ((downloaded.coerceIn(0L, total).toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
            return DownloadNotificationDisplay(
                name?.let { "Downloading $it" } ?: "Nova Browser downloads",
                "$percent% · ${formatBytes(downloaded)} of ${formatBytes(total)}$more",
                percent,
                false
            )
        }
        val text = if (name != null) "${formatBytes(downloaded)} downloaded · transfer size unknown$more"
            else "$activeCount downloads queued or in progress"
        return DownloadNotificationDisplay(name?.let { "Downloading $it" } ?: "Nova Browser downloads", text, 0, true)
    }

    private fun formatBytes(value: Long): String = when {
        value >= 1024L * 1024L * 1024L -> "%.1f GB".format(value / (1024.0 * 1024.0 * 1024.0))
        value >= 1024L * 1024L -> "%.1f MB".format(value / (1024.0 * 1024.0))
        value >= 1024L -> "%.1f KB".format(value / 1024.0)
        else -> "$value B"
    }
}
