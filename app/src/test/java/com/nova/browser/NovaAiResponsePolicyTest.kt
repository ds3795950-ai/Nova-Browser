package com.nova.browser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class NovaAiResponsePolicyTest {
    @Test fun extractsPerplexityAssistantTextAndSourceUrls() {
        val response = JSONObject().put("output", JSONArray().put(
            JSONObject().put("type", "message").put("role", "assistant").put("content", JSONArray().put(
                JSONObject().put("type", "output_text").put("text", "Answer").put(
                    "annotations", JSONArray().put(JSONObject().put("url", "https://example.com/source"))
                )
            ))
        ))
        assertEquals(
            "Answer\n\nSources:\n- https://example.com/source",
            NovaAiResponsePolicy.extractAgentText(response)
        )
    }

    @Test fun ignoresNonAssistantMessagesAndNonHttpsSources() {
        val response = JSONObject().put("output", JSONArray().put(
            JSONObject().put("type", "message").put("role", "user").put("content", JSONArray().put(
                JSONObject().put("type", "output_text").put("text", "Do not include")
            ))
        ).put(
            JSONObject().put("type", "message").put("role", "assistant").put("content", JSONArray().put(
                JSONObject().put("type", "output_text").put("text", "Visible").put(
                    "annotations", JSONArray().put(JSONObject().put("url", "javascript:alert(1)"))
                )
            ))
        ))
        assertEquals("Visible", NovaAiResponsePolicy.extractAgentText(response))
    }

    @Test fun emptyAgentOutputReturnsEmptyText() {
        assertEquals("", NovaAiResponsePolicy.extractAgentText(JSONObject().put("output", JSONArray())))
    }
}
