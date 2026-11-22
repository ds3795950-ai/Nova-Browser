# Nova Browser email OTP setup

Nova Browser signs users in with a six-digit Supabase email OTP (`type=email`). The Android client validates the address and code, persists the authenticated session with Android Keystore-backed AES-GCM encryption, and applies a 60-second local resend cooldown. The Supabase project is configured for six-digit codes with a 10-minute expiry.

## Required Supabase email template

The currently deployed template was previously found to use `{{ .ConfirmationURL }}` (a magic link). Nova's UI expects a numeric OTP, so the template must render `{{ .Token }}` instead.

In Supabase Dashboard, open **Authentication → Email Templates → Magic Link** and use:

```html
<h2>Your Nova Browser sign-in code</h2>
<p>Enter this one-time code in Nova Browser to finish signing in:</p>
<p style="font-size: 28px; font-weight: bold; letter-spacing: 6px;">{{ .Token }}</p>
<p>This code expires in 10 minutes and can only be used once.</p>
<p>If you did not request this code, you can ignore this email.</p>
```

Save the template, then send a test OTP and confirm that the email contains a six-digit code (not a confirmation link). The available Supabase management integration does not expose the live Auth email-template HTML, so this dashboard step cannot be performed or verified from the repository.

## Email delivery and SMTP

**Custom SMTP is optional for development and limited testing.** Nova does not need SMTP credentials or an SMTP client; it calls Supabase Auth over HTTPS. Supabase's built-in mail service can deliver test messages subject to its restrictions (including low rate limits and recipient restrictions; the current project setup notes a 2-email/hour limit and delivery only to addresses belonging to the Supabase organization).

For real users and production delivery, configure a verified SMTP provider in **Project Settings → Authentication → SMTP Settings** if Supabase's built-in mail restrictions do not meet your needs. This is a deployment choice, not an Android app dependency. Never place SMTP credentials in the APK, source code, or repository.

Some Supabase Free-tier projects restrict custom Auth email-template editing while using the built-in provider. If the Dashboard does not allow saving the `{{ .Token }}` template, use a supported SMTP provider or confirm the current project plan's template-editing rules with Supabase. Do not switch providers merely to satisfy the Android client; first check whether the built-in provider supports the required template and test recipient.

## Verification checklist

1. Confirm **Authentication → Providers → Email** is enabled.
2. Confirm OTP length is 6 and expiry is 600 seconds (10 minutes).
3. Confirm the Magic Link template contains `{{ .Token }}`, not only `{{ .ConfirmationURL }}`.
4. Send an OTP to an allowed test recipient and verify that the message arrives with a six-digit token.
5. Enter the token in Nova; confirm sign-in succeeds and the account email appears in Nova Cloud.
6. Try a wrong, expired, and reused token; each must be rejected without creating a local session.
7. Try requesting another code immediately; Nova enforces a 60-second cooldown and Supabase rate limits still apply.

## Client behavior

- Email addresses are trimmed and normalized to lowercase before requests.
- OTP input accepts at most six numeric characters; the client validates exactly six digits before verification.
- Resend requests are throttled locally for 60 seconds and Supabase 429 responses are handled with bounded retry behavior.
- Supabase error fields (`msg`, `message`, `error_description`, and `error`) are surfaced as short, readable errors.
- Authenticated sessions are stored locally using Android Keystore-backed encryption. No SMTP secret is stored on-device.

A successful build or unit test does not prove that Supabase delivered an email. Production readiness still requires the dashboard template check and an end-to-end delivery test to a real, permitted recipient.
