package com.nova.browser

import org.junit.Assert.assertTrue
import org.junit.Test

class NovaSiteDarkModePolicyTest {
    @Test fun defaultModeDoesNotChangeDocumentsWithoutANovaOverride() {
        val script = NovaSiteDarkModePolicy.cssFor(NovaSiteDarkMode.DEFAULT)
        assertTrue(script.contains("if(!style && raw===null) return"))
        assertTrue(script.contains("data-nova-original-color-scheme"))
        assertTrue(script.contains("getPropertyPriority('color-scheme')"))
        assertTrue(script.contains("setProperty('color-scheme',original.value,original.priority || '')"))
        assertTrue(script.contains("removeProperty('color-scheme')"))
    }

    @Test fun forcedModesPreserveTheOriginalInlineValueAndPriority() {
        val dark = NovaSiteDarkModePolicy.cssFor(NovaSiteDarkMode.FORCE_DARK)
        val light = NovaSiteDarkModePolicy.cssFor(NovaSiteDarkMode.FORCE_LIGHT)
        for (script in listOf(dark, light)) {
            assertTrue(script.contains("data-nova-original-color-scheme"))
            assertTrue(script.contains("getPropertyValue('color-scheme')"))
            assertTrue(script.contains("getPropertyPriority('color-scheme')"))
        }
        assertTrue(dark.contains("color-scheme:dark"))
        assertTrue(light.contains("color-scheme:light"))
    }
}
