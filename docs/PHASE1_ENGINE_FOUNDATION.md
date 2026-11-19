# Phase 1 — Research, architecture and dual-engine foundation

**Branch:** `feature/phase-1-dual-engine-foundation`  
**Baseline audited:** `fb717c1cd48da2683ad376d40e5b0bfbbe686d22` (`main`, 2026-11-19)  
**Status:** source foundation added; CI and device verification pending.

## Repository findings

- Nova is a Kotlin Android app (`com.nova.browser`) with a single large `MainActivity` that owns the browser UI, tab list and most GeckoView delegates.
- `MainActivity` creates one `GeckoRuntime` and a `GeckoSession` per tab. Two `GeckoView` instances support the split-pane UI. The runtime is shut down with the activity.
- The tab model already distinguishes normal, private and Guest tabs. Guest sessions use Gecko private mode plus a fresh session context ID, and the previous Guest context is cleared at startup/exit. Guest and private tabs are excluded from normal tab persistence, password autofill and cloud sync.
- Bookmarks, history, downloads, password vault, Shield, protected DNS and encrypted cloud sync are implemented in existing app services. The existing Orbot handoff is not an embedded Tor transport and does not prove per-tab fail-closed routing.
- The current production tab manager is tightly coupled to `GeckoSession`; switching it in one step would risk breaking permissions, downloads, extensions, translation, Shield, split-screen and Guest isolation.

## Cefrium compatibility decision

The current Cefrium release checked for this branch is **0.21.0**, tracking Chromium **156.0.8078.96** (released 2026-11-15). It is a public beta and pre-1.0; the public API can change between minor releases. Pin the SDK and Gradle plugin to the same exact version and keep all Cefrium calls behind `CefriumBrowserEngine`.

| Requirement | Nova baseline | Cefrium 0.21.0 requirement |
|---|---:|---:|
| JDK | 17 | 25 |
| Gradle | 9.3.1 | 9.7.1 |
| Android Gradle Plugin | 9.1.1 | 9.4.0 |
| compileSdk | 37 | 37 |
| minSdk | 29 | 29 |
| ABIs | arm64-v8a, armeabi-v7a | arm64-v8a, armeabi-v7a, x86_64 |

The Phase 1 branch updates CI and Gradle to the Cefrium toolchain and includes all three supported ABI splits. Cefrium's native engine has a substantial size and memory cost (its quickstart documents roughly 203 MB uncompressed for the ARM64 native library and about 89 MB download per architecture). APK/AAB size and memory must be measured before enabling it for all users.

Official integration references:

- [Cefrium quickstart](https://cefrium.com/quickstart/)
- [Cefrium releases](https://cefrium.com/releases/)
- [Cefrium adoption constraints](https://cefrium.com/guides/adopting-cefrium/)
- [Cefrium Browser API](https://cefrium.com/docs/com/cefrium/CefriumBrowser.html)
- [Cefrium Profile API](https://cefrium.com/docs/com/cefrium/CefriumProfile.html)

## Foundation added in this branch

1. `BrowserEngine` defines a minimal per-tab rendering/navigation/lifecycle contract and `BrowserEngineState` provides an engine-neutral state snapshot.
2. `GeckoViewBrowserEngine` wraps one `GeckoSession` and one `GeckoView`, leaving the app-level `GeckoRuntime` owned by the host.
3. `CefriumBrowserEngine` wraps a `CefriumBrowser` created with an explicit `CefriumProfile`; it reports URL/title/loading/back-forward state and forwards activity lifecycle events.
4. A debug-only **Cefrium Engine Lab** is reachable from Settings in debug builds. It uses the isolated `nova-engine-probe` Cefrium profile and supports load/back/forward/reload. It is absent from release manifests.
5. The production `MainActivity` remains on its existing GeckoView path. Phase 2 will migrate tab ownership to the shared interface after the Cefrium probe passes CI and device testing.

## Engine profile policy for later phases

- Normal Cefrium tabs will use a named persistent Nova profile.
- Guest Cefrium tabs must use Cefrium's in-memory `CefriumProfile.incognito()` (or a verified disposable profile lifecycle), never the normal profile.
- GeckoView Guest tabs retain their existing isolated Guest context until the tab manager is migrated.
- The normal and Guest stores must never be merged. Cross-engine website data migration remains best-effort and must preserve the source profile until the destination session is verified.

## Tor feasibility findings for Phase 4

- Cefrium exposes a proxy on each `CefriumProfile`; a fixed proxy has no direct fallback. Its documentation explicitly warns that WebRTC is not covered by that proxy, so WebRTC/IP handling and DNS need separate enforcement and leak tests.
- GeckoView has no simple supported per-session proxy API. Its configuration must be investigated at runtime/process level; do not assume a Gecko session can independently inherit a SOCKS route.
- An app-wide Android `VpnService` can capture normal-mode and Guest traffic together. It is not sufficient by itself to guarantee Guest-only Tor while normal tabs continue using the ordinary route.
- Tor Project's Arti Android guide says there is no stable Rust API/binding yet and a custom JNI wrapper is required. Arti is viable for experimentation but not a drop-in Android library. The mature C Tor/Orbot route is an external VPN handoff, not the required in-app per-Guest-engine route.
- Phase 4 must prove routing for both engines, including DNS, UDP/WebRTC, redirects, proxy failure, circuit changes and normal-mode non-interference. No Tor implementation is added in Phase 1.

References: [Arti Android integration](https://arti.torproject.org/integrating-arti/custom-wrappers/android/), [Arti status](https://arti.torproject.org/FAQs/).

## Acceptance criteria

- [ ] GitHub Actions resolves Cefrium 0.21.0 and completes unit tests + debug/release assembly with JDK 25 / Gradle 9.7.1 / AGP 9.4.0.
- [ ] The debug Engine Lab launches and renders a normal HTTPS page on ARM64 Android.
- [ ] Back, forward, reload, renderer recovery and activity pause/resume work in the Engine Lab.
- [ ] The Cefrium profile used by the probe is distinct from the production profile.
- [ ] Existing GeckoView regression suite remains green.
- [ ] Real-device memory, APK size and stability are measured before Phase 2 makes Cefrium a normal-mode default.

A successful build alone does not satisfy the device acceptance criteria.
