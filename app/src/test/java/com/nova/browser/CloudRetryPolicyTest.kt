package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class CloudRetryPolicyTest {
    @Test fun honorsRetryAfterSeconds() {
        assertEquals(12L, CloudRetryPolicy.delaySeconds("12", 0, 0))
    }

    @Test fun honorsRetryAfterHttpDateWithThirtySecondCap() {
        val now = Instant.parse("2026-11-21T00:00:00Z").epochSecond
        assertEquals(30L, CloudRetryPolicy.delaySeconds("Sat, 21 Nov 2026 00:01:00 GMT", 0, now))
    }

    @Test fun usesBoundedExponentialFallback() {
        assertEquals(1L, CloudRetryPolicy.delaySeconds(null, 0, 0))
        assertEquals(4L, CloudRetryPolicy.delaySeconds(null, 2, 0))
        assertEquals(30L, CloudRetryPolicy.delaySeconds(null, 10, 0))
    }
}
