# Nova Browser roadmap and verification status

Status key: **IMPLEMENTED + CI VERIFIED** means source is committed and the GitHub workflow passed; **DEVICE TEST REQUIRED** means real Android interaction remains unverified; **DASHBOARD SETUP REQUIRED** means a Supabase project setting must be completed by the project owner.

## Current dual-engine transition

- **Active branch:** `feature/phase-1-dual-engine-foundation`; Cefrium SDK 0.21.0 is integrated alongside GeckoView.
- **Production behavior:** compatible Normal tabs can use Cefrium or GeckoView through Automatic/Cefrium/GeckoView settings. Automatic prefers Cefrium and falls back to GeckoView when Cefrium is unavailable or a Gecko-only protection/autofill feature is required.
- **Per-tab switching:** Normal tabs can be explicitly reloaded in the other engine after a warning. Website cookies, storage and login sessions are not transferred; the source engine's profile remains intact. Switching to Cefrium is blocked when Nova Shield, Protected DNS, desktop-site mode, Reader view or saved-login autofill requires GeckoView.
- **Integrated Cefrium features:** normal-tab navigation/lifecycle, secondary split-pane rendering, site permission prompts and completed-download publication into Nova's download repository.
- **Privacy boundary:** Private and Guest tabs remain on GeckoView. Cefrium's built-in incognito profile is shared, and named profiles are persistent until safely deleted; Nova does not enable Cefrium in those modes until independently erasable contexts can meet the privacy requirement.
- **Still outstanding:** native Cefrium equivalents for Nova Shield and Protected DNS, documented password save/fill integration, concrete cross-engine data export/import adapters, full engine-neutral tab ownership, and end-to-end device validation.
- **Verification:** the previous Android CI run passed on commit `189b9a0`; the lint run failed on a missing optional camera feature declaration, which was fixed in commit `6df5861`. New checks for the current branch head are running. BrowserStack real-device testing remains blocked because the trial quota expired.

### Phase 2 — engine selection and production integration

- **Implemented:** common engine contract and GeckoView/Cefrium adapters; Automatic/Cefrium/GeckoView selector; exact-host site affinity; safe fallback; explicit per-tab switching with a data-loss warning; Cefrium normal-tab navigation, lifecycle, split-pane, permissions and downloads.
- **Not enabled by design:** Cefrium Private/Guest contexts, because the SDK's built-in incognito profile is shared and named profiles do not guarantee immediate erasure during a running process. GeckoView remains the privacy-mode engine.
- **Gecko-only routes:** Nova Shield, Protected DNS, desktop-site mode, Reader view and saved-login autofill continue to use GeckoView. Do not bypass these guards just to increase Cefrium coverage.
- **Remaining:** native Cefrium protection equivalents, a supported password/autofill delegate, more complete engine-neutral tab ownership, crash/lifecycle regression tests and physical-device acceptance.

### Phase 3 — shared data and cross-engine migration

- **Implemented foundation:** app-level tabs/session metadata, history, bookmarks and download records are shared through Nova-owned storage; exact-host engine affinity is stored only for Normal mode.
- **Migration contract:** requires explicit consent, destination verification and preserves source data. It is not a full end-to-end migration implementation.
- **Hard limitation:** GeckoView's documented public storage API does not provide general cookie/storage export. HttpOnly/partitioned cookies, service workers, Cache Storage, sessionStorage, passkeys and device-bound credentials are not guaranteed portable. Users may need to reauthenticate after switching engines.
- **Remaining:** concrete export/import adapters where APIs permit, recovery UX for partial migration, more cross-engine integration tests and device acceptance.

## Core browser and privacy

