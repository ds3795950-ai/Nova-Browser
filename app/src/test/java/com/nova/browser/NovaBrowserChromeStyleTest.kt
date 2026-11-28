package com.nova.browser

import com.nova.browser.engine.BrowserEngineType
import com.nova.browser.engine.BrowserEngineMode
import org.junit.Assert.assertEquals
import org.junit.Test

class NovaBrowserChromeStyleTest {
    @Test fun bothRenderingEnginesUseIdenticalNovaChromeMetrics() {
        assertEquals(
            NovaBrowserChromeStyle.metricsFor(BrowserEngineType.CEFRIUM),
            NovaBrowserChromeStyle.metricsFor(BrowserEngineType.GECKOVIEW)
        )
    }

    @Test fun sharedChromeKeepsConsistentTouchAndAddressSizing() {
        val metrics = NovaBrowserChromeStyle.sharedMetrics
        assertEquals(48, metrics.addressHeightDp)
        assertEquals(48, metrics.controlMinSizeDp)
        assertEquals(48, metrics.toolbarMinHeightDp)
        assertEquals(15f, metrics.addressTextSizeSp, 0f)
    }

    @Test fun engineChoicesUseNovaProductLanguage() {
        assertEquals("Nova Core", NovaEnginePresentation.label(BrowserEngineType.CEFRIUM))
        assertEquals("Nova Compatibility", NovaEnginePresentation.label(BrowserEngineType.GECKOVIEW))
        assertEquals("Automatic · Nova chooses the best fit", NovaEnginePresentation.normalModeLabel(BrowserEngineMode.AUTOMATIC))
    }

    @Test fun fallbackReasonsDoNotExposeUnderlyingEngineNames() {
        val message = NovaEnginePresentation.userFacingReason("Cefrium is unavailable; using GeckoView")
        assertEquals("Nova Core is unavailable; using Nova Compatibility", message)
    }
}
