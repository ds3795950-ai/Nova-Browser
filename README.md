# Nova Browser

Nova is an Android/Kotlin browser using **GeckoView as its only browser engine**.

This repository is a fresh reconstruction. The previous repository/account could not be accessed, and no source backup was available; this is not a recovered copy.

## Current implementation

- Address and search bar with Google, Bing, DuckDuckGo and Brave Search
- Back, forward, reload, home controls, multiple tabs and split view
- Normal, Private and Guest browsing modes; Private and Guest tabs are excluded from normal history and password autofill, and cannot open normal-profile bookmarks, history, downloads, saved workspaces or the password vault
- Local bookmarks, bounded browsing history, saved workspaces and tab restoration
- Encrypted local password vault backed by Android Keystore
- Encrypted cloud sync with account binding and periodic WorkManager sync
- GeckoView tracking protection through Nova Shield
- Optional AdGuard DNS-over-HTTPS preference for Nova's GeckoView resolver
- Bundled 1VPN WebExtension browser proxy
- Guest-only Orbot hand-off with an explicit app-egress verification check
- GitHub Actions 1VPN validation, dependency-lock verification, JVM unit tests, debug/release builds and APK artifacts

## Important limitations

- **1VPN is a browser-level proxy, not an Android `VpnService`.** It does not route traffic from other apps or all device traffic.
- **Tor is not built into Nova.** The Guest-only Orbot action opens the external Orbot app; the user must enable Orbot VPN mode and include Nova in Orbot's per-app list. Nova can call Tor Project's check endpoint from its app process and report whether the current app egress is recognized as Tor, but Android controls Orbot's protected VPN on/off state and the check does not prove every tab/request was routed. If Nova is not selected in Orbot, the check may use the regular network.
- **Protected DNS is best-effort DoH.** Nova prefers the configured AdGuard resolver and falls back to the platform resolver if it is unavailable; it is not a fail-closed DNS tunnel. The settings probe checks endpoint reachability only, not GeckoView's live resolver selection.
- **Nova Shield uses GeckoView's built-in protections.** It is not a guarantee against all tracking, ads, fingerprinting, or malicious sites.
- **Cloud sync requires the configured account and passphrase.** Sync merges normal-profile browser data and the encrypted password vault; Guest and Private data are not included.
- Device-level privacy, session lifecycle, and network routing still require real Android-device testing before production claims are made.

## Build

Requires JDK 17, Gradle 9.3.1, Android SDK Platform 37 and Android Gradle Plugin 9.1.1.

```bash
gradle testDebugUnitTest
gradle lintDebug
gradle assembleDebug
```

The CI workflow publishes APK artifacts after a successful build.

## Security notes

- GeckoView is the only rendering engine.
- Cleartext network traffic is blocked by the app's default Network Security Config; exceptions must be explicit and reviewed.
- The Supabase URL and publishable/anonymous key are client configuration, not secrets. **Never add a Supabase service-role key, signing key, access token, or user credential to source control or the APK.**
- The local password vault, bookmarks, history, saved workspaces and deletion metadata use AES-GCM with a non-exportable Android Keystore key. Losing that key can make local encrypted data undecryptable; cloud sync is separately encrypted with the user's passphrase.

See [docs/PRODUCT_DECISIONS.md](docs/PRODUCT_DECISIONS.md) and [docs/ROADMAP.md](docs/ROADMAP.md).
