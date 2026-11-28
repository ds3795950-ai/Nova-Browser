# Nova Browser roadmap and verification status

Last reconciled: 2026-11-28 13:20 UTC. This document distinguishes code present in the repository from behavior that has passed acceptance testing.

## Repository state

- Default branch: `main`.
- Current main commit: `b2caaaf8438f3c3a4c002fc797bb19743b6a4c24` — removes the obsolete extension capability policy after the user-installable add-on system was removed.
- Main-branch Android lint passed on this commit. Recent main history also includes the low-memory work and engine-routing/Guest Tor hardening.
- [PR #31 — Phase 1 Cefrium privacy and capability fallbacks](https://github.com/ds3795950-ai/Nova-Browser/pull/31) ports the remaining Cefrium privacy, saved-login, Data Saver and translation fallback work onto current main. It intentionally contains no user-installable extension or script add-on system.
- [PR #29](https://github.com/ds3795950-ai/Nova-Browser/pull/29) is based on an older main and conflicts with the extension-removal commits. Do not merge it unchanged; its relevant non-extension changes have been ported to PR #31.
- Only the internal 1VPN and AI Reader bridges remain. There is no user-facing extension manager or user-installed add-on support.

## Engine integration and feature coverage

**Implemented in source; final PR and device acceptance remain open.**

- Eligible Normal tabs prefer Cefrium; GeckoView remains the fallback when a required capability or protected route needs it.
- Private and Guest tabs are GeckoView-only and use separate ephemeral contexts. Cefrium and GeckoView persistent profiles remain isolated; website data is not migrated across engines.
- The shared per-tab engine interface and adapters are present. Some app-level GeckoView delegates and feature routing still live in `MainActivity`; further extraction is a maintainability task, not a missing user-facing browser feature.
- Nova Shield and full saved-password autofill remain GeckoView-dependent. PR #31 adds explicit, user-triggered, HTTPS same-origin saved-login filling for Normal Cefrium tabs after device credential or biometric verification.
- PR #31 adds conservative tracker-domain filtering, Data Saver image blocking, per-site image settings, and browser-owned-request cookie filtering for Cefrium. These are limited capabilities, not full GeckoView Enhanced Tracking Protection.
- Cefrium's 0.21 profile-level service-worker requests do not receive the cookie callbacks. The cookie setting is therefore labelled browser-request-only; Nova does not claim complete cookie isolation or third-party cookie partitioning.
- Cefrium translation routes to GeckoView with confirmation and reload; website data is not transferred.
- Cefrium navigation, lifecycle, permissions, downloads, Reader Mode, desktop mode and protected DNS have implementation paths. SDK parity is not complete for every GeckoView feature.
- Renderer-recovery policy and engine/profile isolation regression tests exist. Real Android lifecycle, process-death and renderer-crash behavior still require device/emulator acceptance.

## Privacy, VPN and Guest Tor

**Implemented in source; security acceptance remains open.**

- Private and Guest state is excluded from normal restore, history and cloud sync. Guest context cleanup and session isolation are enforced by policy and runtime barriers.
- Guest Mode starts the embedded Tor Android service and uses the GeckoView internal SOCKS5 proxy bridge with remote DNS and WebRTC protection. Startup and shutdown are gated; a lost route is intended to fail closed.
- GeckoView's proxy setting is runtime-wide, not per-session. Nova suspends persistent Normal sessions, refuses Guest startup while Private tabs exist, and blocks non-Guest session use while Guest Tor owns the runtime. This is a mitigation, not proof of formally audited Guest-only isolation or Tor Browser equivalence.
- 1VPN is a browser proxy, not Android `VpnService` or a full-device VPN. Protected DNS is browser-only. Neither should be described as system-wide protection.
- Physical-device tests are still required for IP/DNS/WebRTC/IPv6/UDP leakage, proxy loss, Tor bootstrap and shutdown, concurrent profiles, and recovery after process death. BrowserStack UI checks alone cannot establish network privacy.

## Downloads and encrypted sync

**Implemented in source; edge-case and multi-device acceptance remain open.**

- Downloads use engine-specific storage namespaces and include interrupted-download recovery. Partial-file reconciliation, process-death recovery, resume responses and engine-switch behavior still need extended Android tests.
- Cloud sync uses client-side encrypted payloads, per-engine profile separation, merge/tombstone handling and compare-and-swap conflict detection. Private and Guest data is excluded.
- Required acceptance: restore on two real devices; wrong-passphrase behavior; concurrent edits/conflict retry; deletion propagation; sign-out and account-deletion behavior. Do not mark multi-device sync fully accepted from unit tests alone.

## Authentication and release setup

**Code exists; owner-controlled setup and live acceptance remain.**

- Email OTP, encrypted local session storage, token refresh and sign-out are implemented. The Supabase dashboard/template/SMTP configuration must be checked in the live project; repository code alone cannot prove delivery.
- A verified sender domain and provider SMTP configuration may be required for reliable production OTP delivery. The project owner must validate actual delivery and expiry.
- Production signing requires `NOVA_RELEASE_KEYSTORE_BASE64`, `NOVA_RELEASE_STORE_PASSWORD`, `NOVA_RELEASE_KEY_ALIAS` and `NOVA_RELEASE_KEY_PASSWORD`. Do not put these secrets in source.
- The current dual-engine ARM64 test APK was previously measured at approximately 222 MB; the under-100-MB target is not met while both native engines are bundled.

## Phase 1 exit criteria

- PR #31 Android build/unit tests, lint and Android 16 emulator smoke all pass on its final head.
- CodeRabbit findings are reviewed; all valid critical/major findings are fixed or explicitly documented as unsupported SDK behavior.
- The final diff contains no user-installable extensions, scripts or add-on manager, and preserves the two internal bridges.
- No known broken core browser flows are introduced by the port.

## Phase 2 acceptance

- Test on the target ARM64 Android phone for startup, memory, battery, renderer recovery, downloads and profile lifecycle.
- Verify 1VPN and Guest Tor with real IP, DNS, WebRTC, IPv6 and proxy-loss tests.
- Verify cloud sync on two separate devices, including conflict and deletion propagation.
- Verify live email OTP delivery, expiry and sign-out.
- Run BrowserStack UI regression when credentials/quota are available; UI tests do not prove network privacy.
- Configure production signing, verify the signed release artifact, and investigate APK size without silently removing an engine.

## Scope decisions

- Do not add Opera-inspired features merely for feature parity. Finish, stabilize and verify Nova's existing product scope.
- Do not bypass engine capability or privacy guards to force Cefrium usage.
- Do not claim Tor anonymity, Tor Browser equivalence, full-device VPN, complete ad blocking, complete cookie isolation, or cross-engine website-data migration.
- A code path or passing JVM test is not equivalent to physical-device or security acceptance.
- User-installable extensions, user scripts and an add-on manager are intentionally outside Nova's product scope. Only the internal 1VPN and AI Reader bridges are retained.
