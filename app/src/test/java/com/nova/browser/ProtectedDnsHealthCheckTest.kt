package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedDnsHealthCheckTest {
    @Test
    fun buildsAValidDnsOverHttpsQueryForExampleCom() {
        val query = ProtectedDnsHealthCheck.buildDnsQuery()
        assertEquals(29, query.size)
        assertEquals(0x4e56, ((query[0].toInt() and 0xff) shl 8) or (query[1].toInt() and 0xff))
        assertEquals(1, (query[5].toInt() and 0xff))
        assertEquals(7, (query[12].toInt() and 0xff))
        assertEquals(3, (query[20].toInt() and 0xff))
        assertEquals(1, (query[26].toInt() and 0xff))
        assertEquals(1, (query[28].toInt() and 0xff))
    }

    @Test
    fun acceptsOnlySuccessfulDnsResponseWithAnswer() {
        val response = ByteArray(12).apply {
            this[0] = 0x4e
            this[1] = 0x56
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[7] = 1
        }
        assertTrue(ProtectedDnsHealthCheck.isValidDnsResponse(response))
        assertFalse(ProtectedDnsHealthCheck.isValidDnsResponse(ByteArray(11)))
        assertFalse(ProtectedDnsHealthCheck.isValidDnsResponse(response.copyOf().apply { this[3] = 0x83.toByte() }))
        assertFalse(ProtectedDnsHealthCheck.isValidDnsResponse(response.copyOf().apply { this[7] = 0 }))
    }
}
