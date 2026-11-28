# Nova Browser — Cefrium integration

## Engine routing

- Normal tabs prefer Cefrium 0.21.0 and use the persistent named profile `nova-normal-v1`.
- Private and Guest tabs are hard-pinned to GeckoView. They never create or open a Cefrium profile.
- The engine choice is per tab and is re-evaluated for each destination URL. Automatic mode supports exact-host site affinity; related sign-in hosts are saved only when the user enters them explicitly.
- Engine switches reload the tab. Cookies, local storage, IndexedDB, service workers and live authentication state are not copied between engines. Users may need to sign in again. This is intentional: Nova does not attempt an unsafe or incomplete browser-profile migration.

## GeckoView-only features and safe fallback

The default Nova Shield mode is Standard, so first-run Normal tabs use GeckoView until Shield is turned off or the user selects a compatible configuration. Cefrium is not selected while full Nova Shield tracking/cookie protections are enabled, while Nova's 1VPN browser proxy is enabled, or for a URL scheme Cefrium does not support. Cefrium Normal tabs can fill an existing vault entry only after biometric/device-credential verification and an explicit user selection; the saved origin and form action must match the current page origin. Automatic login detection, save prompts and GeckoView-native autofill remain GeckoView-only. The tab falls back to GeckoView and shows the reason. Disabling one of those features does not silently migrate an existing tab back; the user can switch it in Settings or the next navigation can apply the selected automatic policy.

Protected DNS uses the same AdGuard DNS-over-HTTPS endpoint in both engines. Cefrium's component updater stays enabled because it refreshes security-critical browser data. It makes HTTPS requests to `update.googleapis.com` carrying component IDs/versions, platform and a random per-component install identifier, not page URLs, history or typed content. This updater traffic does not use the Cefrium profile proxy; enabling Nova 1VPN does not route that maintenance traffic through 1VPN. Keep the updater enabled and include its identifier in the Play Data safety disclosure. Widevine provisioning occurs only if a page uses DRM; network-time queries are off by default. Cefrium site-permission requests are validated for secure origins and known permission types, then shown through Nova's prompt and Android runtime permission flow. Unknown or opaque-origin requests are denied. Site Controls can clear a site's cookies, local/session storage, IndexedDB, Cache Storage and service workers through `CefriumProfile.clearSiteData`, and clear the engine HTTP cache. Privacy settings can retire the entire named profile: Nova closes its Cefrium tabs, rotates to a new empty profile immediately, and asks Cefrium to delete the old profile (which may finish on the next process start). Downloads initiated in an active Cefrium tab are published to `Downloads/Nova` through MediaStore and registered in Nova's download history. Cefrium also exposes a profile-level download observer for downloads resumed after process death; that observer is not yet reconciled into Nova's custom list, so such resumed downloads remain available through Android Downloads/Cefrium until that follow-up is implemented.

The adapter also routes title, URL, load/progress/error, renderer termination/unresponsive state, find-in-page, reader extraction, desktop user-agent, fullscreen, JavaScript dialogs, file chooser, HTTP authentication, external URI schemes and popup lifecycle events. A Cefrium popup becomes a new Normal tab in the same named profile and `window.close()` closes that tab. Cefrium long-press context data is mapped into Nova's bottom-sheet actions for opening/copying/sharing links, copying selected text and saving media. SSL certificate and client-certificate challenges fail closed; Nova does not offer a certificate-error bypass.

## Build requirements

Cefrium 0.21.0 requires JDK 25, Gradle 9.7.1, Android Gradle Plugin 9.4 and compileSdk 37 (minSdk 29). The app builds only `arm64-v8a`, `armeabi-v7a` and `x86_64`, with ABI-split APKs to avoid shipping every native engine binary to every device. The SDK is LGPL-3.0-or-later; follow `CEFrium_LICENSE_COMPLIANCE.md` before redistributing builds.

## Verification

The project CI runs JVM tests, debug/release assembly, lint, and an Android 16 x86_64 emulator smoke suite that creates a Cefrium surface, loads a local page, and exercises Reader Mode. Emulator tests do not replace testing on physical ARM hardware. A physical-device check is still required for video/fullscreen, file chooser, camera/microphone/location prompts, popup navigation, downloads, split-pane view reparenting and memory pressure.
