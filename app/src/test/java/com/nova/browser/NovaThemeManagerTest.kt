package com.nova.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaThemeManagerTest {
    @Test fun systemModeFollowsDeviceAppearance() {
        assertTrue(NovaThemeManager.resolvesDark(NovaThemeMode.SYSTEM, true))
        assertFalse(NovaThemeManager.resolvesDark(NovaThemeMode.SYSTEM, false))
    }

    @Test fun explicitLightOverridesDarkSystem() {
        assertFalse(NovaThemeManager.resolvesDark(NovaThemeMode.LIGHT, true))
    }

    @Test fun explicitDarkOverridesLightSystem() {
        assertTrue(NovaThemeManager.resolvesDark(NovaThemeMode.DARK, false))
    }
}
