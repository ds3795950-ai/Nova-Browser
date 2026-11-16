# Nova Browser

Nova is being reconstructed as an Android/Kotlin browser using **GeckoView as its only browser engine**.

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

Requires JDK 17, Gradle 9.3.1, Android SDK Platform 37 and Android Gradle Plugin 9.1.1.

```bash
gradle testDebugUnitTest
gradle assembleDebug
```

The CI workflow publishes the debug APK as a short-lived workflow artifact after a successful build.

## Security notes

- GeckoView is the only rendering engine.
- Guest Mode, Nova Shield, Tor routing, cloud sync and authentication are not yet implemented.
- Do not put API keys, Supabase service-role keys, signing secrets or user credentials in source control.
- Private-mode behavior still requires device-level verification before being described as production-ready.

See [docs/PRODUCT_DECISIONS.md](docs/PRODUCT_DECISIONS.md) and [docs/ROADMAP.md](docs/ROADMAP.md).
