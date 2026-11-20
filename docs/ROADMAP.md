# Nova Browser roadmap and verification status

Last reconciled: 2026-11-20. This file describes the feature branch, not the default `main` branch.

Status key:
- **IMPLEMENTED + CI VERIFIED** — source is committed and the relevant GitHub Actions checks passed.
- **IMPLEMENTED; DEVICE TEST REQUIRED** — code exists, but Android interaction and privacy behavior still need device validation.
- **PARTIAL** — architecture or part of the feature exists; acceptance criteria are not yet met.
- **EXTERNAL SETUP REQUIRED** — completion depends on a project-owner dashboard, provider account, or service quota.

## Current repository state

- Default branch: `main`.
- Active implementation branch: `feature/phase-1-dual-engine-foundation`.
- Latest inspected commit: `5c83f759b5aeef9f310d04498070fcede1f1af96`.
- Pull request #1 is open and remains a draft. It has not been merged into `main`.
- On the inspected commit, **Android CI** and **Android Lint** passed.
- The Android Emulator Smoke workflow failed during emulator setup/ADB configuration after the emulator reported boot completion. The failure was an ADB broken-pipe/exit-224 error before the instrumentation test command ran; it is not evidence of an app test assertion failure. The workflow is being adjusted and must pass on a fresh run.
- BrowserStack workflows are present, but no BrowserStack run is claimed as completed.

## Phase 1 — dual-engine foundation

**Status: IMPLEMENTED; DEVICE TEST REQUIRED**

- Cefrium SDK 0.21.0 is integrated alongside GeckoView.
- A shared per-tab `BrowserEngine` contract and GeckoView/Cefrium adapters exist.
- A debug-only Cefrium Engine Lab and emulator smoke tests exist.
- Licensing, SDK compatibility, profile boundaries and Tor feasibility are documented.
- Production rollout remains incremental; the draft PR must be reviewed and its emulator/device checks must pass before Phase 1 is accepted.

## Phase 2 — engine selection and production integration

**Status: PARTIAL**

- Normal tabs default to Cefrium; Automatic and GeckoView choices are available.
- Automatic mode supports exact-host affinity and GeckoView fallback for unsupported features.
- Normal tabs can be switched explicitly after a warning that engine-native website data is not transferred.
- Private tabs use Cefrium's memory-only incognito profile; Guest tabs are locked to GeckoView and use their separate ephemeral context.
- Cefrium supports normal-tab navigation/lifecycle, split-pane rendering, permission prompts and completed-download publication.
- Protected DNS, desktop-site mode and Reader Mode have Cefrium paths; Nova Shield and saved-login autofill remain GeckoView-dependent.
- `MainActivity.BrowserTab` still retains Gecko-specific session state. The common interface is not yet the sole tab owner.
- Remaining: complete engine-neutral tab ownership, feature-capability routing, crash/lifecycle recovery tests, and device compatibility/performance acceptance. Do not bypass privacy or feature guards to increase Cefrium coverage.

## Phase 3 — shared data and cross-engine migration

**Status: PARTIAL**

- Nova-owned bookmarks, history, tabs/session metadata and download records are shared at the browser-app layer.
- Exact-host engine affinity is stored for Normal mode only.
- A consent-gated migration coordinator validates an exact web origin, imports and verifies portable data, and never deletes the source profile after a failure.
- The coordinator is a contract only: concrete GeckoView/Cefrium `WebsiteDataPort` adapters are not wired.
- GeckoView's documented public storage APIs do not provide general cookie and website-storage export. HttpOnly/partitioned cookies, service workers, Cache Storage, sessionStorage, passkeys and device-bound credentials cannot be promised portable.
- Remaining: implement adapters only for data exposed by supported APIs, add user-facing partial-migration/re-authentication recovery, and run integration/device tests. Do not claim lossless website-session migration.

## Phase 4 — Private and Guest privacy

**Status: IMPLEMENTED IN CODE; DEVICE TEST REQUIRED**

