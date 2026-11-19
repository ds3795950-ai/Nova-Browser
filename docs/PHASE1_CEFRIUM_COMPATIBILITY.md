# Nova Browser — Phase 1 Cefrium compatibility

Branch: feature/phase-1-dual-engine-foundation

## Engine assignments

| Mode | Engine | Profile |
|---|---|---|
| Normal | Cefrium | Persistent nova-normal-v1 |
| Private | Cefrium when compatible | In-memory incognito() profile |
| Guest | GeckoView | Existing isolated Guest context |

If Nova Shield or saved-password autofill is required, Cefrium is considered incompatible and GeckoView is used instead. Guest is always GeckoView.

## Phase 1 feature status

| Feature | Cefrium status |
|---|---|
| Protected DNS | Implemented through strict Cefrium Secure DNS |
| Desktop Site | Implemented through desktop UA and viewport handling |
| Reader Mode | Implemented through sanitized article extraction |
| Private browsing | Uses an in-memory Cefrium incognito profile |
| 1VPN browser proxy | PENDING — authenticated credential hand-off is not available yet |

Cefrium component updates are disabled because the SDK uses a separate network context for those requests.

## 1VPN integration blocker

The existing 1VPN implementation is a GeckoView WebExtension. It performs proxy selection and authentication internally. Cefrium exposes a fixed profile proxy and a proxy-auth callback, but Nova does not yet have a supported way to provide the extension's authenticated proxy credentials to that callback.

Do not embed copied shared credentials, use an unauthenticated proxy as a substitute, or report Cefrium as VPN-connected until a supported credential hand-off or approved provider API is implemented. A completed adapter must fail closed, restrict non-proxied WebRTC, and pass DNS/IP leak tests.

## Acceptance checks still required

- Android build, lint and unit tests pass.
- Verify AdGuard DoH behavior from Cefrium and GeckoView.
- Verify Desktop Site on representative mobile/desktop websites.
- Verify Reader Mode on article pages, script-heavy pages, and pages without semantic article markup.
- Verify Private storage is separate from Normal and clears after the final Private browser closes.
- Verify Guest remains GeckoView and its storage lifecycle is unchanged.
- Complete Cefrium 1VPN integration and test proxy authentication, fail-closed behavior, WebRTC and DNS/IP leaks before declaring Phase 1 complete.
