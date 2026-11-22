# Nova Browser UI bugfix changelog

Branch: `fix/ui-50-browser-polish`  
Base: PR #4 branch `fix/top-10-bugs`

## Item-by-item changes

| # | File(s) | Change / status |
|---:|---|---|
| 1 | `res/values/themes.xml`, `res/values/colors.xml`, `MainActivity.kt` | Nova dark framework theme and dialog palette applied. |
| 2 | `res/values/themes.xml`, `res/values-v31/themes.xml`, launcher resources | Dark window background and Android 12+ splash configuration. |
| 3 | `res/values-night/*`, `res/mipmap-anydpi-v26/ic_nova_launcher.xml` | Night palette and adaptive launcher icon with monochrome layer. |
| 4 | `MainActivity.kt` | Gecko preferred color scheme follows system or the selected appearance preference. |
| 5 | `NovaVpnFirewallActivity.kt`, theme resources | Dark VPN/Firewall UI and switch-style controls; PR #4 ScrollView retained. |
| 6 | `MainActivity.kt`, `BrowserUiState.kt` | Distinct Private and Guest mode accents/chips. |
| 7 | `MainActivity.kt`, `NovaVpnFirewallActivity.kt` | Layout dimensions converted to density-aware values. |
| 8 | `MainActivity.kt` | Search-engine selector uses content-based sizing rather than a clipped fixed width. |
| 9 | `MainActivity.kt` | Home shortcuts can be added, edited and removed in a responsive grid. **Intentional privacy choice:** the shortcut grid uses local site initials rather than silently fetching third-party favicons. The grid behavior is complete; remote favicon contact is deliberately not part of this design. |
| 10 | `MainActivity.kt`, `res/drawable-nodpi/ic_kali_dragon.webp` | Scrollable home panel and attributed Kali-dragon artwork with content description. |
| 11 | `MainActivity.kt` | Tab actions use a horizontally scrollable row and are mode-aware. |
| 12 | `MainActivity.kt` | Slim address chrome and bottom navigation row; secondary actions moved to overflow. |
| 13 | `MainActivity.kt` | Narrow/wide layout switches using the current window width. **Partial:** this is a width-class breakpoint, not Jetpack WindowManager's fold/hinge-aware posture handling. |
| 14 | `MainActivity.kt` | Toolbar controls use minimum heights and wrap-content sizing instead of fixed clipping heights. |
| 15 | `MainActivity.kt`, `res/values/strings.xml` | URI keyboard variation, suggestions suppression, and clear-address control. |
| 16 | `MainActivity.kt` | Location updates do not replace text while the address field has focus. |
| 17 | `AndroidManifest.xml`, `MainActivity.kt` | `adjustResize` and unchanged soft-input state. |
| 18 | `MainActivity.kt` | Gecko progress delegate drives the progress bar and loading/error state; stop/reload is available from the browser menu. |
| 19 | `MainActivity.kt`, `BrowserUiState.kt` | Security state reflects secure, certificate-warning and not-secure pages; host is emphasized. |
| 20 | `MainActivity.kt`, `BrowserUiState.kt`, `BrowserUiStateTest.kt` | Live local suggestions from history/bookmarks plus a search action and URL matching. **Intentional privacy choice:** suggestions are local-only; no third-party search service receives keystrokes while the user types. |
| 21 | `MainActivity.kt` | Page title and user rename override are stored separately. |
| 22 | `MainActivity.kt` | Bookmark control reflects the current URL's saved state. |
| 23 | `res/drawable/ic_*.xml`, `MainActivity.kt`, `res/values/strings.xml` | Main navigation icons use vector resources and descriptive accessibility labels. |
| 24 | `MainActivity.kt`, `res/values/strings.xml` | Tabs control displays a live count. |
| 25 | `MainActivity.kt` | Back handling prioritizes split exit/history/home/tab/task navigation; task moves to background rather than finishing prematurely. |
| 26 | `MainActivity.kt` | Home stops the current page and navigates its tab to the internal home state; navigation controls follow the visible home/page state. |
| 27 | `MainActivity.kt` | Restored workspace tabs are lazy; only the selected tab creates a Gecko session. |
| 28 | `MainActivity.kt`, `BrowserUiState.kt` | Tab numbering is calculated independently for each mode. |
| 29 | `MainActivity.kt`, `BrowserUiState.kt` | Status line uses priority-aware state updates rather than unrelated text writes. |
| 30 | `MainActivity.kt` | Secondary pane has back/forward/reload, address synchronization, and split-aware navigation. |
| 31 | `MainActivity.kt` | Bookmarks/history/downloads use ListView adapters; bookmark/history search is debounced. |
| 32 | `MainActivity.kt` | Bookmark/history/download lists refresh in place and update counts. |
| 33 | `MainActivity.kt` | Download list has live progress, state-specific actions, refresh, and resumable interrupted downloads. |
| 34 | `MainActivity.kt`, `res/values/strings.xml` | Settings grouped by Privacy, Network, Sync, Appearance and About; About displays app version. |
| 35 | `MainActivity.kt`, `res/values/strings.xml` | Destructive actions use confirmations; reversible bookmark/shortcut deletions offer Undo. |
| 36 | `MainActivity.kt` | Reader-mode explanation is shown once and the mode is toggleable from the toolbar/menu. |
| 37 | `MainActivity.kt` | Menu flows use bottom sheets and consistent action roles. **Intentional platform choice:** native AlertDialogs remain for nested forms/system prompts where platform dialog semantics are more appropriate; action menus use bottom sheets. |
| 38 | `MainActivity.kt` | Password vault has a protected list, reveal, edit, add and delete flows; device biometric/credential gate is applied. |
| 39 | `MainActivity.kt` | Empty bookmark folders render and bookmark editor includes a folder picker. |
| 40 | `MainActivity.kt` | Prompt dialogs are tracked and dismissed/cancelled during destruction so Gecko results are completed. |
| 41 | `MainActivity.kt` | Gecko prompt delegate covers choice, alert, button, text, auth, date/time, color, popup and before-unload prompts. |
| 42 | `MainActivity.kt` | Gecko new-session requests create mode-aware tabs for target=_blank/window.open. |
| 43 | `AndroidManifest.xml`, `MainActivity.kt` | Site permission prompts, runtime permission requests, and named permission labels. |
| 44 | `MainActivity.kt` | Fullscreen delegate hides browser chrome/system bars and restores them on exit. |
| 45 | `MainActivity.kt` | Context menu supports new tab/private tab, copy link, save image and share. |
| 46 | `MainActivity.kt` | Content-process crash/kill shows reload/close recovery UI and recreates the session. |
| 47 | `MainActivity.kt` | Find-in-page bar is connected to Gecko's finder. |
| 48 | `MainActivity.kt` | Network/certificate failures show themed internal error pages. |
| 49 | `MainActivity.kt` | Search attachment control preserves the query and falls back to Android share; tab switcher uses a thumbnail grid. |
| 50 | `MainActivity.kt`, drawable/string resources | Icon labels, minimum touch dimensions, live status announcements and large-text-friendly scrolling were added. **Unverified:** TalkBack order, 4.5:1 contrast measurements, and 200% font-scale behavior require real-device accessibility testing. |

