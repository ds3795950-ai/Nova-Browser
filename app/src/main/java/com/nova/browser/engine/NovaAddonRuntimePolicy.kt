package com.nova.browser.engine

/**
 * Describes what an add-on package asks Nova to execute. This is deliberately separate
 * from browser-engine capabilities: an engine advertising extensions does not imply that
 * it implements every WebExtensions API.
 */
enum class NovaAddonPackageKind {
    WEB_EXTENSION,
    NOVA_SCRIPT
}

enum class NovaAddonFeature {
    DOCUMENT_START_CONTENT_SCRIPT,
    BACKGROUND_WORKER,
    EXTENSION_STORAGE,
    TAB_CONTROL,
    DYNAMIC_SCRIPTING,
    REQUEST_INTERCEPTION,
    COOKIE_ACCESS,
    NATIVE_MESSAGING
}

enum class NovaAddonRuntimeMode {
    GECKOVIEW_WEB_EXTENSION,
    CEFRIUM_DOCUMENT_SCRIPT,
    UNSUPPORTED
}

data class NovaAddonRequest(
    val packageKind: NovaAddonPackageKind,
    val requestedFeatures: Set<NovaAddonFeature> = emptySet()
)

data class NovaAddonCompatibility(
    val supported: Boolean,
    val runtimeMode: NovaAddonRuntimeMode,
    val targetEngine: BrowserEngineType?,
    val requiresEngineSwitch: Boolean,
    val requiresNativeInstallValidation: Boolean,
    val unsupportedFeatures: Set<NovaAddonFeature>,
    val explanation: String
)

/**
 * Honest routing policy for the two add-on mechanisms currently shipped by Nova.
 *
 * GeckoView delegates WebExtension manifest/API validation to its native WebExtension
 * runtime. Cefrium currently supports only Nova's origin-scoped document-start scripts;
 * it does not expose a general WebExtensions API implementation.
 */
object NovaAddonRuntimePolicy {
    private val cefriumScriptFeatures = setOf(NovaAddonFeature.DOCUMENT_START_CONTENT_SCRIPT)

    fun assess(
        currentEngine: BrowserEngineType,
        profile: BrowserProfileKind,
        request: NovaAddonRequest
    ): NovaAddonCompatibility {
        val ephemeral = profile != BrowserProfileKind.NORMAL

        if (request.packageKind == NovaAddonPackageKind.WEB_EXTENSION) {
            if (currentEngine == BrowserEngineType.GECKOVIEW) {
                return NovaAddonCompatibility(
                    supported = true,
                    runtimeMode = NovaAddonRuntimeMode.GECKOVIEW_WEB_EXTENSION,
                    targetEngine = BrowserEngineType.GECKOVIEW,
                    requiresEngineSwitch = false,
                    requiresNativeInstallValidation = true,
                    unsupportedFeatures = emptySet(),
                    explanation = "GeckoView must validate the manifest and each requested API during native installation."
                )
            }
            return if (ephemeral || currentEngine == BrowserEngineType.CEFRIUM) {
                NovaAddonCompatibility(
                    supported = true,
                    runtimeMode = NovaAddonRuntimeMode.GECKOVIEW_WEB_EXTENSION,
                    targetEngine = BrowserEngineType.GECKOVIEW,
                    requiresEngineSwitch = true,
                    requiresNativeInstallValidation = true,
                    unsupportedFeatures = emptySet(),
                    explanation = if (ephemeral) {
                        "Private and Guest profiles use GeckoView; the WebExtension must be installed and validated there."
                    } else {
                        "This WebExtension requires GeckoView's native runtime. Site data and login state are not transferred by this decision."
                    }
                )
            } else {
                unsupported("No compatible WebExtension runtime is available.")
            }
        }

        if (request.packageKind == NovaAddonPackageKind.NOVA_SCRIPT) {
            if (currentEngine != BrowserEngineType.CEFRIUM || ephemeral) {
                return NovaAddonCompatibility(
                    supported = false,
                    runtimeMode = NovaAddonRuntimeMode.UNSUPPORTED,
                    targetEngine = null,
                    requiresEngineSwitch = false,
                    requiresNativeInstallValidation = false,
                    unsupportedFeatures = request.requestedFeatures,
                    explanation = "Nova Script Add-ons are limited to Normal Cefrium tabs. Private and Guest profiles do not run this persistent script store."
                )
            }
            val unsupported = request.requestedFeatures - cefriumScriptFeatures
            return NovaAddonCompatibility(
                supported = unsupported.isEmpty(),
                runtimeMode = if (unsupported.isEmpty()) NovaAddonRuntimeMode.CEFRIUM_DOCUMENT_SCRIPT else NovaAddonRuntimeMode.UNSUPPORTED,
                targetEngine = if (unsupported.isEmpty()) BrowserEngineType.CEFRIUM else null,
                requiresEngineSwitch = false,
                requiresNativeInstallValidation = false,
                unsupportedFeatures = unsupported,
                explanation = if (unsupported.isEmpty()) {
                    "Runs as an origin-scoped document-start script. No background, tabs, storage, request, cookie, or native-messaging APIs are provided."
                } else {
                    "Cefrium's Nova Script runtime cannot provide the requested WebExtensions APIs: " +
                        unsupported.joinToString().lowercase().replace('_', ' ') + "."
                }
            )
        }

        return unsupported("Unknown add-on package type.")
    }

    private fun unsupported(message: String) = NovaAddonCompatibility(
        supported = false,
        runtimeMode = NovaAddonRuntimeMode.UNSUPPORTED,
        targetEngine = null,
        requiresEngineSwitch = false,
        requiresNativeInstallValidation = false,
        unsupportedFeatures = emptySet(),
        explanation = message
    )
}
