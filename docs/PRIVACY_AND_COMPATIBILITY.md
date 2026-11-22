# Nova Browser privacy and compatibility boundaries

## Nova Shield

Nova Shield uses GeckoView's built-in Enhanced Tracking Protection and Safe Browsing lists. Standard/Aggressive modes additionally enable cookie purging, private-mode query stripping and email-tracker protection; Aggressive enables strict social-tracker blocking. These controls block known trackers and some ad trackers, but they are **not** a complete EasyList/uBlock-style ad blocker and do not remove every ad or cosmetic page element.

## DNS and network routing

Nova applies AdGuard DNS-over-HTTPS in strict mode to GeckoView and Cefrium browser DNS. GeckoView uses TRR_MODE_ONLY; Cefrium uses the SDK Secure DNS mode. Direct DNS lookups do not fall back to unencrypted system DNS when the configured resolver is unavailable. Cefrium component updates are disabled because the SDK documents that they use a separate system network context outside a profile proxy.

Protected DNS is browser-only. It does not create a system VPN, hide the device IP address, or change DNS for other Android apps. When an authenticated browser proxy is active, that proxy may resolve the remote website hostnames instead of the local DoH resolver.

The existing 1VPN browser proxy remains a GeckoView WebExtension and is the source of truth for host selection and credentials. Its own authentication callback now relays the challenged proxy host and resolved credentials through Nova's native bridge to Cefrium in memory. Cefrium uses a fixed profile proxy and disables non-proxied WebRTC UDP. Runtime proxy, DNS and IP-leak acceptance is still required; until then, do not treat a successful UI toggle as proof of a verified VPN tunnel.

## Guest Mode and Tor

Guest Mode currently displays an explanation that Guest-only Tor is unavailable. Nova does not launch or configure Orbot because Android VPN routing is app/profile-level and would affect Normal and Private tabs in the same app package. Guest Mode is an isolated, ephemeral GeckoView private session; it does not create a Tor circuit. A dedicated Guest-only Tor transport is not implemented. Do not claim Tor routing, Tor anonymity, or a kill switch.

## Browser VPN boundaries

Nova's current 1VPN feature is a browser proxy, not an Android VpnService and not a full-device VPN. It is currently provided through GeckoView's bundled WebExtension. A connected extension status does not imply that every Android app or every Cefrium request is routed through that proxy.

Cefrium uses its profile-level fixed HTTPS proxy and proxy-auth callback. The bundled 1VPN extension remains the credential source: its own auth callback relays the challenged proxy host and resolved credentials to Nova in memory. Cefrium restricts non-proxied WebRTC UDP and fixed-proxy requests have no direct fallback. If the bridge cannot provide credentials, the Cefrium profile remains on a local fail-closed proxy target. Runtime proxy, DNS and IP-leak acceptance still needs real-device verification.

## Extensions

Nova uses GeckoView WebExtensions. Firefox-compatible signed extensions that support the embedded GeckoView version may work; Chrome/Chromium extensions and desktop-only APIs are not guaranteed. The extension manager asks for required permissions and keeps private/Guest access disabled by default.

## Sync

Cloud sync is end-to-end encrypted with a passphrase that is not uploaded. Each device must use the same passphrase. Bookmark, workspace and saved-password deletions plus history-clear timestamps now merge across devices. Supabase updated_at triggers plus compare-and-swap writes detect simultaneous-device edits instead of silently overwriting a newer revision; retry Sync now after a conflict. Private/Guest data is excluded. Automatic sync remains opt-in and requires the encrypted passphrase to be stored locally under Android Keystore protection; the passphrase is never stored in plaintext or sent to Supabase.


## Cefrium settings compatibility — Phase 1 update

- Normal mode defaults to Cefrium with a named persistent profile.
- Private mode is GeckoView-only and uses ephemeral private sessions. It is excluded from normal persistence and cloud sync.
- Guest mode remains GeckoView-only. Its engine and isolated Gecko context are unchanged.
- Protected DNS now applies to both engines. Cefrium uses strict Secure DNS; GeckoView uses TRR_MODE_ONLY. This is browser-only protection, not a full-device VPN.
- Desktop Site uses a Chromium desktop user agent plus a document-start viewport adjustment in Cefrium.
- Reader Mode extracts and sanitizes article content before displaying it in a simplified Cefrium reading page. Source scripts and event handlers are removed.
- Nova Shield and saved-password autofill remain GeckoView-only. Normal tabs may fall back to GeckoView when these features are required; Private and Guest are always GeckoView.
- Cefrium component updates are disabled because the SDK documents that they do not use a profile proxy.
- 1VPN now has a Cefrium adapter: the GeckoView extension provides the authenticated proxy credentials through a native bridge, while Cefrium applies its own fixed profile proxy and WebRTC policy. Runtime connectivity and leak tests are still pending.

