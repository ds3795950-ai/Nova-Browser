# Nova Browser roadmap and verification status

Last reconciled: 2026-11-22. This file describes the feature branch, not the default `main` branch.

Status key:
- **IMPLEMENTED + CI VERIFIED** — source is committed and the relevant GitHub Actions checks passed.
- **IMPLEMENTED; DEVICE TEST REQUIRED** — code exists, but Android interaction and privacy behavior still need device validation.
- **PARTIAL** — architecture or part of the feature exists; acceptance criteria are not yet met.
- **EXTERNAL SETUP REQUIRED** — completion depends on a project-owner dashboard, provider account, or service quota.

## Current repository state

- Default branch: `main`.
- Active implementation branch: `feature/phase-1-dual-engine-foundation`.
- Latest source-verification commit: `314c1077730728aed5a741fef390f4bff805d48f` (Cefrium permission-origin fail-closed policy, Private/Guest smoke-test correction and documentation alignment). A follow-up documentation commit is `7ed38716bc2ba71694f4b10c2013d56924709ef4`.
- Pull request #1 is open and remains a draft. It has not been merged into `main`.
- Android CI and Android Lint passed on source commit `314c1077730728aed5a741fef390f4bff805d48f`; Android 16 emulator smoke is still in progress. The URL/session-safety branch Android CI passed on `20753b646c1cf0462607136c9cabd81f608b6e79`; its Android 15 Appium UI run remains in progress.
- The Android Emulator Smoke workflow failed during emulator setup/ADB configuration after the emulator reported boot completion. The failure was an ADB broken-pipe/exit-224 error before the instrumentation test command ran; it is not evidence of an app test assertion failure. The workflow is being adjusted and must pass on a fresh run.
- BrowserStack workflows were executed; AAB builds and credential parsing passed, but App Automate upload returned HTTP 403 `BROWSERSTACK_TESTING_TIME_LIMIT_EXHAUSTED`. Updated workflows report this as a warning and skip device steps. No BrowserStack device result is claimed.

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
- Private and Guest tabs are both locked to GeckoView. Private uses a private session; Guest additionally uses a dedicated temporary context. Regression tests now enforce both selections regardless of Normal-mode preferences.
- Cefrium supports normal-tab navigation/lifecycle, split-pane rendering, permission prompts and completed-download publication.
- Protected DNS, desktop-site mode and Reader Mode have Cefrium paths; Nova Shield and saved-login autofill remain GeckoView-dependent.
- `MainActivity.BrowserTab` now owns the engine-neutral `BrowserEngine?` and does not retain a `GeckoSession`; Gecko-specific delegate installation and a narrow Gecko-session accessor remain in `MainActivity`.
- Remaining: complete engine-neutral tab ownership, feature-capability routing, crash/lifecycle recovery tests, and device compatibility/performance acceptance. Do not bypass privacy or feature guards to increase Cefrium coverage.

## Phase 3 — strict engine profile isolation

**Status: IMPLEMENTED IN SOURCE; CI AND DEVICE ACCEPTANCE REQUIRED**

- Cefrium and GeckoView have disjoint website profiles; no cookies, cache, localStorage, IndexedDB, service workers, permissions, credentials, passkeys or session state cross the engine boundary.
- Nova-owned history, bookmarks, folders/tombstones, workspaces, saved Normal tab metadata and downloads use engine-specific storage namespaces. Download preference records, partial files and public download folders are also separated.
- Bookmark backup files are schema-tagged with their engine and cross-engine or unscoped imports are rejected. History exports include engine provenance. Password vault UI is GeckoView-only. Private and Guest modes cannot read owner bookmarks, history, workspaces or downloads.
- Encrypted cloud sync stores and merges independent `GECKOVIEW` and `CEFRIUM` profile objects. Legacy unscoped cloud snapshots are not imported into either profile.
- `WebsiteDataMigrationCoordinator` contains no data ports and always blocks cross-engine migration.
- Unscoped legacy local records are preserved but quarantined because the owning engine cannot be reliably inferred.
- Remaining: run the latest Android CI, lint, Android 16 emulator and BrowserStack workflows; perform real-device profile-leak tests on Android 10+ and extended tab/download lifecycle tests.

## Phase 4 — Private and Guest privacy

**Status: IMPLEMENTED IN CODE; DEVICE TEST REQUIRED**

