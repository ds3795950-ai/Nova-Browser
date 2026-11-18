# Nova browser-only VPN and firewall

Nova exposes 1VPN under **Settings → VPN & Firewall**. The provider's official Firefox add-on is downloaded and bundled into the APK during the build, then registered as an internal GeckoView component. It is hidden from Nova's normal add-ons list. It does not create Android's VpnService and does not route traffic from other Android apps.

## Free server catalogue

The provider's published free catalogue contains Singapore, Amsterdam and Los Angeles. India locations are marked premium. The provider advertises unlimited free traffic and no account requirement; Nova cannot guarantee provider uptime, speed or future plan terms.

## Controls

- **1VPN toggle:** enables/disables proxy routing for Nova browser requests.
- **Server selector:** selects Singapore, Amsterdam or Los Angeles.
- **Nova Firewall toggle:** controls GeckoView's native tracking/content protection. It blocks known tracking content in Nova only; it is not a packet firewall and does not affect other apps.

The integration suppresses the provider's first-install tab. The Firefox proxy API can fall back to the browser's configured default route if all provider proxy endpoints fail; this integration therefore cannot guarantee fail-closed or leak-proof routing. WebRTC peer connections are disabled while enabled when GeckoView exposes the required privacy setting, then restored to their prior value. HTTP proxying, WebRTC, DNS, QUIC, downloads, WebSockets and service-worker behavior still require device-level verification.

## Provider and licensing

Upstream project: https://github.com/1vpn/browser-extension. The corresponding GPL-3.0 licence is bundled alongside the downloaded component. Provider privacy policy: https://1vpn.org/privacy-policy/.

1VPN is a third-party service. When enabled, the provider operates the proxy servers and may process destination connection metadata as described in its privacy policy. Do not treat the service as an independently audited or end-to-end anonymous network.
