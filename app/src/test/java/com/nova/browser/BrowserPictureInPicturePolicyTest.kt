package com.nova.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPictureInPicturePolicyTest {
    @Test fun automaticEntersOnlyForActiveFullscreenPlaybackOnSupportedAndroid() {
        assertTrue(BrowserPictureInPicturePolicy.shouldEnter(26, true, true, true, false))
        assertTrue(BrowserPictureInPicturePolicy.shouldEnter(36, true, true, true, false))
        assertFalse(BrowserPictureInPicturePolicy.shouldEnter(25, true, true, true, false))
        assertFalse(BrowserPictureInPicturePolicy.shouldEnter(36, false, true, true, false))
        assertFalse(BrowserPictureInPicturePolicy.shouldEnter(36, true, false, true, false))
        assertFalse(BrowserPictureInPicturePolicy.shouldEnter(36, true, true, false, false))
        assertFalse(BrowserPictureInPicturePolicy.shouldEnter(36, true, true, true, true))
    }

    @Test fun userInitiatedAllowsPlayingVideoOnActiveTabWithoutFullscreen() {
        assertTrue(BrowserPictureInPicturePolicy.shouldEnterUserInitiated(26, true, true, false))
        assertFalse(BrowserPictureInPicturePolicy.shouldEnterUserInitiated(25, true, true, false))
        assertFalse(BrowserPictureInPicturePolicy.shouldEnterUserInitiated(36, false, true, false))
        assertFalse(BrowserPictureInPicturePolicy.shouldEnterUserInitiated(36, true, false, false))
        assertFalse(BrowserPictureInPicturePolicy.shouldEnterUserInitiated(36, true, true, true))
    }
}
