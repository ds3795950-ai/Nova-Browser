# Phase 1 — Research, architecture and dual-engine foundation

**Branch:** `feature/phase-1-dual-engine-foundation`  
**Baseline audited:** `fb717c1cd48da2683ad376d40e5b0bfbbe686d22` (`main`, 2026-11-19)  
**Status:** dual-engine source integration is merged on `main`; repository CI and emulator smoke checks pass. Physical-device acceptance remains open.

## Repository findings

- Nova is a Kotlin Android app (`com.nova.browser`) with a single large `MainActivity` that owns the browser UI, tab list and most GeckoView delegates.
- `MainActivity` creates one `GeckoRuntime` and a `GeckoSession` per tab. Two `GeckoView` instances support the split-pane UI. The runtime is shut down with the activity.
- The tab model already distinguishes normal, private and Guest tabs. Guest sessions use Gecko private mode plus a fresh session context ID, and the previous Guest context is cleared at startup/exit. Guest and private tabs are excluded from normal tab persistence, password autofill and cloud sync.
- Bookmarks, history, downloads, password vault, Shield, protected DNS and encrypted cloud sync are implemented in existing app services. Guest Mode now uses an embedded Tor service and GeckoView SOCKS5 extension bridge, with an explicit runtime-wide proxy isolation limitation documented below.
- `BrowserTab` now holds an engine-neutral `BrowserEngine` adapter, while `MainActivity` still owns app-level delegates and feature routing. Remaining work is to reduce residual Gecko-specific ownership without breaking permissions, downloads, extensions, translation, Shield, split-screen and Guest isolation.

## Cefrium compatibility decision

The current Cefrium release checked for this branch is **0.21.0**, tracking Chromium **156.0.8078.96** (released 2026-11-15). It is a public beta and pre-1.0; the public API can change between minor releases. Pin the SDK and Gradle plugin to the same exact version and keep all Cefrium calls behind `CefriumBrowserEngine`.

| Requirement | Nova baseline | Cefrium 0.21.0 requirement |
|---|---:|---:|
| JDK | 17 | 25 |
| Gradle | 9.3.1 | 9.7.1 |
| Android Gradle Plugin | 9.1.1 | 9.4.0 |
| compileSdk | 37 | 37.1 (`minorApiLevel = 1`) |
| minSdk | 29 | 29 |
| ABIs | arm64-v8a, armeabi-v7a | arm64-v8a, armeabi-v7a, x86_64 |

The Phase 1 branch updates CI and Gradle to the Cefrium toolchain and compiles against Android API 37.1 (`release(37) { minorApiLevel = 1 }`). CI installs `platforms;android-37.1`. It includes all three ABI splits declared by the integration build. Cefrium's native engine has a substantial size and memory cost (its quickstart documents roughly 203 MB uncompressed for the ARM64 native library and about 89 MB download per architecture). APK/AAB size and memory must be measured before enabling it for all users.

Official integration references:

