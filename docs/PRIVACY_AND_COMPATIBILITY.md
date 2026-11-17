# Nova Browser privacy and compatibility boundaries

## Nova Shield

Nova Shield uses GeckoView's built-in Enhanced Tracking Protection and Safe Browsing lists. Standard/Aggressive modes additionally enable cookie purging, private-mode query stripping and email-tracker protection; Aggressive enables strict social-tracker blocking. These controls block known trackers and some ad trackers, but they are **not** a complete EasyList/uBlock-style ad blocker and do not remove every ad or cosmetic page element.

## DNS and network routing

Nova currently relies on Android/GeckoView system name resolution and network routing. It does not expose a browser-managed DNS-over-HTTPS or DNS-over-TLS provider setting, and the app does not implement its own DNS resolver. A successful DNS-over-HTTPS test to a public resolver checks that resolver endpoint separately; it does not mean Nova's normal page requests are forced through that resolver.

Nova does not implement an in-app VPN service or a VPN kill switch. The Shield menu only offers an explicit handoff to the separately installed Orbot Android app, which uses Android's system VPN facility. Nova cannot confirm that Orbot is connected, that Nova is selected in Orbot, that a Tor circuit is active, or that traffic is blocked when the VPN disconnects. Use Android Always-on VPN and Block connections without VPN for system-level fail-closed behavior, and verify the active route independently.

## Guest Mode and Tor

Nova exposes its Orbot handoff only from Guest Mode settings. The Tor launcher checks that the active tab is a Guest tab before opening Orbot; it is not offered in normal or Private Mode. This is a user-interface and launch restriction, not a per-tab network isolation guarantee: Orbot's Android VPN routing is device/profile-level and can affect other apps unless Orbot's own app-selection mode is configured to include only Nova. Guest Mode remains an isolated, ephemeral GeckoView private session; it does not itself create a Tor circuit. The user must install and configure Orbot, enable its VPN mode, and select Nova in Orbot. Nova cannot verify the Tor circuit or guarantee fail-closed routing. Android Always-on VPN plus Block connections without VPN can provide OS-level fail-closed behavior, but should be configured and tested on-device.

## Guardian VPN status

Nova does not yet contain a production Guardian VPN tunnel or an operational VPN on/off switch. GuardianConnect is an integration SDK, not a free VPN server network. A production integration requires Guardian's authorization, app credentials, and an approved subscriber/entitlement flow. Do not display a connected state until the SDK confirms an active tunnel. Guardian VPN and Orbot both use Android's VPN service slot and therefore cannot be active simultaneously for the same Android user/profile; switching modes must first disconnect the active tunnel and obtain user confirmation.

## Extensions

Nova uses GeckoView WebExtensions. Firefox-compatible signed extensions that support the embedded GeckoView version may work; Chrome/Chromium extensions and desktop-only APIs are not guaranteed. The extension manager asks for required permissions and keeps private/Guest access disabled by default.

## Sync

Cloud sync is end-to-end encrypted with a passphrase that is not uploaded. Each device must use the same passphrase. Bookmark, workspace and saved-password deletions plus history-clear timestamps now merge across devices. Supabase updated_at triggers plus compare-and-swap writes detect simultaneous-device edits instead of silently overwriting a newer revision; retry Sync now after a conflict. Private/Guest data is excluded. Automatic sync remains opt-in and requires the encrypted passphrase to be stored locally under Android Keystore protection; the passphrase is never stored in plaintext or sent to Supabase.
