package com.nova.browser

import android.app.Application

/** Application-owned download manager survives Activity recreation without retaining an Activity. */
class NovaBrowserApplication : Application() {
    lateinit var downloads: NovaDownloadManager
        private set

    override fun onCreate() {
        super.onCreate()
        downloads = NovaDownloadManager(applicationContext)
    }

    override fun onTerminate() {
        if (::downloads.isInitialized) downloads.shutdown()
        super.onTerminate()
    }
}
