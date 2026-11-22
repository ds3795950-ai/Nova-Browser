package com.nova.browser

import org.mozilla.geckoview.GeckoSession
import java.util.UUID

internal const val NOVA_HOME_URL = "about:blank"

internal data class BrowserTab(
    var session: GeckoSession?,
    val isPrivate: Boolean,
    val isGuest: Boolean = false,
    val id: String = UUID.randomUUID().toString(),
    var lastUrl: String = NOVA_HOME_URL,
    var group: String = "Ungrouped",
    var title: String = "New tab",
    var canGoBack: Boolean = false,
    var canGoForward: Boolean = false,
    var lastAccess: Long = 0L,
    var needsReload: Boolean = false,
    var desktopSite: Boolean = false,
    var readerOriginalUrl: String? = null,
    var trackingPermission: GeckoSession.PermissionDelegate.ContentPermission? = null,
    var contentPermissions: List<GeckoSession.PermissionDelegate.ContentPermission> = emptyList()
)
