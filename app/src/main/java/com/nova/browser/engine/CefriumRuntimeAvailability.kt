package com.nova.browser.engine

/**
 * Runtime ABI gate for Cefrium. Keep this list aligned with the native ABIs
 * packaged by the app's Gradle ABI splits and the pinned Cefrium SDK release.
 */
object CefriumRuntimeAvailability {
    private val supportedAbis = setOf("arm64-v8a", "x86_64")

    fun supportsAny(deviceAbis: List<String>): Boolean =
        deviceAbis.any { it in supportedAbis }
}