- Private tabs use Cefrium's in-memory incognito profile when Cefrium is selected and remain excluded from normal persistence.
- Guest tabs are GeckoView-only and use a dedicated isolated Gecko context. Guest tabs are excluded from normal tabs/session restore, history, bookmarks, workspaces and saved downloads.
- Guest context cleanup is designed to run at startup (to recover after process death), when the final Guest tab closes, and during Activity teardown. The context rotates for the next Guest session.
- Owner credential autofill callbacks are disabled while any Private or Guest tab exists.
- Guest Mode is local ephemeral isolation. It is **not Tor routing** and does not provide Tor anonymity.
- Remaining: verify storage cleanup, downloads, credentials, tab isolation and process-death recovery on supported Android versions, including Android 16. Do not describe Guest privacy as independently audited until those checks pass.

## VPN and Tor

- The bundled 1VPN integration is a browser proxy path; it is not an Android-wide VPN service.
- Cefrium's proxy path is designed to fail closed when credentials or the proxy bridge are unavailable. GeckoView uses the bundled extension path.
- The requested Guest-only Tor route is not implemented or verified. Do not label 1VPN as Tor, and do not claim Tor routing until a separately supported Tor transport is integrated, scoped only to Guest traffic, and tested for DNS/WebRTC/leak behavior.

## Core browser features

The codebase includes address/search and provider selection, tabs, normal session restore, bookmarks, history, downloads/uploads, workspaces, desktop-site mode, Reader Mode, translation, sharing, split-pane, Nova Shield, GeckoView extensions, an encrypted local password vault, and ChatGPT/Claude/Perplexity website handoff. Availability and parity vary by engine; GeckoView-only features must remain on GeckoView until equivalent support is implemented and tested.

## Phase 8 — Supabase email OTP authentication

**Status: IMPLEMENTED; EXTERNAL SETUP AND DEVICE TEST REQUIRED**

- Passwordless email OTP send/verify, encrypted local session storage using Android Keystore, token refresh and sign-out are implemented.
- The app contains only the public/publishable key; no service-role secret belongs in the Android app.
- The Supabase Magic Link email template must display `{{ .Token }}` for the numeric OTP UI. A verified custom SMTP provider must be configured for normal delivery; the default provider has recipient and rate restrictions.
- Remaining: project owner configures the template and SMTP, then verifies real email delivery, OTP expiry, sign-in, refresh and sign-out on-device.

## Phase 9 — opt-in encrypted cloud sync

**Status: IMPLEMENTED; MULTI-DEVICE ACCEPTANCE REQUIRED**

- Sync snapshots and password-vault payloads are separately encrypted client-side with AES-256-GCM; passphrase-derived keys use PBKDF2-HMAC-SHA256.
- The passphrase is not stored or sent to Supabase. Supabase stores encrypted envelopes and row-level policies scope records to the authenticated user.
- Sync is manual, not continuous/background. It is additive: deletion tombstones are not implemented, so an item removed on one device may return from another device's snapshot.
- Private and Guest browsing state is excluded.
- Remaining: verify upload/restore across two devices, wrong-passphrase handling, conflicts, sign-out and account deletion. The project owner must provide the dashboard/device access needed for acceptance.

## Phase 10 — automated QA, security and release

**Status: PARTIAL**

- JVM unit tests, debug/release builds and lint run in GitHub Actions. Android CI and lint passed on the inspected branch head.
- The Android emulator smoke test must be rerun after the ADB setup reliability adjustment; the latest failure happened before instrumentation began.
- BrowserStack App Automate workflows exist for home/search, privacy/settings and VPN/firewall settings. They require valid BrowserStack credentials in the repository secret `ROHANSAHA_NGXOSY` and available App Automate quota. No run is claimed until the workflow returns a completed result with test artifacts.
- Remaining: passing emulator instrumentation, BrowserStack execution, Android 16 / target-device acceptance, OTP and two-device sync verification, privacy/security regression checks, and release APK review.

## Completion rules

- Keep implementation on the feature branch until the owner explicitly approves merging.
- Never mark a phase complete solely because its code compiles; required device, dashboard and service checks must pass.
- Never place Supabase service-role credentials, SMTP credentials or BrowserStack access keys in source code or logs.
- Never silently replace an unreadable local vault or encrypted cloud backup.
- Never claim Guest Mode is Tor or anonymous without a separately implemented and audited Tor transport.
