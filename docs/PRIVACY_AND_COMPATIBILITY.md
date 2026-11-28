# Nova Browser privacy and compatibility boundaries

## Engine selection and Nova Shield

Normal tabs prefer Cefrium, but engine selection is capability-gated. Nova Shield and saved-password autofill currently require GeckoView, so Normal tabs fall back to GeckoView whenever those protections or credentials are needed. Private and Guest tabs are always GeckoView-only and use ephemeral contexts. The Cefrium profile is persistent and reserved for Normal browsing.

Nova Shield uses GeckoView's built-in Enhanced Tracking Protection and Safe Browsing lists. Standard/Aggressive modes additionally enable cookie purging, private-mode query stripping and email-tracker protection; Aggressive enables strict social-tracker and fingerprinting protection. These controls block known trackers and some ad trackers, but they are **not** a complete EasyList/uBlock-style ad blocker and do not remove every ad or cosmetic page element. Safe Browsing remains active even when Shield is Off.

## Cefrium Normal-tab request controls

Cefrium 0.22.0 supports a conservative curated tracker-domain filter, image blocking for Data Saver/per-site image rules, and per-origin cookie send/store filtering for browser-owned requests. The tracker list is not a complete filter-list engine or full GeckoView Enhanced Tracking Protection.

**Cookie limitation:** Cefrium 0.22.0 does not invoke the cookie callbacks for profile-level requests that belong to no browser, including service-worker requests and their fetches. Nova labels the Cefrium cookie control "browser requests only" and does not claim complete cookie isolation or third-party cookie partitioning. A site may still use cookies from its service worker. Do not present this setting as a comprehensive cookie-blocking guarantee.

Explicit saved-login filling in Normal Cefrium tabs is manual, HTTPS-only, exact-origin and same-origin-form-action constrained, and requires device credential or biometric verification. Automatic password detection and save prompts remain GeckoView-only.

## Extension scope

Nova has no user-installable extension manager, user scripts or third-party add-on support. The only extension-backed components are Nova's internal 1VPN and AI Reader bridges; they are built-in browser infrastructure, not user-installable extensions.

## DNS and network routing

Nova applies AdGuard DNS-over-HTTPS in strict mode to GeckoView and Cefrium browser DNS. GeckoView uses TRR_MODE_ONLY; Cefrium uses the SDK Secure DNS mode. Direct DNS lookups do not fall back to unencrypted system DNS when the configured resolver is unavailable. Cefrium component updates are disabled because the SDK documents that they use a separate system network context outside a profile proxy.

Protected DNS is browser-only. It does not create a system VPN, hide the device IP address, or change DNS for other Android apps. When an authenticated browser proxy is active, that proxy may resolve the remote website hostnames instead of the local DoH resolver.

## 1VPN browser proxy

The bundled 1VPN proxy component is the source of truth for server selection and credentials. It is a browser proxy, not Android VpnService and not a full-device VPN. Its private GeckoView bridge registers a blocking `webRequest` traffic gate and waits for Nova's native routing configuration before releasing requests. The gate blocks subresources until configuration is ready and again if the native bridge disconnects. When 1VPN is enabled, a missing/failed proxy decision or a `DIRECT` result is replaced with a loopback fail-closed proxy; proxy authentication and WebRTC protection must be available before the bridge reports routing ready. MainActivity also closes live browser engines while routing is unverified and restores tabs only after protection is ready or the user turns 1VPN off.

Cefrium has a profile-level proxy adapter and in-memory credential bridge, but MainActivity currently routes Normal tabs to GeckoView while 1VPN is enabled. The Cefrium adapter is not a claim that all engines or all app traffic are protected. Proxy-server uptime, DNS, IPv4/IPv6, WebRTC, QUIC, WebSocket, downloads and tunnel-interruption behavior still require physical-device network verification. Do not claim a full-device VPN or independently audited anonymity.

## Guest Mode and Tor

Guest Mode uses the embedded Tor Android service and an internal GeckoView SOCKS5 proxy bridge. Guest startup waits for Tor circuit bootstrap, a valid local SOCKS port, proxy-bridge confirmation of remote-DNS SOCKS routing plus WebRTC protection, and the combined routing-ready acknowledgment (including 1VPN readiness when 1VPN is enabled). Loss of the Tor service or proxy bridge closes Guest Mode rather than intentionally falling back to DIRECT.

GeckoView's internal proxy bridge is runtime-wide, not a supported per-session proxy API. To reduce cross-profile exposure, Nova refuses to start Guest Tor while Private tabs are open, suspends every persistent Normal engine session before enabling the Guest circuit, and blocks all non-Guest tab creation/switching while the Tor proxy is starting, active or stopping. Normal tabs are reopened only after the proxy bridge explicitly acknowledges `torGuestMode=false`; if the bridge cannot confirm the reset, tabs remain paused. This is a strong runtime barrier but still needs concurrent-session and physical-device verification before Nova can claim a formally audited Guest-only Tor boundary. Do not claim Tor anonymity or equivalence to the Tor Browser.

## Sync

Cloud sync is end-to-end encrypted with a passphrase that is not uploaded. Each device must use the same passphrase. Bookmark, workspace and saved-password deletions plus history-clear timestamps merge across devices. Supabase updated_at triggers plus compare-and-swap writes detect simultaneous-device edits instead of silently overwriting a newer revision; retry Sync now after a conflict. Private/Guest data is excluded. Automatic sync remains opt-in and requires the encrypted passphrase to be stored locally under Android Keystore protection; the passphrase is never stored in plaintext or sent to Supabase.

## Verification still required

- Measure Cefrium memory, startup and per-ABI APK/AAB size on ARM64 hardware before making it the unconditional default.
- Verify Cefrium renderer recovery, activity pause/resume and feature fallbacks on physical devices.
- Test 1VPN proxy failure, DNS, WebRTC and IPv4/IPv6 leakage with the bundled proxy component on physical Android devices.
- Test Guest Tor while Normal Cefrium tabs exist, reject Private-tab coexistence, and verify Normal GeckoView sessions restore after Guest exit.
- BrowserStack UI tests are useful for regression but do not prove VPN/Tor routing or leak resistance.

Guest Tor shutdown is confirmed only by a request-scoped acknowledgement from the currently connected proxy bridge. Every native proxy configuration carries a monotonically increasing request ID; the bridge echoes that ID after applying the requested VPN/Tor mode. Nova ignores stale IDs. The Tor-disabled acknowledgement is independent of 1VPN readiness so Nova can start the 1VPN credential bootstrap after Tor is off; normal browser navigation and session restoration remain blocked until 1VPN separately confirms its protected route. If a reset acknowledgement times out, a subsequent stop request sends a fresh request ID and retries; the runtime barrier is never released just because a timeout elapsed. Engine and Gecko-session factories also enforce the Guest Tor and 1VPN readiness barriers, so UI or asynchronous callbacks cannot bypass them.
