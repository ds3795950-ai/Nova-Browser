# Nova Guest Mode Tor routing

Guest Mode starts the embedded Guardian Project `tor-android` service inside Nova. It does not launch Orbot and does not create Android's system-wide VPN. The service exposes a loopback SOCKS listener; Nova's bundled GeckoView proxy extension routes Guest browsing through SOCKS5 with remote DNS enabled.

## Mandatory startup gate

Nova does not create or navigate a Guest tab until all of these conditions are true:

1. The embedded Tor daemon reports that its first Tor circuit is established.
2. Tor exposes a valid local SOCKS port.
3. The GeckoView proxy extension acknowledges the Tor route.
4. WebRTC peer connections are disabled while Guest Tor is active.

If any step fails or the proxy bridge disconnects, Nova blocks Guest startup or closes the active Guest session. It never switches Guest traffic to a direct connection. Tor takes precedence over 1VPN while the Tor Guest route is active; 1VPN settings are restored when Guest Mode ends.

## Scope and limitations

Guest tabs are fixed to GeckoView. The Tor proxy listener is attached to Nova's GeckoView runtime, so GeckoView requests are routed through Tor while Guest Mode is active; Cefrium/Normal-engine traffic is not switched to Tor. Existing GeckoView sessions share the runtime proxy setting while Guest Mode is active. Tor hides the device IP from destination sites but does not make a user immune to browser fingerprinting, malicious downloads, account-based identification, or traffic-correlation attacks. Avoid signing into identifying accounts and do not treat Tor as a guarantee of perfect anonymity.

## Verification

`GuestTorRoutingSmokeTest` starts the embedded daemon on an Android device/emulator, waits for a circuit, and checks `https://check.torproject.org/api/ip` through its SOCKS listener. This validates the bundled daemon and Tor exit path. Runtime UI testing must additionally confirm that the GeckoView proxy extension reports its Tor route ready and that Guest navigation is blocked when Tor is unavailable.