- Private tabs use GeckoView private sessions and remain excluded from normal persistence.
- Guest tabs are GeckoView-only and use a dedicated isolated Gecko context. Guest tabs are excluded from normal tabs/session restore, history, bookmarks, workspaces and saved downloads.
- Guest context cleanup is designed to run at startup (to recover after process death), when the final Guest tab closes, and during Activity teardown. The context rotates for the next Guest session.
- Owner credential autofill callbacks are disabled while any Private or Guest tab exists.
- Guest Mode is local ephemeral isolation. It is **not Tor routing** and does not provide Tor anonymity.
- Remaining: verify storage cleanup, downloads, credentials, tab isolation and process-death recovery on supported Android versions, including Android 16. Do not describe Guest privacy as independently audited until those checks pass.

## VPN and Tor

- The bundled 1VPN integration is a browser proxy path, not an Android-wide VPN service and not Tor.
- Cefrium's proxy path uses a fixed proxy and is designed to fail closed when credentials or the proxy bridge are unavailable. The GeckoView extension path has not passed equivalent fail-closed/leak testing.
- Guest-only Tor is not implemented or verified. Android app-wide VPN routing cannot isolate one Guest tab from Normal/Private tabs. GeckoView has no supported per-session proxy API; do not add a fake toggle or claim Tor routing until a dedicated transport and routing boundary are proven.

## Core browser features

The codebase includes address/search and provider selection, tabs, normal session restore, bookmarks, history, downloads/uploads, workspaces, desktop-site mode, Reader Mode, translation, sharing, split-pane, Nova Shield, GeckoView extensions, an encrypted local password vault, and ChatGPT/Claude/Perplexity website handoff. Availability and parity vary by engine; GeckoView-only features must remain on GeckoView until equivalent support is implemented and tested.

## Phase 8 — Supabase email OTP authentication

**Status: IMPLEMENTED; EXTERNAL SETUP AND DEVICE TEST REQUIRED**

- Passwordless email OTP send/verify, encrypted local session storage using Android Keystore, token refresh and sign-out are implemented.
- The app contains only the public/publishable key; no service-role secret belongs in the Android app.
- Live Auth configuration was inspected on 2026-11-21: email sign-in is enabled, OTP length is 6 and expiry is 600 seconds.
- The deployed Magic Link template still uses `{{ .ConfirmationURL }}`, not `{{ .Token }}`; the available Auth configuration action cannot update template HTML.
- No custom SMTP host is configured. SMTP credentials and a verified sender domain are still required for normal user delivery.
- Remaining: project owner configures the template and SMTP, then verifies real email delivery, OTP expiry, sign-in, refresh and sign-out on-device.

## Phase 9 — opt-in encrypted cloud sync

**Status: IMPLEMENTED; MULTI-DEVICE ACCEPTANCE REQUIRED**

- Sync snapshots and password-vault payloads are separately encrypted client-side with AES-256-GCM; passphrase-derived keys use PBKDF2-HMAC-SHA256.
- The passphrase is not stored or sent to Supabase. Supabase stores encrypted envelopes and row-level policies scope records to the authenticated user.
- Manual sync and opt-in six-hour WorkManager sync are implemented. Browser and password-vault ciphertext are stored in one atomic bundle revision; sync uses compare-and-swap to detect stale writes. Deletion propagation and merge behavior still require multi-device acceptance.
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


## Verification and integration snapshot (2026-11-22)

- Dual-engine Android CI: JVM tests, debug APK, unsigned release APK and artifact upload passed on `314c107`; Android Lint passed. Android 16 emulator smoke had not completed at the time of this snapshot.
- UI-polish branch: the code head `319005842` passed unit tests and debug/release builds. Its later workflow/documentation updates are being rechecked. The 50-item changelog records three intentional privacy/platform choices (local shortcut initials, local-only suggestions, native form dialogs), fold/hinge posture handling as not implemented, and TalkBack/contrast/200% font-scale behavior as requiring real-device accessibility verification.
- The UI-polish and dual-engine branches cannot be merged automatically: GitHub rejected the direct merge due to overlapping `MainActivity.kt`, manifest, downloads, sync and VPN changes. No integration merge was made.
- Release signing is blocked because no `NOVA_RELEASE_KEYSTORE_BASE64`, `NOVA_RELEASE_STORE_PASSWORD`, `NOVA_RELEASE_KEY_ALIAS` or `NOVA_RELEASE_KEY_PASSWORD` repository secrets are configured.
- Production OTP is blocked on the project owner's Magic Link template and custom SMTP configuration.
- Guest-only Tor remains unimplemented and is not represented as available.
- `main` remains unchanged; all implementation branches and pull requests remain separate and unmerged.
