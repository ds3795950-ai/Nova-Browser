package com.nova.browser.engine

import org.junit.Assert.*
import org.junit.Test

class CefriumPopupPolicyTest {
    @Test fun allowsUserInitiatedHttpAndHttpsAccountPopups() {
        assertTrue(CefriumPopupPolicy.isSafeUserInitiatedRequest("https://accounts.example/login", true))
        assertTrue(CefriumPopupPolicy.isSafeUserInitiatedRequest("http://legacy.example/login", true))
        assertTrue(CefriumPopupPolicy.isSafeUserInitiatedRequest("about:blank", true))
        assertTrue(CefriumPopupPolicy.isSafeUserInitiatedRequest("", true))
    }

    @Test fun blocksUnsolicitedAndNonWebPopupSchemes() {
        assertFalse(CefriumPopupPolicy.isSafeUserInitiatedRequest("https://accounts.example/login", false))
        assertFalse(CefriumPopupPolicy.isSafeUserInitiatedRequest("javascript:alert(1)", true))
        assertFalse(CefriumPopupPolicy.isSafeUserInitiatedRequest("file:///etc/passwd", true))
        assertFalse(CefriumPopupPolicy.isSafeUserInitiatedRequest("data:text/html,hello", true))
        assertFalse(CefriumPopupPolicy.isSafeUserInitiatedRequest("intent://open", true))
    }
}
