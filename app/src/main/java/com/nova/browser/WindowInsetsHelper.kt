package com.nova.browser

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController

/** Pads this view by the system bars, display cutout and keyboard (edge-to-edge is enforced at targetSdk 35+). */
@Suppress("DEPRECATION")
internal fun View.applySystemBarPadding() {
    setOnApplyWindowInsetsListener { view, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            val types = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            val bars = insets.getInsets(types)
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        } else {
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        insets
    }
    requestApplyInsets()
}

/** Light icons for status/navigation bars drawn over a dark background. */
@Suppress("DEPRECATION")
internal fun Window.useLightSystemBarIcons() {
    if (Build.VERSION.SDK_INT >= 30) {
        insetsController?.setSystemBarsAppearance(
            0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        )
    } else {
        var flags = decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        if (Build.VERSION.SDK_INT >= 26) flags = flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        decorView.systemUiVisibility = flags
    }
}


/** Dark icons for status/navigation bars drawn over a light background. */
@Suppress("DEPRECATION")
internal fun Window.useDarkSystemBarIcons() {
    if (Build.VERSION.SDK_INT >= 30) {
        val appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        insetsController?.setSystemBarsAppearance(appearance, appearance)
    } else {
        var flags = decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 26) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        decorView.systemUiVisibility = flags
    }
}
