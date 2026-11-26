package com.nova.browser

import org.json.JSONArray

internal object NovaAiInputPolicy {
    private val modelIdentifier = Regex("[A-Za-z0-9._:-]{1,159}")

    fun isSafeModelIdentifier(value: String): Boolean = modelIdentifier.matches(value)

    fun limitPageContext(value: String): String = value.take(24_000)

    fun serializedHistoryBytes(messages: List<NovaAiMessage>): Int = JSONArray().apply {
        messages.forEach { message ->
            put(org.json.JSONObject().put("role", message.role).put("content", message.text))
        }
    }.toString().toByteArray(Charsets.UTF_8).size

    /** Keep the newest complete turns while reserving space for provider-specific request metadata. */
    fun boundedHistory(
        history: List<NovaAiMessage>,
        maxRequestBytes: Int = 512 * 1024,
        reserveBytes: Int = 64 * 1024
    ): List<NovaAiMessage> {
        val recent = history.takeLast(20).map { it.copy(text = it.text.take(32_000)) }.toMutableList()
        // The count cap can begin on an assistant answer whose user prompt was dropped.
        while (recent.firstOrNull()?.role != "user" && recent.isNotEmpty()) recent.removeAt(0)
        val budget = (maxRequestBytes - reserveBytes).coerceAtLeast(1024)
        while (recent.size > 1 && serializedHistoryBytes(recent) > budget) {
            val nextUser = (1 until recent.size).firstOrNull { recent[it].role == "user" }
            val removeCount = nextUser ?: 1
            repeat(removeCount.coerceAtMost(recent.size)) { recent.removeAt(0) }
        }
        require(serializedHistoryBytes(recent) <= budget) { "The latest AI message exceeds the request size limit" }
        return recent
    }
}
