# Nova Browser release signing

The GitHub Actions workflow signs the release APK only when all four repository Actions secrets are present:

- `NOVA_RELEASE_KEYSTORE_BASE64`: Base64-encoded Android upload/release keystore (`.jks`)
- `NOVA_RELEASE_STORE_PASSWORD`
- `NOVA_RELEASE_KEY_ALIAS`
- `NOVA_RELEASE_KEY_PASSWORD`

Create the keystore locally and store its Base64 contents only in the private repository's Actions secrets. Never commit the keystore, passwords, or signing key. If any secret is missing, CI intentionally produces an unsigned release APK for development/testing. A signed APK is still not a Play Store release until application ID, versioning, policy declarations, Play App Signing, and device QA are completed.
