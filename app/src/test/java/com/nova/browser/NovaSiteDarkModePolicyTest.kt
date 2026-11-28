package com.nova.browser

import org.junit.Assert.assertTrue
import org.junit.Test

class NovaSiteDarkModePolicyTest {
    @Test fun defaultModeRemovesTheInjectedStyle() {
        val script = NovaSiteDarkModePolicy.cssFor(NovaSiteDarkMode.DEFAULT)
        assertTrue(script.contains("nova-force-color-scheme"))
        assertTrue(script.contains("removeProperty('color-scheme')"))
    }

    @Test fun forceModesSetTheirColorScheme() {
        assertTrue(NovaSiteDarkModePolicy.cssFor(NovaSiteDarkMode.FORCE_DARK).contains("color-scheme:dark"))
        assertTrue(NovaSiteDarkModePolicy.cssFor(NovaSiteDarkMode.FORCE_LIGHT).contains("color-scheme:light"))
    }
}
