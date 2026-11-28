# Nova Browser email OTP setup

## Live Supabase Auth configuration (verified 2026-11-28)

- Email sign-in: enabled
- OTP length: 6 digits
- OTP expiry: 600 seconds (10 minutes)
- Magic Link email template: contains `{{ .Token }}`, so Supabase sends a numeric OTP rather than only a confirmation link
- Custom SMTP: configured; port 465
- Sender display name: `Nova creator`

The SMTP hostname, username and password are intentionally not recorded here or in the Android repository. Keep those values in Supabase Dashboard settings only.

## Remaining acceptance

The live configuration is correct, but a real inbox delivery and verification test has not yet been performed in this session. Test both a new and existing account, confirm the six-digit code verifies with `type=email`, and confirm an expired code is rejected after 10 minutes. The email subject currently remains a generic sign-in subject; the body clearly identifies the Nova Browser verification code.
