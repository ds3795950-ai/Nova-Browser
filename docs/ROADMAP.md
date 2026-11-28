# Nova Browser roadmap and verification status

Last reconciled: 2026-11-28. This document describes the current default `main` branch unless a pull request is named explicitly.

## Repository state

- Default branch: `main`.
- Current main commit: `390a7c495a75f6deabd5fc079e3964cb6168be22` — PR #25 merged, including engine-routing, Guest Tor and VPN fail-closed hardening.
- Main-branch Android build and lint checks passed on this commit.
- Only open PR at this reconciliation: [PR #27 — low-memory performance and device diagnostics](https://github.com/ds3795950-ai/Nova-Browser/pull/27).
- PR #27 head `c62c281231d5f559c4f26ba30a2ae6211072080a` fixes two Android instrumentation test compile errors (`Long` metrics initialized as `Int`). The previous run's build, lint and CodeRabbit review passed; emulator instrumentation failed at Kotlin test compilation. Fresh checks after the fix are pending. Do not merge until those checks complete.

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

- Downloads use engine-specific storage namespaces and include interrupted-download recovery. Partial-file reconciliation, process-death recovery, resume responses and engine-switch behavior still need extended Android tests.
- Cloud sync uses client-side encrypted payloads, per-engine profile separation, merge/tombstone handling and compare-and-swap conflict detection. Private and Guest data is excluded.
- Required acceptance: restore on two real devices; wrong-passphrase behavior; concurrent edits/conflict retry; deletion propagation; sign-out and account-deletion behavior. Do not mark multi-device sync fully accepted from unit tests alone.

## Authentication and release setup

**Code exists; owner-controlled setup and live acceptance remain.**

- Email OTP, encrypted local session storage, token refresh and sign-out are implemented. The Supabase dashboard/template/SMTP configuration was last documented as requiring owner setup; its live state has not been rechecked in this reconciliation.
- A verified sender domain and provider SMTP configuration may be required for reliable production OTP delivery. The project owner must validate actual delivery and expiry.
- Production signing requires `NOVA_RELEASE_KEYSTORE_BASE64`, `NOVA_RELEASE_STORE_PASSWORD`, `NOVA_RELEASE_KEY_ALIAS` and `NOVA_RELEASE_KEY_PASSWORD`. Do not put these secrets in source.
- The current dual-engine ARM64 test APK was previously measured at approximately 222 MB; the under-100-MB target is not met while both native engines are bundled.

## QA and remaining acceptance

- Main: Android build and lint passed on `390a7c4`.
- PR #27: unit/build and lint passed on its previous head; CodeRabbit review completed successfully. Emulator smoke failed because `CefriumEmulatorSmokeTest.kt` initialized two `Long` PSS metrics with `Int` literals. Fixed in `c62c281`; rerun pending.
- BrowserStack App Automate execution remains quota/credential dependent; the prior trial quota exhaustion prevented device runs. The Realme RMX5313 physical-device run has not been performed in this session.
- Before calling Nova release-ready: pass fresh PR #27 CI; pass Android 16 emulator instrumentation; test on the target ARM64 phone; verify VPN/Tor network behavior; complete two-device sync and live OTP checks; configure production signing; review APK size and release artifact.

## Scope decisions

- Do not add Opera-inspired features merely for feature parity. Finish, stabilize and verify Nova's existing product scope.
- Do not bypass engine capability or privacy guards to force Cefrium usage.
- Do not claim Tor anonymity, Tor Browser equivalence, full-device VPN, complete ad blocking, or cross-engine website-data migration.
- A code path or passing JVM test is not equivalent to physical-device or security acceptance.
