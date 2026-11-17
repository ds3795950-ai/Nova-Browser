# AdGuard VPN integration plan

## Decision

Use AdGuard's official white-label VPN SDK as the preferred VPN provider for Nova Browser. Do not integrate Aether/AGPL code or GuardianConnect. Keep the existing Orbot handoff separate from the VPN provider.

Official provider references:
- https://adguard.com/en/partners/technology.html
- https://adguard-vpn.com/en/white-label-vpn.html
- https://adguard-vpn.com/en/blog/white-label-vpn-and-vpn-sdk-how-to-choose-a-provider.html

## Current implementation status

**Planning only — no AdGuard tunnel is integrated.** The repository currently has no AdGuard SDK dependency, VPN service, provider credentials, or verified connection-state implementation. Do not show a connected state or claim traffic is protected until the SDK reports a live tunnel and network tests confirm routing.

The previous `saharohan870-svg/Nova-Browser` repository could not be inspected during this migration because GitHub returned HTTP 403 (account suspended). This plan carries forward the AdGuard provider decision, not unverified source code from that repository.

## Provider access and account model

AdGuard describes its white-label VPN as a partner SDK backed by AdGuard's server network. The documented integration includes a client SDK and a server-side provisioning API. The Nova backend is expected to issue per-installation or per-user access tokens and keep provider credentials server-side.

Before implementation, AdGuard must confirm in writing:
1. SDK access and Android sample/documentation for this project.
2. Commercial terms, minimum commitments, free-tier availability, and whether an accountless end-user flow is permitted.
3. Whether anonymous installation-scoped tokens can be provisioned without requiring Nova users to create AdGuard accounts.
4. Token expiry, revocation, usage limits, server selection, and backend API requirements.
5. Supported Android API levels, ABI requirements, and SDK redistribution obligations.

A public SDK description is not proof that Nova has access or that end users can use the service for free. Do not embed partner secrets, API keys, or privileged provisioning credentials in the APK.

## Required Nova UX

- Provide independent VPN and Tor controls.
- VPN control must connect/disconnect only the AdGuard VPN session.
- Tor control remains a separate Guest Mode action and must not silently toggle VPN.
- Warn users before changing between VPN providers because Android allows only one active `VpnService` per Android user/profile.
- Never report VPN connected until the SDK confirms an active tunnel.
- Show explicit states: unavailable, permission required, connecting, connected, disconnecting, and error.
- If the tunnel drops, report the failure; only claim kill-switch protection after it is enabled and verified.
- Keep DNS configuration inside the VPN tunnel. Test DNS, IPv4, IPv6, and WebRTC leak behavior.

## Guest Mode and Tor isolation constraint

Android's VPN service is device/profile-level, not per-tab. AdGuard's app exclusions may be able to route Nova as an application, but that would still route all Nova traffic, not only Guest Mode. Therefore, do not claim that AdGuard VPN or Android VPN routing isolates Tor to Guest Mode.

Tor must remain available only through the Guest Mode UI, and the implementation must separately prove browser-level isolation before claiming that only Guest tabs use Tor. If this cannot be guaranteed with GeckoView's supported APIs, disclose the limitation rather than presenting a false privacy guarantee.

## Implementation gates

1. Obtain AdGuard SDK access, sample app, documentation, and written commercial/accountless-flow answers.
2. Review SDK license, redistribution terms, privacy requirements, Android compatibility, and security model.
3. Build a provider adapter around the SDK; keep SDK-specific code out of browser UI.
4. Implement backend token provisioning with server-only credentials and no privileged secrets in the APK.
5. Add VPN permission, lifecycle, connection-state, DNS, server-selection, and failure handling.
6. Verify kill switch and traffic routing on physical Android devices, including tunnel interruption and reconnect tests.
7. Run automated tests and BrowserStack checks for UI/lifecycle behavior. BrowserStack alone cannot prove real VPN routing, DNS leak protection, or kill-switch behavior; those require network-level tests on physical devices.

## Acceptance criteria

- A successful connection is backed by SDK-reported tunnel state and independent route verification.
- Disconnecting or killing the tunnel cannot silently fall back to unprotected traffic if kill switch is advertised.
- DNS requests use the configured in-tunnel resolver while connected.
- VPN and Tor controls remain independent and warn about the Android VPN slot conflict.
- No user-facing accountless/free claim is made until AdGuard confirms the exact entitlement model.
- No Guest-only Tor isolation claim is made until verified end-to-end.