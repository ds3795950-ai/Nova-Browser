package com.nova.browser

import android.app.Application
import android.content.Context
import com.cefrium.Cefrium

/**
 * Applies Cefrium startup-only network policy before its engine/content providers initialize.
 */
class NovaApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        Cefrium.setComponentUpdatesEnabled(false)
    }
}
