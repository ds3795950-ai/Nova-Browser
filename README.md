# Nova Browser

Nova is an Android/Kotlin browser. **GeckoView remains the production engine** while Phase 1 adds Cefrium 0.21.0 behind an isolated engine adapter and a debug-only engine lab. Normal-mode engine selection is not enabled yet.

This repository is a fresh reconstruction. The previous repository/account could not be accessed, and no source backup was available; this is not a recovered copy.

## Current browser-core slice

- Address and search bar with Google, Bing, DuckDuckGo and Brave Search
- Back, forward, reload and home controls
- Multiple normal and private GeckoView sessions
- Tab switcher and close-current-tab action
- Local bookmarks and normal-session browsing history
- Private tabs are excluded from the normal history and bookmark store
- Android share sheet and a basic settings panel
- GitHub Actions unit-test, debug-build and APK-artifact workflow

## Build

Requires JDK 25, Gradle 9.7.1, Android SDK Platform 37 and Android Gradle Plugin 9.4.0. Cefrium 0.21.0 requires this toolchain.

```bash
gradle testDebugUnitTest
gradle assembleDebug
```

The CI workflow publishes the debug APK as a short-lived workflow artifact after a successful build.

## Security notes

- The live MainActivity still uses GeckoView. Cefrium is currently available through the debug-only Engine Lab; the production tab manager has not been migrated.
- Guest Mode, Nova Shield, encrypted cloud sync and authentication have existing GeckoView implementations. Embedded Tor routing is not implemented; the existing Orbot handoff is not proof of per-tab Tor enforcement.
- Do not put API keys, Supabase service-role keys, signing secrets or user credentials in source control.
- Private-mode behavior still requires device-level verification before being described as production-ready.

See [docs/PRODUCT_DECISIONS.md](docs/PRODUCT_DECISIONS.md) and [docs/ROADMAP.md](docs/ROADMAP.md).
