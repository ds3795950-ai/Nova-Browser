package com.nova.browser

import android.app.Activity
import android.content.Context
import android.content.res.Configuration

internal enum class NovaThemeMode(val storedValue: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark")
}

internal data class NovaThemePalette(
    val background: Int,
    val toolbar: Int,
    val surface: Int,
    val input: Int,
    val raised: Int,
    val text: Int,
    val secondary: Int,
    val muted: Int,
    val stroke: Int,
    val accent: Int,
    val homeCard: Int,
    val userBubble: Int,
    val warning: Int
)

internal object NovaThemeManager {
    private const val PREFS = "nova_local"
    private const val MODE_KEY = "appearance_mode"

    fun currentMode(context: Context): NovaThemeMode =
        NovaThemeMode.values().firstOrNull {
            it.storedValue == context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(MODE_KEY, NovaThemeMode.SYSTEM.storedValue)
        } ?: NovaThemeMode.SYSTEM

    fun setMode(context: Context, mode: NovaThemeMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(MODE_KEY, mode.storedValue).apply()
    }

    fun isSystemDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun resolvesDark(mode: NovaThemeMode, systemDark: Boolean): Boolean = when (mode) {
        NovaThemeMode.SYSTEM -> systemDark
        NovaThemeMode.LIGHT -> false
        NovaThemeMode.DARK -> true
    }

    fun isDark(context: Context): Boolean =
        resolvesDark(currentMode(context), isSystemDark(context))

    /** Apply explicit light/dark mode to resource qualifiers before Activity inflation. */
    fun wrapBaseContext(base: Context): Context {
        val mode = currentMode(base)
        if (mode == NovaThemeMode.SYSTEM) return base
        val configuration = Configuration(base.resources.configuration)
        val night = if (mode == NovaThemeMode.DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(configuration)
    }

    fun applyBeforeCreate(activity: Activity) {
        activity.setTheme(if (isDark(activity)) R.style.NovaThemeDark else R.style.NovaThemeLight)
    }

    fun palette(context: Context): NovaThemePalette = if (isDark(context)) {
        NovaThemePalette(
            background = 0xFF100D15.toInt(),
            toolbar = 0xFF171020.toInt(),
            surface = 0xFF1B1922.toInt(),
            input = 0xFF30243D.toInt(),
            raised = 0xFF25222E.toInt(),
            text = 0xFFF4F0FA.toInt(),
            secondary = 0xFFE5DCEC.toInt(),
            muted = 0xFFAAA3B5.toInt(),
            stroke = 0xFF393443.toInt(),
            accent = 0xFFB99AFF.toInt(),
            homeCard = 0xFF21182B.toInt(),
            userBubble = 0xFF292334.toInt(),
            warning = 0xFFFFC66D.toInt()
        )
    } else {
        NovaThemePalette(
            background = 0xFFF6F3FA.toInt(),
            toolbar = 0xFFFFFFFF.toInt(),
            surface = 0xFFFFFFFF.toInt(),
            input = 0xFFF0EAF7.toInt(),
            raised = 0xFFE9E1F1.toInt(),
            text = 0xFF241C2B.toInt(),
            secondary = 0xFF5F5668.toInt(),
            muted = 0xFF746A7E.toInt(),
            stroke = 0xFFD7CEDF.toInt(),
            accent = 0xFF7044C8.toInt(),
            homeCard = 0xFFFFFFFF.toInt(),
            userBubble = 0xFFEDE5F7.toInt(),
            warning = 0xFF8A5800.toInt()
        )
    }
}
