package com.nova.browser.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CefriumRuntimeAvailabilityTest {
    @Test fun acceptsEveryPublishedAbiEvenWhenOtherAbisPrecedeIt() {
        assertTrue(CefriumRuntimeAvailability.supportsAny(listOf("armeabi-v7a", "arm64-v8a")))
        assertTrue(CefriumRuntimeAvailability.supportsAny(listOf("x86_64")))
    }

    @Test fun rejectsAbisWithoutCefriumNativeLibraries() {
        assertFalse(CefriumRuntimeAvailability.supportsAny(listOf("armeabi-v7a")))
        assertFalse(CefriumRuntimeAvailability.supportsAny(listOf("x86", "riscv64")))
        assertFalse(CefriumRuntimeAvailability.supportsAny(emptyList()))
    }
}
