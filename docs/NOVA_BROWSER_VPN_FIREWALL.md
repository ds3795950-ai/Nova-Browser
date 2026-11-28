# Nova browser-only VPN and firewall

Nova exposes 1VPN under **Settings → VPN & Firewall**. The provider's official 1VPN browser proxy component is downloaded and bundled into the APK during the build, then registered as a private GeckoView component. It does not create Android's VpnService and does not route traffic from other Android apps.

## Free server catalogue

The provider's published free catalogue contains Singapore, Amsterdam and Los Angeles. India locations are marked premium. The provider advertises unlimited free traffic and no account requirement; Nova cannot guarantee provider uptime, speed or future plan terms.

## Controls and routing behavior

- **1VPN toggle:** enables/disables proxy routing for Nova browser requests in GeckoView.
- **Server selector:** selects Singapore, Amsterdam or Los Angeles.
- **Nova Firewall toggle:** controls GeckoView's native tracking/content protection. It blocks known tracking content in Nova only; it is not a packet firewall and does not affect other apps.

The bundled 1VPN proxy component waits for Nova's native configuration handshake before allowing browser requests. With 1VPN enabled, missing, failed or `DIRECT` proxy decisions are replaced by a loopback fail-closed proxy. The proxy component must also register a blocking `webRequest` traffic gate and confirm proxy authentication support and WebRTC protection before reporting routing ready. The gate blocks subresource requests until Nova configuration arrives and again if the native bridge disconnects. Nova also closes live browser engines while the toggle is enabled but routing readiness has not been confirmed, restoring tabs only after protection is ready or the user turns 1VPN off. This includes the case where the component cannot register its proxy listener. This prevents an intentional direct fallback in the proxy-component callback; it is not proof that every protocol or device network path is leak-free.

Cefrium has a profile proxy adapter, but Nova currently routes Normal tabs to GeckoView while 1VPN is enabled. Do not claim Cefrium or other Android apps are protected by the GeckoView internal proxy bridge. DNS, IPv4/IPv6, WebRTC, QUIC, downloads, WebSockets, service workers and tunnel interruption still require physical-device verification.

## Guest Tor

Guest Mode uses the embedded Tor service and the internal GeckoView SOCKS5 proxy bridge with remote DNS. Guest startup waits for Tor bootstrap, WebRTC protection, and the combined routing-ready acknowledgment (including 1VPN readiness when 1VPN is enabled). Because the internal proxy bridge is runtime-wide, Nova refuses Guest startup while Private tabs are open and suspends Normal GeckoView sessions until Guest exits. This is a mitigation, not a formal proof of per-session routing isolation; verify concurrent Normal Cefrium traffic and session restoration before making a Guest-only Tor guarantee.

## Provider and licensing

Upstream project: https://github.com/1vpn/browser-extension. The corresponding GPL-3.0 licence is bundled alongside the downloaded component. Provider privacy policy: https://1vpn.org/privacy-policy/.

1VPN is a third-party service. When enabled, the provider operates the proxy servers and may process destination connection metadata as described in its privacy policy. Do not treat the service as an independently audited or end-to-end anonymous network.

Guest Tor shutdown is confirmed only by a request-scoped acknowledgement from the currently connected proxy bridge. Every native proxy configuration carries a monotonically increasing request ID; the bridge echoes that ID after applying the requested VPN/Tor mode. Nova ignores stale IDs and keeps the Normal/Private session barrier closed until the latest Tor-disabled request is acknowledged with combined routing readiness. Engine and Gecko-session factories also enforce the Guest Tor and 1VPN readiness barriers, so UI or asynchronous callbacks cannot bypass them.