- [Cefrium quickstart](https://cefrium.com/quickstart/)
- [Cefrium releases](https://cefrium.com/releases/)
- [Cefrium adoption constraints](https://cefrium.com/guides/adopting-cefrium/)
- [Cefrium Browser API](https://cefrium.com/docs/com/cefrium/CefriumBrowser.html)
- [Cefrium Profile API](https://cefrium.com/docs/com/cefrium/CefriumProfile.html)


## GeckoView + Chromium manifest compatibility

The first combined-engine manifest merge exposed a conflict: GeckoView declares `org.mozilla.gecko.process.ZygotePreload`, while Cefrium declares `org.chromium.content_public.app.ZygotePreload`. Android exposes only one application-level `android:zygotePreloadName`. Android's API documentation defines this as an optional preloader for isolated services using the app zygote, not as the browser engine entry point. Phase 1 removes this optional attribute from the merged app manifest so neither engine's preload class is incorrectly invoked for the other engine. This may reduce startup optimization for app-zygote isolated processes; it does not select or disable either browser engine. Revisit only if device profiling shows a material regression and a safe composite preloader can be demonstrated.

## Foundation added in this branch

1. `BrowserEngine` defines a minimal per-tab rendering/navigation/lifecycle contract and `BrowserEngineState` provides an engine-neutral state snapshot.
2. `GeckoViewBrowserEngine` wraps one `GeckoSession` and one `GeckoView`, leaving the app-level `GeckoRuntime` owned by the host.
3. `CefriumBrowserEngine` wraps a `CefriumBrowser` created with an explicit `CefriumProfile`; it reports URL/title/loading/back-forward state and forwards activity lifecycle events.
4. A debug-only **Cefrium Engine Lab** is reachable from Settings in debug builds. It uses the isolated `nova-engine-probe` Cefrium profile and supports load/back/forward/reload. It is absent from release manifests.
5. Production `MainActivity` now owns engine-neutral tab lifecycle and selects Cefrium for eligible Normal tabs, with GeckoView fallback for unsupported features or runtime initialization failures. Private and Guest tabs remain GeckoView-only. The default Cefrium preference does not override Nova Shield or VPN capability requirements.

## Engine profile policy for later phases

- Normal Cefrium tabs use the named persistent Nova profile.
- Private and Guest tabs are GeckoView-only. Private uses GeckoView private sessions; Guest uses Gecko private mode plus a dedicated temporary context ID.
- The Normal, Private and Guest stores must never be merged. Cross-engine website data migration remains best-effort and must preserve the source profile until the destination session is verified.

## Tor implementation and isolation boundary

- Nova now bundles the embedded Tor Android service and configures a GeckoView WebExtension SOCKS5 proxy with remote DNS and WebRTC protection. Guest startup waits for a Tor circuit, valid SOCKS port and extension readiness; loss of the chain closes Guest Mode.
- GeckoView has no supported public per-session proxy API. The extension proxy is runtime-wide, so the app suspends every persistent Normal engine session, refuses Guest startup while Private tabs are open, and blocks non-Guest tab creation/switching throughout Tor startup, active use and shutdown. Normal sessions resume only after the extension acknowledges `torGuestMode=false`. This is a mitigation, not yet a formally verified Guest-only boundary.
- Cefrium exposes a proxy on each CefriumProfile; its adapter is scoped to Normal Cefrium profiles and must pass independent DNS/WebRTC/IP leak tests.
- Physical-device tests must verify Guest Tor while Normal Cefrium tabs exist, that Private tabs are refused, and that Normal GeckoView sessions restore after Guest exit. Also test DNS, UDP/WebRTC, redirects, proxy failure, circuit changes and Normal/Private non-interference.

References: [Arti Android integration](https://arti.torproject.org/integrating-arti/custom-wrappers/android/), [Arti status](https://arti.torproject.org/FAQs/).

## Acceptance criteria and current status

- [x] GitHub Actions resolves the pinned Cefrium SDK and completes unit tests and Android debug build on the merged `main` branch.
- [x] Android lint and the Android emulator smoke workflow pass on the Phase 2 merge commit.
- [x] MainActivity has production Cefrium tab creation, engine preference selection, profile isolation, and GeckoView fallback paths.
- [x] Unit tests cover engine selection, feature capability routing, profile isolation and renderer recovery policy.
- [ ] Run the full Cefrium navigation/lifecycle suite on physical ARM64 Android, including back, forward, reload, pause/resume and renderer recovery.
- [ ] Measure memory, startup and per-ABI APK/AAB size on the target device before enabling Cefrium unconditionally for all Normal tabs.
- [ ] Verify that Nova Shield and saved-password fallback do not silently weaken privacy or lose form data during engine routing.

A successful build or emulator smoke test alone does not satisfy physical-device acceptance. Cefrium remains capability-gated: Nova must not disable Shield or bypass saved-login requirements merely to force a Chromium engine.
