# Nova Browser email OTP setup

Nova requests Supabase email OTPs and verifies a six-digit token with `type=email`. The live project configuration is set to six digits with a 10-minute expiry.

## Required email template change

In Supabase Dashboard, open **Authentication → Email Templates → Magic Link** and replace the link-based template with:

```html
<h2>Your Nova Browser sign-in code</h2>
<p>Enter this one-time code in Nova Browser to finish signing in:</p>
<p style="font-size: 28px; font-weight: bold; letter-spacing: 6px;">{{ .Token }}</p>
<p>This code expires in 10 minutes and can only be used once.</p>
<p>If you did not request this code, you can ignore this email.</p>
```

The current deployed template uses `{{ .ConfirmationURL }}` instead of `{{ .Token }}`. The available Supabase management action can update OTP length and expiry but does not expose template HTML, so this one dashboard edit remains necessary for numeric-code sign-in.

## Email delivery

The project currently has no custom SMTP host configured. Supabase's default email service is intended for testing and applies recipient/rate restrictions. Configure a verified custom SMTP provider under **Project Settings → Authentication → SMTP Settings** before production use. Do not put SMTP credentials in the Android app or Git repository.

## Important: default SMTP restrictions

The live project has no custom SMTP host configured. Supabase's built-in email provider is limited to 2 emails per hour and only sends to addresses belonging to members of the Supabase organization. For normal users, configure a custom SMTP provider first. On newer Free-tier projects, Supabase also restricts editing auth email templates while using its default SMTP provider; connecting custom SMTP restores template customization. See Supabase's email rate-limit guidance and template customization change.

Nova's code and project OTP length/expiry are aligned, but a real numeric-code email flow cannot be declared complete until the Magic Link template uses `{{ .Token }}` and email delivery is tested through a working SMTP provider.
