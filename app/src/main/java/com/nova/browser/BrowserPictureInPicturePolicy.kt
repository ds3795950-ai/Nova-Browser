package com.nova.browser

/**
 * Picture-in-Picture entry rules.
 *
 * Automatic entry stays conservative: fullscreen + active tab + playing video.
 * User-initiated "pop out" may enter when the active tab is playing video even if
 * the page is not in browser-chrome fullscreen (still requires API 26+).
 */
internal object BrowserPictureInPicturePolicy {
    fun shouldEnter(
        apiLevel: Int,
        isFullscreen: Boolean,
        fullscreenTabIsActive: Boolean,
        videoIsPlaying: Boolean,
        alreadyInPictureInPicture: Boolean
    ): Boolean = shouldEnterAutomatic(
        apiLevel = apiLevel,
        isFullscreen = isFullscreen,
        fullscreenTabIsActive = fullscreenTabIsActive,
        videoIsPlaying = videoIsPlaying,
        alreadyInPictureInPicture = alreadyInPictureInPicture
    )

    fun shouldEnterAutomatic(
        apiLevel: Int,
        isFullscreen: Boolean,
        fullscreenTabIsActive: Boolean,
        videoIsPlaying: Boolean,
        alreadyInPictureInPicture: Boolean
    ): Boolean = apiLevel >= 26 &&
        isFullscreen &&
        fullscreenTabIsActive &&
        videoIsPlaying &&
        !alreadyInPictureInPicture

    /** Explicit user action (menu / button), not the automatic fullscreen path. */
    fun shouldEnterUserInitiated(
        apiLevel: Int,
        activeTabIsCurrent: Boolean,
        videoIsPlaying: Boolean,
        alreadyInPictureInPicture: Boolean
    ): Boolean = apiLevel >= 26 &&
        activeTabIsCurrent &&
        videoIsPlaying &&
        !alreadyInPictureInPicture
}