## Engine boundary

`BrowserEngine` and `GeckoBrowserEngine` in this UI branch isolate browser-chrome navigation and lifecycle calls behind an engine interface. The separate `feature/phase-1-dual-engine-foundation` branch contains the Cefrium SDK and adapter, with Private/Guest locked to GeckoView. These branches diverged from `main`; a direct merge into an isolated integration branch was rejected because of overlapping changes in `MainActivity.kt`, the manifest, download/sync code and VPN settings. A manual three-way integration is still required before the UI polish and dual-engine architecture exist together in one build. Do not read this branch-local note as meaning the repository has no Cefrium implementation.

## Automated validation

The Android CI workflow runs:

- `gradle :app:testDebugUnitTest`
- `gradle :app:assembleDebug`
- Release APK assembly when signing secrets are configured

CI status is tracked on the PR; device-level UI tests are not implied by a successful build.

## Manual test script

1. Launch in portrait, rotate to landscape and back; confirm the selected tab, URL and split state survive.
2. Toggle system light/dark mode; inspect home, tabs, bookmarks, history, downloads, settings, password vault and VPN/Firewall dialogs.
3. Set system font scale to 130%, then 200%; verify scrollability, labels and dialog actions.
4. Open the address field, type a URL and a search phrase, select a local suggestion, clear the field, open a dialog, return and verify keyboard/focus behavior.
5. Create Normal, Private and Guest tabs; verify mode accents, independent counts, persistence boundaries and Guest cleanup.
6. Open split view; navigate both panes, use each pane's back/forward/reload, open Home in one pane, press Back, and exit split.
7. At tablet width and in Android split-screen/resizable-window mode, verify the wide split layout and that controls remain reachable.
8. With TalkBack enabled, traverse every screen; check focus order, descriptions, touch targets and contrast.
9. Test Gecko web prompts (select, alert, confirm, text, HTTP auth, date/time, color, popup and before-unload), permissions, fullscreen, context menu, find-in-page, network failure and content-process crash.

**Not run in CI:** manual device script, TalkBack, 200% font scale, fold-hinge posture, and BrowserStack real-device sessions.
