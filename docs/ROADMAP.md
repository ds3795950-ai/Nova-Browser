# Nova Browser roadmap and verification status

Last reconciled: 2026-11-28 14:08 UTC. This document describes the current default `main` branch and separately identifies work under review.

## Repository state

- Default branch: `main`.
- Current main commit: `b2caaaf8438f3c3a4c002fc797bb19743b6a4c24`. Its ancestry includes PR #25 engine-routing/Guest Tor hardening and PR #27 low-memory performance diagnostics.
- Main-branch Android build, unit tests, lint and Android 16 emulator smoke passed on the current main commit.
- [PR #27](https://github.com/ds3795950-ai/Nova-Browser/pull/27) is merged.
- [PR #31 — Cefrium privacy and capability fallbacks](https://github.com/ds3795950-ai/Nova-Browser/pull/31) is open. Latest head `444afff5f0e621e7c8128d3a781202cd7e2c3b47`; the final global-image-default fallback review finding has been fixed and fresh CI/CodeRabbit validation is pending.
- [PR #32 — atomic encrypted cloud sync](https://github.com/ds3795950-ai/Nova-Browser/pull/32) is open. It writes the browser snapshot and password vault as one CAS bundle and retains legacy two-table read compatibility; CI and CodeRabbit review are pending.
- PR #29 remains closed because it would restore the removed user-installable add-on system.

## Engine integration and feature coverage

**Implemented in source; physical-device acceptance remains open.**

- Eligible Normal tabs prefer Cefrium; GeckoView remains the fallback when a required capability is unsupported or a protected route requires it.
- Private and Guest tabs are GeckoView-only and use separate ephemeral contexts. Cefrium and GeckoView persistent profiles remain isolated; website data is not migrated across engines.
- The shared per-tab engine interface and adapters are present. Some app-level GeckoView delegates and feature routing still live in `MainActivity`; a full extraction is a maintainability task, not a missing user-facing browser feature.
- Nova Shield, saved-password autofill and translation remain GeckoView-dependent. Cefrium capability routing must continue to fall back rather than silently dropping protections.
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
- PR #32 fixes a partial-write hazard: new clients now bundle the browser snapshot and password vault into one `nova_sync_state` row and perform one CAS write. Legacy `nova_encrypted_vault` data remains readable and is merged, but new clients no longer write the legacy table.
- Live Supabase project check (2026-11-28): project is `ACTIVE_HEALTHY`, Postgres 17.11.0.019. Both sync tables have RLS enabled; authenticated SELECT/INSERT/UPDATE/DELETE privileges are present with owner-scoped policies and UPDATE `WITH CHECK`; anon SELECT is denied. Security and performance advisors returned no lints. Each table has one updated-at trigger. Both tables currently contain zero rows, so no real account backup has yet been proven.
- Required acceptance: restore on two real devices; wrong-passphrase behavior; concurrent edits/conflict retry; deletion propagation; sign-out and account-deletion behavior. Do not mark multi-device sync fully accepted from unit tests alone.

## Authentication and release setup

**Code exists; owner-controlled setup and live acceptance remain.**

- Email OTP, encrypted local session storage, token refresh and sign-out are implemented. Live Supabase Auth settings were read on 2026-11-28: email sign-in enabled, six-digit OTP, 600-second expiry, Magic Link template contains `{{ .Token }}`, and a custom SMTP host is configured on port 465 with sender name `Nova creator`. No SMTP hostname or credentials are included in repository docs.
- Remaining acceptance is an actual send/verify/expiry test using a real inbox. The project settings are correctly configured, but delivery to a user's inbox has not been exercised in this session.
- Production signing requires `NOVA_RELEASE_KEYSTORE_BASE64`, `NOVA_RELEASE_STORE_PASSWORD`, `NOVA_RELEASE_KEY_ALIAS` and `NOVA_RELEASE_KEY_PASSWORD`. Do not put these secrets in source.
- The current dual-engine ARM64 test APK was previously measured at approximately 222 MB; the under-100-MB target is not met while both native engines are bundled.

## QA and remaining acceptance

- Main: Android build, unit tests, lint and Android 16 emulator smoke passed on `b2caaaf`.
- PR #31: previous head `2b383d0` passed build/unit tests, lint and emulator smoke. Latest head `444afff` fixes the final reviewed worker-image fallback issue; fresh checks are still running.
- PR #32: CI is running for the atomic sync change; CodeRabbit review is in progress.
- BrowserStack credentials parsed successfully, but the Home/Search App Automate upload returned HTTP 403 `BROWSERSTACK_TESTING_TIME_LIMIT_EXHAUSTED`. The Settings and Guest Tor workflows were cancelled after the same quota block was confirmed. No BrowserStack device session ran; this is a provider quota limitation, not a Nova test pass. BrowserStack UI tests do not prove network privacy.
- The Realme RMX5313 physical-device run has not been performed in this session.
- Before calling Nova release-ready: complete current PR checks; test on the target ARM64 phone; verify VPN/Tor IP/DNS/WebRTC/IPv6 behavior; complete two-device sync and live OTP checks; configure production signing; review APK size and release artifact.

## Scope decisions

- Do not add Opera-inspired features merely for feature parity. Finish, stabilize and verify Nova's existing product scope.
- Do not bypass engine capability or privacy guards to force Cefrium usage.
- Do not claim Tor anonymity, Tor Browser equivalence, full-device VPN, complete ad blocking, or cross-engine website-data migration.
- A code path or passing JVM test is not equivalent to physical-device or security acceptance.
