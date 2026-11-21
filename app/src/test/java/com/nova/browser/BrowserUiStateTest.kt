package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserUiStateTest {
    @Test fun modeNumberingIsIndependentAcrossNormalPrivateAndGuestTabs() {
        val modes = listOf(BrowserMode.NORMAL, BrowserMode.PRIVATE, BrowserMode.NORMAL, BrowserMode.GUEST, BrowserMode.PRIVATE)
        assertEquals(2, BrowserUiState.modeOrdinal(modes, 2))
        assertEquals(2, BrowserUiState.modeOrdinal(modes, 4))
        assertEquals(1, BrowserUiState.modeOrdinal(modes, 3))
        assertEquals(2, BrowserUiState.modeCount(modes, BrowserMode.PRIVATE))
    }

    @Test fun securityCallbackStateDistinguishesSecureInsecureAndExceptions() {
        assertEquals(BrowserSecurityState.SECURE, BrowserUiState.securityState(true, false))
        assertEquals(BrowserSecurityState.NOT_SECURE, BrowserUiState.securityState(false, false))
        assertEquals(BrowserSecurityState.CERTIFICATE_WARNING, BrowserUiState.securityState(true, true))
    }

    @Test fun higherPriorityStatusCannotBeOverwrittenByLowerPriorityEvents() {
        val secure = BrowserStatusState("Secure connection", 4)
        assertEquals(secure, BrowserUiState.updateStatus(secure, "Downloads · 1 active", 1))
        assertEquals(BrowserStatusState("Page failed", 5), BrowserUiState.updateStatus(secure, "Page failed", 5))
    }

    @Test fun equalPriorityStatusCanRefreshCurrentState() {
        assertEquals(BrowserStatusState("Next page loading", 3), BrowserUiState.updateStatus(BrowserStatusState("Loading", 3), "Next page loading", 3))
    }

    @Test fun invalidTabIndexHasNoModeOrdinal() {
        assertEquals(0, BrowserUiState.modeOrdinal(listOf(BrowserMode.NORMAL), -1))
        assertEquals(0, BrowserUiState.modeOrdinal(listOf(BrowserMode.NORMAL), 2))
    }
}
