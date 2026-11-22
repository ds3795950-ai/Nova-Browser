# Nova Browser roadmap and verification status

Status key: **IMPLEMENTED + CI VERIFIED** means source is committed and the GitHub workflow passed; **DEVICE TEST REQUIRED** means real Android interaction remains unverified; **DASHBOARD SETUP REQUIRED** means a Supabase project setting must be completed by the project owner.

## Core browser and privacy

- GeckoView is the only browser engine; no Chromium or WebView fallback.
- Address/search, search provider selection, navigation, tabs, normal session restore, private tabs and isolated ephemeral Guest context.
- Bookmarks, history, downloads/uploads, workspaces, desktop-site mode, reader mode, translation, sharing and split-pane tab workflow.
- Nova Shield uses GeckoView native tracking protection, cookie isolation, Safe Browsing, GPC/fingerprinting controls and per-site permissions. Coverage depends on GeckoView's shipped lists; it is not a DNS firewall and does not guarantee every ad is blocked.
- ChatGPT, Claude and Perplexity handoff use official websites. Prompt content is user-entered; page URL context is opt-in; page content is not scraped or sent automatically.
- GeckoView WebExtension management and AES-GCM local password vault. Extension compatibility depends on the embedded GeckoView version. Private/Guest browsing does not save or retrieve passwords.
- Guest Mode is local ephemeral isolation and is not Tor by default. Optional external Orbot routing is user-configured; Nova can check current app egress with the Tor Project endpoint but cannot guarantee every request is routed or control Orbot's VPN state.

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
- Sync is user-initiated and merge-based. It includes bookmarks, normal browsing history, saved workspaces, selected preferences and saved passwords. Private/Guest tabs, private browsing state and Guest data are excluded. Current merge uses durable deletion tombstones for bookmarks, workspaces and saved-password records; history uses a clear timestamp. Tombstones merge by newest timestamp so older device snapshots do not resurrect deleted records. A deliberate re-add with a newer update timestamp is retained.
- Supabase stores only encrypted envelopes. The existing password vault remains protected locally by Android Keystore.
- Wrong passphrase/authentication-tag failure stops the sync rather than overwriting the cloud backup.

**Current limitation:** Sync is manual from Settings → Account & encrypted cloud sync. It is not a background/continuous sync service. Use the same passphrase on every device. The first device can upload; another device can restore/merge after signing in with the same email and passphrase.

## Phase 10 — Automated QA, security checks and release builds

- GitHub Actions verifies the dependency lockfile and bundled 1VPN extension, runs JVM unit tests, assembles debug and release APKs, and uploads artifacts. Android lint remains a separate follow-up check.
- Unit tests cover cloud-encryption round trip, ciphertext not matching plaintext, wrong-passphrase rejection/cache recovery, KDF validation, weak-passphrase rejection, malformed password-vault imports, credential tombstone merging, workspace bounds/tombstones, URL handling, AI routing and download protocols.
- CI uploads debug and unsigned release APK artifacts for inspection.
- Supabase security advisors returned no findings at the time of implementation; table policies were independently inspected.

**Device verification still required:** OTP email delivery and verification, session refresh after expiry, multi-device sync/restore, incorrect passphrase UX, password-vault migration and restore, Guest/Private exclusion, GeckoView extension prompts, Shield behavior and performance on the target Android device. Automated CI cannot substitute for hands-on Android testing.

## Build and security rules

- Never place Supabase service-role/secret keys in Android code, Gradle config or APKs. The publishable key is intentionally public and RLS is the security boundary.
- Never claim Guest Mode is Tor by default or anonymous. Only report Tor egress after the app-level check succeeds, and retain the limitation that this does not prove every tab/request was routed.
- Never silently replace an unreadable encrypted password vault or cloud backup.


## Architecture cleanup

**Partially implemented:** Bookmark and browsing-history persistence has been extracted from `MainActivity` into `NovaBrowserDataRepository`; deterministic JSON parsing, URL filtering, bounded bookmark metadata, and history-ID recovery live in the pure `NovaBrowserDataCodec` with JVM regression tests. The `BrowserTab` model and GeckoSession memory-budget/eviction behavior are now separated into `BrowserTab` and `NovaSessionBudgetController`. Browser chrome, home panel, toolbars, and split-pane view construction are separated into `NovaBrowserUiController`, with behavior injected through callbacks; `MainActivity` retains lifecycle integration, tab privacy gates, navigation orchestration, and UI feedback. The Activity is now approximately 2,526 lines; tab orchestration, extension delegates, settings, and cloud-auth UI remain follow-up decomposition work.
