# Nova Browser privacy and compatibility boundaries

## Nova Shield

Nova Shield uses GeckoView's built-in Enhanced Tracking Protection and Safe Browsing lists. Standard/Aggressive modes additionally enable cookie purging, private-mode query stripping and email-tracker protection; Aggressive enables strict social-tracker blocking. These controls block known trackers and some ad trackers, but they are **not** a complete EasyList/uBlock-style ad blocker and do not remove every ad or cosmetic page element.

## DNS and network routing

Nova applies AdGuard DNS-over-HTTPS in strict mode to GeckoView and Cefrium browser DNS. GeckoView uses TRR_MODE_ONLY; Cefrium uses the SDK Secure DNS mode. Direct DNS lookups do not fall back to unencrypted system DNS when the configured resolver is unavailable. Cefrium component updates are disabled because the SDK documents that they use a separate system network context outside a profile proxy.

Protected DNS is browser-only. It does not create a system VPN, hide the device IP address, or change DNS for other Android apps. When an authenticated browser proxy is active, that proxy may resolve the remote website hostnames instead of the local DoH resolver.

The existing 1VPN browser proxy is a GeckoView WebExtension. Its proxy selection and authentication are currently handled inside that extension. Nova has not yet connected the extension's authenticated credentials to Cefrium's native profile proxy API, so Cefrium 1VPN support is not implemented yet. Do not assume Cefrium traffic is routed through 1VPN until that integration and leak testing are complete.

## Guest Mode and Tor

Nova exposes its Orbot handoff only from Guest Mode settings. The Tor launcher checks that the active tab is a Guest tab before opening Orbot; it is not offered in normal or Private Mode. This is a user-interface and launch restriction, not a per-tab network isolation guarantee: Orbot's Android VPN routing is device/profile-level and can affect other apps unless Orbot's own app-selection mode is configured to include only Nova. Guest Mode remains an isolated, ephemeral GeckoView private session; it does not itself create a Tor circuit. The user must install and configure Orbot, enable its VPN mode, and select Nova in Orbot. Nova cannot verify the Tor circuit or guarantee fail-closed routing. Android Always-on VPN plus Block connections without VPN can provide OS-level fail-closed behavior, but should be configured and tested on-device.

## Browser VPN boundaries

Nova's current 1VPN feature is a browser proxy, not an Android VpnService and not a full-device VPN. It is currently provided through GeckoView's bundled WebExtension. A connected extension status does not imply that every Android app or every Cefrium request is routed through that proxy.

Cefrium's SDK exposes profile-level fixed proxy configuration and proxy-auth callbacks, but the current Nova build does not yet provide the required 1VPN credential hand-off to that callback. Cefrium VPN remains pending; Nova must not show it as connected or silently send traffic directly when a future proxy is enabled. A completed integration must also restrict non-proxied WebRTC traffic and test proxy failure, DNS and IP leakage.

## Extensions

Nova uses GeckoView WebExtensions. Firefox-compatible signed extensions that support the embedded GeckoView version may work; Chrome/Chromium extensions and desktop-only APIs are not guaranteed. The extension manager asks for required permissions and keeps private/Guest access disabled by default.

## Sync

Cloud sync is end-to-end encrypted with a passphrase that is not uploaded. Each device must use the same passphrase. Bookmark, workspace and saved-password deletions plus history-clear timestamps now merge across devices. Supabase updated_at triggers plus compare-and-swap writes detect simultaneous-device edits instead of silently overwriting a newer revision; retry Sync now after a conflict. Private/Guest data is excluded. Automatic sync remains opt-in and requires the encrypted passphrase to be stored locally under Android Keystore protection; the passphrase is never stored in plaintext or sent to Supabase.


## Cefrium settings compatibility — Phase 1 update

- Normal mode defaults to Cefrium with a named persistent profile.
- Private mode uses Cefrium's in-memory incognito profile when the current site does not require a Gecko-only feature. Private tabs share the Private profile, which is cleared when the final Private Cefrium browser closes.
- Guest mode remains GeckoView-only. Its engine and isolated Gecko context are unchanged.
- Protected DNS now applies to both engines. Cefrium uses strict Secure DNS; GeckoView uses TRR_MODE_ONLY. This is browser-only protection, not a full-device VPN.
- Desktop Site uses a Chromium desktop user agent plus a document-start viewport adjustment in Cefrium.
- Reader Mode extracts and sanitizes article content before displaying it in a simplified Cefrium reading page. Source scripts and event handlers are removed.
- Nova Shield and saved-password autofill remain GeckoView-only. When either is required, engine selection conservatively falls back to GeckoView.
- Cefrium component updates are disabled because the SDK documents that they do not use a profile proxy.
- 1VPN is still GeckoView-only. Its current WebExtension owns the authenticated proxy flow; the native credential hand-off required by Cefrium has not been implemented. No Cefrium VPN connection is claimed.

