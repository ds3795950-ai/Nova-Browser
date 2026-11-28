package com.nova.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPictureInPicturePolicyTest {
    @Test fun entersOnlyForActiveFullscreenPlaybackOnSupportedAndroid() {
        assertTrue(BrowserPictureInPicturePolicy.shouldEnter(26, true, true, true, false))
        assertTrue(BrowserPictureInPicturePolicy.shouldEnter(36, true, true, true, false))
        assertFalse(BrowserPictureInPicturePolicy.shouldEnter(25, true, true, true, false))
        assertFalse(BrowserPictureInPicturePolicy.shouldEnter(36, false, true, true, false))
        assertFalse(BrowserPictureInPicturePolicy.shouldEnter(36, true, false, true, false))
        assertFalse(BrowserPictureInPicturePolicy.shouldEnter(36, true, true, false, false))
        assertFalse(BrowserPictureInPicturePolicy.shouldEnter(36, true, true, true, true))
    }
}
