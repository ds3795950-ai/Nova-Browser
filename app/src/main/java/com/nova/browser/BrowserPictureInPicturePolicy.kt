package com.nova.browser

/** Gate Android Picture-in-Picture to active browser fullscreen sessions on supported API levels. */
internal object BrowserPictureInPicturePolicy {
    fun shouldEnter(
        apiLevel: Int,
        isFullscreen: Boolean,
        fullscreenTabIsActive: Boolean,
        videoIsPlaying: Boolean,
        alreadyInPictureInPicture: Boolean
    ): Boolean = apiLevel >= 26 && isFullscreen && fullscreenTabIsActive &&
        videoIsPlaying && !alreadyInPictureInPicture
}
