# Nova Browser roadmap and verification status

Last reconciled: 2026-11-28 14:08 UTC. This document describes the current default `main` branch and separately identifies work under review.

## Repository state

- Default branch: `main`.
- Current main commit: `a1c45fe` (PR #31 merge). Its ancestry includes PR #25 engine-routing/Guest Tor hardening, PR #27 low-memory performance diagnostics and the removal of the user-installable add-on system.
- Main-branch Android build, unit tests, lint and Android 16 emulator smoke passed on the current main commit.
- [PR #27](https://github.com/ds3795950-ai/Nova-Browser/pull/27) is merged.
- [PR #31 — Cefrium privacy and capability fallbacks](https://github.com/ds3795950-ai/Nova-Browser/pull/31) is merged (`a1c45fe`); Android build, lint and Android 16 emulator smoke passed on its final head.
- [PR #32 — Phase 2 cloud sync and lifecycle hardening](https://github.com/ds3795950-ai/Nova-Browser/pull/32) adds the single-row atomic sync bundle RPC (`nova_upload_sync_bundle(p_bundle, p_expected_updated_at)`), the lock-order migration history, the updated SQL regression test and a download-recovery test. **The three `supabase/migrations/20261128*` files must be applied to the live Supabase project before clients with this change are used for sync.**
- PR #29 remains closed because it would restore the removed user-installable add-on system.

## Engine integration and feature coverage

**Implemented in source; final PR and device acceptance remain open.**

- Eligible Normal tabs prefer Cefrium; GeckoView remains the fallback when a required capability or protected route needs it.
- Private and Guest tabs are GeckoView-only and use separate ephemeral contexts. Cefrium and GeckoView persistent profiles remain isolated; website data is not migrated across engines.
- The shared per-tab engine interface and adapters are present. Some app-level GeckoView delegates and feature routing still live in `MainActivity`; further extraction is a maintainability task, not a missing user-facing browser feature.
- Nova Shield and full saved-password autofill remain GeckoView-dependent. PR #31 adds explicit, user-triggered, HTTPS same-origin saved-login filling for Normal Cefrium tabs after device credential or biometric verification.
- PR #31 adds conservative tracker-domain filtering, Data Saver image blocking, per-site image settings, and browser-owned-request cookie filtering for Cefrium. These are limited capabilities, not full GeckoView Enhanced Tracking Protection.
- Nova does not rely on per-origin cookie callbacks for profile-level service-worker requests, which may use the profile cookie store. The cookie setting is therefore labelled browser-request-only; Nova does not claim complete cookie isolation or third-party cookie partitioning.
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

- Downloads use engine-specific storage namespaces and include interrupted-download recovery. PR #32 adds an Android instrumentation regression test that restores RUNNING/FINALIZING records as INTERRUPTED while preserving user-paused records. Full network resume, partial-file reconciliation, MediaStore finalization and engine-switch behavior still need device acceptance.
- Cloud sync uses client-side encrypted payloads, per-engine profile separation, merge/tombstone handling and compare-and-swap conflict detection. Private and Guest data is excluded.
- PR #32 changes new clients to store the encrypted browser snapshot and password vault together as one bundle in a single `nova_sync_state` row, written by the security-invoker `nova_upload_sync_bundle(p_bundle, p_expected_updated_at)` compare-and-swap RPC. This removes the partial-write window between the two former tables. Legacy two-table backups remain readable. Whether older clients can read the bundle format has not been verified; do not assume rollback compatibility.
- Live Supabase project check (2026-11-28): project is `ACTIVE_HEALTHY`, Postgres 17.11.0.019. Both sync tables have RLS enabled; authenticated SELECT/INSERT/UPDATE/DELETE privileges are present with owner-scoped policies and UPDATE `WITH CHECK`; anon SELECT is denied. Security and performance advisors returned no lints. Each table has one updated-at trigger. Both tables currently contain zero rows and `auth.users` has zero users, so no real account backup has yet been proven.
- The `nova_upload_sync_bundle` RPC migration is applied live. It runs as SECURITY INVOKER, has EXECUTE granted only to `authenticated` (not `anon`), and atomically validates both revisions before updating the bundled snapshot and compatibility vault row.
- Required acceptance: restore on two real devices; wrong-passphrase behavior; concurrent edits/conflict retry; deletion propagation; sign-out and account-deletion behavior. Do not mark multi-device sync fully accepted from unit tests alone.

## Authentication and release setup

**Code exists; owner-controlled setup and live acceptance remain.**

- Email OTP, encrypted local session storage, token refresh and sign-out are implemented. Live Supabase Auth settings were read on 2026-11-28: email sign-in enabled, six-digit OTP, 600-second expiry, Magic Link template contains `{{ .Token }}`, and a custom SMTP host is configured on port 465 with sender name `Nova creator`. No SMTP hostname or credentials are included in repository docs.
- Remaining acceptance is an actual send/verify/expiry test using a real inbox. The project settings are correctly configured, but delivery to a user's inbox has not been exercised in this session.
- Production signing requires `NOVA_RELEASE_KEYSTORE_BASE64`, `NOVA_RELEASE_STORE_PASSWORD`, `NOVA_RELEASE_KEY_ALIAS` and `NOVA_RELEASE_KEY_PASSWORD`. Do not put these secrets in source.
- The current dual-engine ARM64 test APK was previously measured at approximately 222 MB; the under-100-MB target is not met while both native engines are bundled.

## Not implemented

Recorded in `docs/PHASE2_OPERA_FEATURES.md`; no fake toggles exist for these.

- QR-paired encrypted Nova-to-Nova Flow transfer (only the Android share sheet exists).
- Offline page snapshots and PDF export (the reading list stores links only).
- Data Saver compression and byte-saved accounting (image blocking only, GeckoView only).
- Voice input/output, native image generation, multi-tab AI comparison, Discover live scores and notifications.
- Screenshot annotation and per-site dark-mode overrides.
- Video pop-out beyond the fullscreen-only Picture-in-Picture gate.
- Cross-engine Back/Forward handoff (the Cefrium SDK does not expose the request method for history entries).
- Phishing/malware reputation protection is engine-scoped: GeckoView keeps native Safe Browsing lists active in every Nova Shield mode; Cefrium is explicitly marked **unprotected by this service** and the Shield status action offers a per-tab switch to GeckoView. The switch reloads the URL and does not migrate cookies, storage, or other website data. This is not a guarantee that every phishing or malware site will be detected.
- Fingerprinting protection is Aggressive-mode and Private-browsing only.

## Phase 1 exit criteria

- Main: Android build, unit tests, lint and Android 16 emulator smoke passed on `b2caaaf`.
- PR #31 passed build, unit tests, lint and emulator smoke before merge.
- PR #32 passed build, lint and emulator smoke on its pre-merge head; re-check after the main-into-branch merge. The Supabase SQL test in `supabase/tests/` is not run by CI and must be run manually against a test project.
- BrowserStack credentials parsed successfully, but the Home/Search App Automate upload returned HTTP 403 `BROWSERSTACK_TESTING_TIME_LIMIT_EXHAUSTED`. The Settings and Guest Tor workflows were cancelled after the same quota block was confirmed. No BrowserStack device session ran; this is a provider quota limitation, not a Nova test pass. BrowserStack UI tests do not prove network privacy.
- The Realme RMX5313 physical-device run has not been performed in this session.
- Before calling Nova release-ready: complete current PR checks; test on the target ARM64 phone; verify VPN/Tor IP/DNS/WebRTC/IPv6 behavior; complete two-device sync and live OTP checks; configure production signing; review APK size and release artifact.

## Scope decisions

- Do not add Opera-inspired features merely for feature parity. Finish, stabilize and verify Nova's existing product scope.
- Do not bypass engine capability or privacy guards to force Cefrium usage.
- Do not claim Tor anonymity, Tor Browser equivalence, full-device VPN, complete ad blocking, complete cookie isolation, or cross-engine website-data migration.
- A code path or passing JVM test is not equivalent to physical-device or security acceptance.
- User-installable extensions, user scripts and an add-on manager are intentionally outside Nova's product scope. Only the internal 1VPN and AI Reader bridges are retained.
