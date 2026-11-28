package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class PerplexitySearchEngineTest {
    @Test fun searchQueryRoutesToPerplexity() {
        assertEquals(
            "https://www.perplexity.ai/search?q=best%20browser",
            BrowserUrlResolver.resolve("best browser", "Perplexity")
        )
    }
}