- GeckoView is the privacy/protection fallback and the engine for Private/Guest tabs; Cefrium serves compatible Normal tabs.
- Address/search, search provider selection, navigation, tabs, normal session restore, private tabs and isolated ephemeral Guest context.
- Bookmarks, history, downloads/uploads, workspaces, desktop-site mode, reader mode, translation, sharing and split-pane tab workflow.
- Nova Shield currently uses GeckoView native tracking protection, cookie isolation, Safe Browsing, GPC/fingerprinting controls and per-site permissions. Cefrium is not selected while these protections are enabled.
- ChatGPT, Claude and Perplexity handoff use official websites. Prompt content is user-entered; page URL context is opt-in; page content is not scraped or sent automatically.
- GeckoView WebExtension management and AES-GCM local password vault. Extension compatibility depends on the embedded GeckoView version. Private/Guest browsing does not save or retrieve passwords.
- Guest Mode is local ephemeral isolation, not Tor routing or Tor-level anonymity.

## Phase 8 — Supabase email OTP authentication

**Implemented:**
- Supabase Auth passwordless email OTP send and verify flow.
- New-user creation is enabled by the Auth OTP request; no password is stored by Nova.
- Access/refresh session is encrypted with AES-GCM using a non-exportable Android Keystore key and stored in no-backup app storage.
- Expiring access tokens are refreshed through Supabase Auth; sign-out clears the local session.
- The app embeds only the Supabase publishable key; no service-role/secret key is included.

**Dashboard setup still required:** Supabase email OTP shares the Magic Link email template. In Supabase Dashboard → Authentication → Email Templates → Magic Link, make the template display `{{ .Token }}` so users receive the numeric code Nova asks them to enter. Confirm email provider delivery and Auth rate limits are configured for production. Nova's client-side code is ready, but an actual email delivery/login cannot be proven without a user email and the dashboard template setting.

## Phase 9 — Opt-in encrypted cloud sync

**Implemented:**
- Uses the existing `nova_sync_state` and `nova_encrypted_vault` tables in the Nova Browser Supabase project.
- Both tables have RLS enabled. SELECT/INSERT/UPDATE/DELETE policies are scoped to `auth.uid() = user_id`; UPDATE includes both USING and WITH CHECK.
- Client encrypts the sync snapshot and password-vault payload separately with AES-256-GCM. Keys are derived from a user-chosen passphrase using PBKDF2-HMAC-SHA256 (310,000 iterations), with random per-payload salt and IV.
- Passphrase is not stored or sent to Supabase. Losing it means the cloud backup cannot be decrypted.
- Sync is user-initiated and merge-based. It includes bookmarks, normal browsing history, saved workspaces, selected preferences and saved passwords. Private/Guest tabs, private browsing state and Guest data are excluded. The current merge is additive: deleting an item on one device does not create a cloud tombstone, so that item may be restored from another device's backup on the next merge.
- Supabase stores only encrypted envelopes. The existing password vault remains protected locally by Android Keystore.
- Wrong passphrase/authentication-tag failure stops the sync rather than overwriting the cloud backup.

**Current limitation:** Sync is manual from Settings → Account & encrypted cloud sync. It is not a background/continuous sync service. Use the same passphrase on every device. The first device can upload; another device can restore/merge after signing in with the same email and passphrase.

## Phase 10 — Automated QA, security checks and release builds

- GitHub Actions runs JVM unit tests, debug APK assembly and unsigned release APK assembly. Android lint was removed from the blocking pipeline after the Gradle lint task stalled; it remains a separate follow-up check.
- Unit tests cover cloud-encryption round trip, ciphertext not matching plaintext, wrong-passphrase rejection and weak-passphrase rejection, alongside existing URL, AI-routing and download-protocol tests.
- CI uploads debug and unsigned release APK artifacts for inspection.
- Supabase security advisors returned no findings at the time of implementation; table policies were independently inspected.

**Device verification still required:** OTP email delivery and verification, session refresh after expiry, multi-device sync/restore, incorrect passphrase UX, password-vault migration and restore, Guest/Private exclusion, GeckoView extension prompts, Shield behavior and performance on the target Android device. Automated CI cannot substitute for hands-on Android testing.

## Build and security rules

- Never place Supabase service-role/secret keys in Android code, Gradle config or APKs. The publishable key is intentionally public and RLS is the security boundary.
- Never claim Guest Mode is Tor or anonymous unless a separately audited Tor transport is implemented.
- Never silently replace an unreadable encrypted password vault or cloud backup.
