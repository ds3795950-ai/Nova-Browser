# TrustTunnel Android implementation notes

## Target architecture

Nova is a Kotlin/Android GeckoView app. Keep the VPN engine behind a small provider boundary so the browser UI does not depend directly on JNI classes:

- TrustTunnelEngine: wraps the pinned upstream Android adapter and exposes a typed state stream (idle, permission-required, connecting, connected, disconnecting, error).
- TrustTunnelVpnService: uses the upstream service implementation and Android foreground-service lifecycle; no placeholder service or fake state.
- TrustTunnelConfigRepository: imports and validates official tt:// links or the documented server configuration format; encrypts credentials at rest with Android Keystore.
- VpnSettingsController: requests Android VPN consent, starts/stops the service, and presents only engine-confirmed state.
- UI: server label, connect/disconnect, current state, DNS resolver, and actionable errors. Do not show provider locations or speeds unless the endpoint actually supplies them.

## Upstream facts checked

- TrustTunnel is the Apache-2.0 open-source VPN protocol originally developed by AdGuard VPN.
- The official Android adapter lives in TrustTunnelClient/platform/android and is native/JNI-backed.
- The adapter build requires Python 3.13+, CMake 3.24+, Conan 2.0.5+, Go 1.18.3+, Ninja 1.13+, Android SDK 35+, and Android NDK 29.0.14206865+.
- Its Kotlin API includes VpnClient, VpnClientListener, VpnServiceConfig, and the upstream VpnService; VpnClient requires the trusttunnel_android native library and a socket-protection callback.
- The official TrustTunnel Flutter app is a standalone self-hosted VPN client, not a drop-in SDK for Nova's Kotlin app.
- The published Android AAR download tested through GitHub Packages returned 401, so it must not be assumed publicly resolvable by Nova's Gradle build.

## Implementation sequence

1. Pin an upstream TrustTunnelClient release/commit and review its license/notices.
2. Establish a reproducible native Android library build in CI; no personal package token or locally generated binary may be required by normal builds.
3. Integrate the upstream VPN service and JNI library. Audit app exclusions and ensure Nova itself is routed while the transport sockets are protected from recursion.
4. Add the config import, secure persistence, Android VPN consent, lifecycle, notifications, and truthful connection states.
5. Add DNS and routing tests, then verify on physical Android hardware.
6. Add BrowserStack UI tests after the native build succeeds.

## Not acceptable

- A fake toggle, fake Connected state, or hand-written placeholder VpnService with no TrustTunnel data plane.
- A dependency on an individual's GitHub Packages credentials.
- An undocumented public VPN endpoint or shared exit server.
- Claiming per-tab/Guest-only system VPN routing.
- Claiming a kill switch, no logs, anonymity, or leak protection without implementation evidence and tests.

## External prerequisite

The app cannot establish a real tunnel without a TrustTunnel endpoint and a valid client configuration. No endpoint has been provisioned for this repository yet.
