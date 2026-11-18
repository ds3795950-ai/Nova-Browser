# Nova VPN: AdGuard TrustTunnel integration

## Selected approach

Use the open-source TrustTunnel protocol, originally developed by AdGuard VPN, with the official Android client adapter as the implementation reference.

- Protocol/server: https://github.com/TrustTunnel/TrustTunnel
- Android client adapter: https://github.com/TrustTunnel/TrustTunnelClient/tree/master/platform/android
- Official Flutter client (reference only): https://github.com/TrustTunnel/TrustTunnelFlutterClient
- License: Apache-2.0 for the TrustTunnel protocol/client repositories.

This replaces the earlier plan to use AdGuard's commercial white-label VPN SDK. Nova does not have partner SDK credentials, and the commercial AdGuard VPN server network is not available merely because the protocol is open source.

## What this choice means

TrustTunnel is self-hosted. Nova can ship the client protocol without an AdGuard partner account, but it still needs an endpoint that Nova owns or is authorized to use. There are no bundled public exit servers in the official self-hosted client.

A TrustTunnel endpoint requires a public IP or domain, a valid TLS certificate, server configuration, and client credentials/configuration. A server can optionally provide per-user subscription configuration. Nova must not invent or silently use a third-party endpoint.

The official TrustTunnel Android app is a standalone client for self-hosted endpoints. It is useful as a reference and a way to validate a server configuration, but launching it is not the same as embedding a VPN in Nova.

## Current Nova status

- AdGuard DNS-over-HTTPS is already implemented as a browser-scoped GeckoView setting (protected_dns_enabled).
- This DNS setting is not a VPN: it does not tunnel IP traffic, mask the user's public IP, or protect other apps.
- No TrustTunnel engine, Android VpnService, or VPN connection UI is integrated yet.
- No TrustTunnel server endpoint or client credentials have been provisioned.
- Do not display a VPN Connected state until the embedded TrustTunnel client reports an active tunnel and independent network checks confirm routing.

## Android integration requirements

The official Android adapter is a native Android library backed by JNI/native code, not a simple Kotlin-only dependency. Its current source build documents these prerequisites: Python 3.13+, CMake 3.24+, Conan 2.0.5+, Go 1.18.3+, Ninja 1.13+, Android SDK 35+, and Android NDK 29.0.14206865+.

The published Android AAR is hosted in GitHub Packages and currently requires package authentication in the tested download path. Do not add a dependency that makes public builds depend on a developer's personal GitHub token. Prefer a reproducible source build or an explicitly approved, reproducibly pinned distribution route.

The upstream Android API exposes a VpnClient backed by libtrusttunnel_android, a VpnService, configuration parsing, connection state callbacks, network-change notification, and a socket-protection callback. The app integration must:
1. Pin an upstream release/commit and verify its Apache-2.0 license and notices.
2. Build the native Android adapter reproducibly for Nova's supported ABIs.
3. Integrate the upstream service lifecycle and foreground notification correctly.
4. Ensure the tunnel's own transport sockets are protected from VPN recursion.
5. Ensure Nova browser traffic is routed through the tunnel. Audit upstream app-exclusion behavior; do not accidentally exclude the Nova package.
6. Parse/import tt:// configurations safely, validate endpoint/TLS settings, and store credentials using Android Keystore-backed encryption.
7. Request Android VPN consent using VpnService.prepare() and report the actual native state only.
8. Handle reconnects, network changes, process death, user disconnect, and Android 13+ foreground-service/notification rules.
9. Keep server credentials out of source control and APK build constants.

## Routing and privacy constraints

- Android permits one active VPN service per Android user/profile. TrustTunnel and Orbot cannot normally run as simultaneous VPNs. Nova must detect/explain this conflict and never silently claim both are active.
- Android VPN routing is user/profile-level, not browser-tab-level. Do not claim that only Guest Mode or only one tab is routed through the system VPN.
- Nova's existing Protected DNS is browser-scoped. When a VPN is connected, the VPN's configured DNS must be used for tunneled traffic; test for conflicts with GeckoView TRR.
- Do not advertise a kill switch until the implementation has a verified always-on/lockdown strategy and interruption tests.
- Do not claim anonymity. A VPN changes the network path and visible exit IP; it does not make browser fingerprints or signed-in accounts anonymous.

## Required verification before release

### Build and automated tests
- Build debug and release APKs for every shipped ABI.
- Unit-test TrustTunnel config parsing, secret storage, service state transitions, and error handling.
- Test VPN permission denial, cancellation, connect/disconnect, app restart, network switching, and process recreation.
- Run BrowserStack UI/lifecycle checks. BrowserStack UI tests do not prove VPN routing or leak resistance.

### Physical-device/network tests
- Verify the public IPv4 and IPv6 exit addresses while connected.
- Verify DNS resolver and DNS leak behavior.
- Verify WebRTC address exposure.
- Verify that tunnel interruption does not silently fall back if a kill switch is advertised.
- Verify that Nova traffic is included in the tunnel and the tunnel transport itself does not recurse.
- Verify conflict handling with Orbot and other VPN apps.

## Provisioning blocker

A functioning VPN cannot be tested until a TrustTunnel endpoint and a valid client configuration are available. The endpoint can be self-hosted or supplied by a provider that explicitly authorizes Nova to use it. Do not deploy paid infrastructure, create provider accounts, or claim a free public VPN service without explicit authorization and confirmed terms.
