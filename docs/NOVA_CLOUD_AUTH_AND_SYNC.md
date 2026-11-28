# Nova Cloud email OTP and encrypted sync

## Email OTP configuration

Nova uses the existing Supabase Auth project and its passwordless email OTP flow. The Android client calls Supabase Auth directly with the project URL and publishable key; no service-role key is shipped in the app.

In the Supabase Dashboard for the Nova Browser project:

1. Open **Authentication → Providers → Email** and ensure email sign-in is enabled.
2. Open **Authentication → Email Templates → Magic Link**. Replace the magic-link body with a numeric-code template. Supabase uses this template for both magic links and email OTPs:
   ```html
   <h2>Nova Browser sign-in code</h2>
   <p>Enter this six-digit code in Nova Browser:</p>
   <h1>{{ .Token }}</h1>
   <p>If you did not request this code, you can ignore this email.</p>
   ```
   The important variable is `{{ .Token }}`; do not use only `{{ .ConfirmationURL }}`.
3. The live project is already set to six digits and **600 seconds (10 minutes)**.
4. A custom SMTP host is already configured on port **465**, with sender name **Nova creator**. Keep SMTP credentials in the Supabase Dashboard only; never add them to the Android app, repository, or client build config.
5. The live settings were read and verified on 2026-11-28. Still test sending and verifying a code with a real inbox before calling delivery production-accepted.

Nova requests a six-digit code, verifies it with `type=email`, and stores the resulting session encrypted with AES-GCM using a non-exportable Android Keystore key. The refresh token is not stored in plaintext. Sign-out revokes the current session where possible and removes the local encrypted session file.

## Encrypted cloud sync design

- Browser snapshot and saved-password vault are encrypted on the device before upload.
- Encryption: AES-256-GCM with a fresh 96-bit IV and 128-bit authentication tag; key derivation: PBKDF2-HMAC-SHA256, 310,000 iterations and a per-backup 128-bit salt.
- The sync passphrase is never uploaded. Optional periodic sync stores it only as AES-GCM ciphertext protected by a non-exportable Android Keystore key.
- Each account is bound to its Supabase Auth user ID. Database RLS policies restrict reads, inserts, updates and deletes to `auth.uid() = user_id`.
- New uploads package the encrypted browser snapshot and encrypted password vault into one JSONB bundle in a single `nova_sync_state` row. The row is written with one compare-and-swap revision check, so the browser state and vault cannot be partially updated.
- The legacy two-table format remains read-compatible: clients continue reading `nova_encrypted_vault` and merge it with any bundled vault data. New clients do not write the legacy vault table. The security-invoker `nova_upload_sync_bundle` RPC updates only `nova_sync_state`; it never migrates or shares browser cookies, storage, or engine profiles.
- Included: bookmarks, normal browsing history (up to 500 restored entries), saved workspaces, selected browser settings and saved passwords.
- Excluded: Private Mode and Guest Mode data. Downloads remain device-local.
- Automatic sync is opt-in and scheduled every six hours when a network is available. Turning it off clears the locally stored auto-sync passphrase but does not delete the cloud backup.

The sync passphrase is required on each device. If it is lost, the encrypted cloud backup cannot be decrypted or recovered by Nova or Supabase.

## Verification checklist

- [ ] Save the numeric OTP template and SMTP settings in the Supabase Dashboard.
- [ ] Request and verify an OTP using a real email inbox.
- [ ] Confirm the session survives app restart and refreshes after access-token expiry.
- [ ] Sign in on a second Android device and decrypt/restore the same backup with the same passphrase.
- [ ] Confirm a wrong passphrase and modified ciphertext fail authentication.
- [ ] Confirm simultaneous-device updates return a conflict instead of silently overwriting a newer revision.
- [ ] Confirm Guest and Private data never enters the sync snapshot.
- [ ] Confirm auto-sync runs only after explicit opt-in and only for the account to which the local profile is bound.
