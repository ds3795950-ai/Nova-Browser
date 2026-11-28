package com.nova.browser.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaAddonRuntimePolicyTest {
    @Test fun webExtensionOnGeckoUsesNativeManifestValidation() {
        val result = NovaAddonRuntimePolicy.assess(
            BrowserEngineType.GECKOVIEW,
            BrowserProfileKind.NORMAL,
            NovaAddonRequest(
                NovaAddonPackageKind.WEB_EXTENSION,
                setOf(NovaAddonFeature.BACKGROUND_WORKER, NovaAddonFeature.TAB_CONTROL)
            )
        )

        assertTrue(result.supported)
        assertEquals(NovaAddonRuntimeMode.GECKOVIEW_WEB_EXTENSION, result.runtimeMode)
        assertTrue(result.requiresNativeInstallValidation)
        assertFalse(result.requiresEngineSwitch)
    }

    @Test fun webExtensionOnCefriumRoutesToGecko() {
        val result = NovaAddonRuntimePolicy.assess(
            BrowserEngineType.CEFRIUM,
            BrowserProfileKind.NORMAL,
            NovaAddonRequest(NovaAddonPackageKind.WEB_EXTENSION)
        )

        assertTrue(result.supported)
        assertEquals(BrowserEngineType.GECKOVIEW, result.targetEngine)
        assertTrue(result.requiresEngineSwitch)
    }

    @Test fun cefriumScriptAllowsOnlyDocumentStartContentScripts() {
        val result = NovaAddonRuntimePolicy.assess(
            BrowserEngineType.CEFRIUM,
            BrowserProfileKind.NORMAL,
            NovaAddonRequest(
                NovaAddonPackageKind.NOVA_SCRIPT,
                setOf(NovaAddonFeature.DOCUMENT_START_CONTENT_SCRIPT)
            )
        )

        assertTrue(result.supported)
        assertEquals(NovaAddonRuntimeMode.CEFRIUM_DOCUMENT_SCRIPT, result.runtimeMode)
    }

    @Test fun cefriumScriptRejectsBackgroundAndPrivilegedApis() {
        val result = NovaAddonRuntimePolicy.assess(
            BrowserEngineType.CEFRIUM,
            BrowserProfileKind.NORMAL,
            NovaAddonRequest(
                NovaAddonPackageKind.NOVA_SCRIPT,
                setOf(
                    NovaAddonFeature.DOCUMENT_START_CONTENT_SCRIPT,
                    NovaAddonFeature.BACKGROUND_WORKER,
                    NovaAddonFeature.COOKIE_ACCESS,
                    NovaAddonFeature.REQUEST_INTERCEPTION
                )
            )
        )

        assertFalse(result.supported)
        assertEquals(
            setOf(
                NovaAddonFeature.BACKGROUND_WORKER,
                NovaAddonFeature.COOKIE_ACCESS,
                NovaAddonFeature.REQUEST_INTERCEPTION
            ),
            result.unsupportedFeatures
        )
        assertEquals(NovaAddonRuntimeMode.UNSUPPORTED, result.runtimeMode)
    }

    @Test fun privateAndGuestNeverRunPersistentCefriumScripts() {
        listOf(BrowserEngineType.GECKOVIEW, BrowserEngineType.CEFRIUM).forEach { engine ->
            listOf(BrowserProfileKind.PRIVATE, BrowserProfileKind.GUEST).forEach { profile ->
                val result = NovaAddonRuntimePolicy.assess(
                    engine,
                    profile,
                    NovaAddonRequest(
                        NovaAddonPackageKind.NOVA_SCRIPT,
                        setOf(NovaAddonFeature.DOCUMENT_START_CONTENT_SCRIPT)
                    )
                )
                assertFalse(result.supported)
                assertEquals(NovaAddonRuntimeMode.UNSUPPORTED, result.runtimeMode)
            }
        }
    }
}
