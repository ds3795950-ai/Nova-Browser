package com.nova.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaFireClearPolicyTest {
    @Test fun refusesToClearWhilePrivateOrGuestContextsExist() {
        assertTrue(NovaFireClearPolicy.canRun(false))
        assertFalse(NovaFireClearPolicy.canRun(true))
    }
}
