package com.nova.browser

import org.json.JSONObject
import kotlin.math.ceil

/** Pure validation and response handling for Supabase email OTP authentication. */
internal object NovaEmailOtpPolicy {
    const val RESEND_COOLDOWN_MILLIS = 60_000L

    fun normalizeCode(value: String): String {
        val code = value.trim()
        require(code.matches(Regex("[0-9]{6}"))) { "Enter the six-digit code from your email" }
        return code
    }

    fun remainingCooldownSeconds(lastSentAtMillis: Long, nowMillis: Long): Long {
        if (lastSentAtMillis <= 0L) return 0L
        val elapsed = nowMillis - lastSentAtMillis
        if (elapsed < 0L) return ceil(RESEND_COOLDOWN_MILLIS / 1000.0).toLong()
        val remaining = RESEND_COOLDOWN_MILLIS - elapsed
        return if (remaining <= 0L) 0L else ceil(remaining / 1000.0).toLong()
    }

    fun errorMessage(responseBody: String, status: Int): String {
        val fallback = "Cloud request failed ($status)"
        val json = runCatching { JSONObject(responseBody) }.getOrNull() ?: return fallback
        val candidate = sequenceOf("msg", "message", "error_description", "error")
            .mapNotNull { key -> json.optString(key).takeIf { it.isNotBlank() && it != "null" } }
            .firstOrNull() ?: return fallback
        return candidate.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim().take(180).ifBlank { fallback }
    }
}
