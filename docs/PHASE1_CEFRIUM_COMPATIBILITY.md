# Nova Browser — Phase 1 Cefrium compatibility

Branch: feature/phase-1-dual-engine-foundation

## Engine assignments

| Mode | Engine | Profile |
|---|---|---|
| Normal | Cefrium | Persistent nova-normal-v1 |
| Private | GeckoView | Ephemeral Gecko private session |
| Guest | GeckoView | Existing isolated Guest context |

If Nova Shield or saved-password autofill is required, Cefrium is considered incompatible and GeckoView is used instead. Guest is always GeckoView.

## Phase 1 feature status

| Feature | Cefrium status |
|---|---|
| Protected DNS | Implemented through strict Cefrium Secure DNS |
| Desktop Site | Implemented through desktop UA and viewport handling |
| Reader Mode | Implemented through sanitized article extraction |
| Private browsing | Uses GeckoView private sessions; Cefrium is never selected |
| 1VPN browser proxy | Implemented in code — extension credentials are relayed to Cefrium in memory; device/network acceptance pending |

Cefrium component updates are disabled because the SDK uses a separate network context for those requests.

## 1VPN Cefrium adapter

The bundled 1VPN WebExtension remains the source of truth for proxy hosts and authentication. Its own `onAuthRequired` callback now forwards the resolved credentials and challenged proxy host to Nova's native bridge. A short-lived hidden Gecko session initiates one ordinary request to trigger that existing authentication path; it is closed after credentials arrive or the handshake times out. Nova does not embed or persist the proxy password.

Cefrium applies the returned HTTPS proxy to its persistent Normal profile only, answers only matching proxy-auth challenges, disables non-proxied WebRTC UDP, and uses a fixed proxy with no direct fallback. If the credential bridge is unavailable, Cefrium is assigned a fail-closed local proxy target and the UI reports that traffic is blocked. Private and Guest remain GeckoView-only; this Cefrium adapter does not establish their proxy state.

The VPN settings screen currently exposes the free 1VPN locations (Singapore, Amsterdam and Los Angeles). Premium credentials can be relayed if the existing extension has an authenticated session; the app does not create or store a separate 1VPN account.

## Remaining VPN verification

The build-time patch intentionally checks the upstream extension's auth callback shape. If 1VPN changes that callback, the build stops rather than silently shipping a broken or unauthenticated adapter. On-device tests must still verify a successful proxy handshake, location switching, proxy failure, WebRTC leak blocking, DNS behavior while proxied, and disabling the proxy.

## Acceptance checks still required

- Android build, lint and unit tests pass.
- Verify AdGuard DoH behavior from Cefrium and GeckoView.
- Verify Desktop Site on representative mobile/desktop websites.
- Verify Reader Mode on article pages, script-heavy pages, and pages without semantic article markup.
- Verify Private storage is separate from Normal and clears after the final Private browser closes.
- Verify Guest remains GeckoView and its storage lifecycle is unchanged.
- Complete real-device acceptance for the Cefrium 1VPN adapter: proxy authentication, fail-closed behavior, WebRTC and DNS/IP leak checks.
