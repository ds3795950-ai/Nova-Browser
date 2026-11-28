# Nova Browser

Nova is an Android/Kotlin browser built around an engine-neutral tab manager, with GeckoView and Cefrium 0.21.0 adapters.

## Current architecture

- **Normal tabs:** Cefrium is the preferred engine for eligible sites. Nova falls back to GeckoView when Nova Shield is active, 1VPN is enabled, saved-password autofill is required, a page type is unsupported, or Cefrium cannot initialize.
- **Private and Guest tabs:** GeckoView-only, with separate ephemeral contexts.
- **Engine isolation:** GeckoView and Cefrium use independent profiles. Nova does not copy cookies, cache, local storage, passwords, permissions or session state between engines.
- **Guest Mode:** Uses the embedded Tor Android service and a GeckoView internal SOCKS5 proxy bridge with remote DNS and WebRTC protection. GeckoView's proxy is runtime-wide, not a supported per-tab proxy API; Nova blocks conflicting GeckoView sessions while Guest routing starts, runs and shuts down. This is not a claim of Tor Browser equivalence or formally audited per-tab isolation.
- **1VPN:** The bundled 1VPN proxy component provides a browser proxy, not Android VpnService or a full-device VPN. It currently protects GeckoView traffic; Nova falls back from Cefrium while 1VPN is enabled.
- **Privacy and sync:** Nova Shield, protected DNS, encrypted cloud sync and authentication are implemented in app services. Private and Guest data are excluded from normal sync/history.

## Build

Requires JDK 25, Gradle 9.7.1, Android Gradle Plugin 9.4.0 and Android SDK Platform 37. Cefrium 0.21.0 is pinned to the same toolchain.

```bash
gradle testDebugUnitTest
gradle assembleDebug
```

The Android CI workflow runs unit tests, builds debug and release variants, and publishes only the installable ARM64 test APK for the target device class. If production signing secrets are absent, CI signs the optimized release APK with the runner's temporary Android debug key; that artifact is for testing only, not Play Store distribution, and may require uninstalling an older build before installation. Lint and Android 16 emulator instrumentation tests run separately. The latest ARM64 release output is approximately 222 MB, so the under-100-MB target is not met with both native engines bundled; reaching it would require removing an engine or delivering one on demand.

## Release readiness

- GitHub build, unit-test, lint and emulator checks are necessary but do not replace physical-device acceptance.
- Physical ARM64 testing is still required for startup, memory use, renderer recovery, Cefrium navigation/lifecycle, 1VPN proxy failures, DNS/WebRTC/IP leakage and Guest Tor isolation.
- Guest Tor's runtime-wide GeckoView proxy limitation must remain visible; do not describe it as formally audited Guest-only isolation.
- A signed release APK is produced only when the required release-signing secrets are configured. Otherwise, use the debug artifact for testing only.

Do not commit API keys, Supabase service-role keys, signing secrets or user credentials.

See [docs/PRODUCT_DECISIONS.md](docs/PRODUCT_DECISIONS.md), [docs/ROADMAP.md](docs/ROADMAP.md), [docs/PHASE1_ENGINE_FOUNDATION.md](docs/PHASE1_ENGINE_FOUNDATION.md) and [docs/PRIVACY_AND_COMPATIBILITY.md](docs/PRIVACY_AND_COMPATIBILITY.md).
