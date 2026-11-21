package com.nova.browser

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal object CloudRetryPolicy {
    fun delaySeconds(retryAfter: String?, attempt: Int, nowEpochSeconds: Long = Instant.now().epochSecond): Long {
        val header = retryAfter?.trim().orEmpty()
        val seconds = header.toLongOrNull()
        val dateSeconds = if (seconds == null && header.isNotEmpty()) {
            runCatching {
                ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() - nowEpochSeconds
            }.getOrNull()
        } else null
        val fallback = 1L shl attempt.coerceIn(0, 5)
        return (seconds ?: dateSeconds ?: fallback).coerceIn(1L, 30L)
    }
}
