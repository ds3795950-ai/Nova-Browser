package com.nova.browser.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CefriumRuntimeAvailabilityTest {
    @Test fun supportsPackagedArm64Abi() {
        assertTrue(CefriumRuntimeAvailability.supportsAny(listOf("arm64-v8a")))
    }

    @Test fun supportsPackaged32BitArmAbi() {
        assertTrue(CefriumRuntimeAvailability.supportsAny(listOf("armeabi-v7a")))
    }

    @Test fun supportsPackagedX64EmulatorAbi() {
        assertTrue(CefriumRuntimeAvailability.supportsAny(listOf("x86_64")))
    }

    @Test fun rejectsUnsupportedOnlyAbis() {
        assertFalse(CefriumRuntimeAvailability.supportsAny(listOf("x86", "riscv64")))
    }

    @Test fun acceptsDeviceWithAnySupportedAbi() {
        assertTrue(CefriumRuntimeAvailability.supportsAny(listOf("x86", "arm64-v8a")))
    }

    @Test fun rejectsEmptyAbiList() {
        assertFalse(CefriumRuntimeAvailability.supportsAny(emptyList()))
    }
}
