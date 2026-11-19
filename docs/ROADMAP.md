# Nova Browser roadmap and verification status

Status key: **IMPLEMENTED + CI VERIFIED** means source is committed and the GitHub workflow passed; **DEVICE TEST REQUIRED** means real Android interaction remains unverified; **DASHBOARD SETUP REQUIRED** means a Supabase project setting must be completed by the project owner.

## Current dual-engine transition

- **Phase 1 branch:** adds Cefrium 0.21.0, a common `BrowserEngine` contract, GeckoView and Cefrium adapters, a debug-only Cefrium Engine Lab, and the Cefrium-compatible build toolchain.
- **Not yet implemented:** production tab-manager migration, Automatic/Cefrium/GeckoView user selector, unified cross-engine storage, session migration and embedded Tor.
- **Current production behavior:** `MainActivity` still renders normal, private and Guest tabs with GeckoView. The Cefrium Engine Lab is diagnostic only.
- **Verification:** CI and debug-device smoke testing are required before this foundation is considered verified. BrowserStack real-device testing remains blocked until App Automate access is restored.

### Phase 2 — engine selection and tab migration foundation

- **Committed in this continuation:** pure engine-selection policy for Automatic / Cefrium / GeckoView, Automatic-mode site-affinity precedence, Cefrium-unavailable/incompatible fallback, and the Guest GeckoView default with explicit Guest Cefrium override.
- **Committed:** profile-lifetime policy that reserves a persistent Cefrium profile for Normal mode and requires ephemeral engine profiles for Private and Guest.
- **Not yet production-integrated:** `MainActivity` still owns `GeckoSession` directly; the engine-neutral tab manager, UI selector, Cefrium tab rendering in the production tab strip, Gecko-only feature delegation, and full navigation/permission/download/extension parity remain outstanding. Do not expose the selector as an operational production setting until that migration and regression tests land.

### Phase 3 — shared data and cross-engine migration foundation

- **Committed in this continuation:** exact-host site-engine affinity persistence for Normal mode only, plus a consent-gated migration coordinator contract for portable cookies, localStorage and adapter-provided IndexedDB payloads.
- **Safety invariant:** source engine data is never deleted by migration; destination import must verify before the coordinator reports success. Guest/Private affinity is never persisted.
- **Not yet production-integrated:** concrete GeckoView/Cefrium export/import adapters, browser-level tab/session/history/bookmark/password/permission/download repositories wired to both engines, identity-provider related-domain affinity, and end-to-end migration verification remain outstanding. Service workers, Cache Storage, sessionStorage, passkeys and device-bound credentials are not promised to migrate.

## Core browser and privacy

- GeckoView is the only browser engine; no Chromium or WebView fallback.
- Address/search, search provider selection, navigation, tabs, normal session restore, private tabs and isolated ephemeral Guest context.
- Bookmarks, history, downloads/uploads, workspaces, desktop-site mode, reader mode, translation, sharing and split-pane tab workflow.
- Nova Shield uses GeckoView native tracking protection, cookie isolation, Safe Browsing, GPC/fingerprinting controls and per-site permissions. Coverage depends on GeckoView's shipped lists; it is not a DNS firewall and does not guarantee every ad is blocked.
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
