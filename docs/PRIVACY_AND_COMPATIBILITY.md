# Nova Browser privacy and compatibility boundaries

## Nova Shield

Nova Shield uses GeckoView's built-in Enhanced Tracking Protection and Safe Browsing lists. Standard/Aggressive modes additionally enable cookie purging, private-mode query stripping and email-tracker protection; Aggressive enables strict social-tracker blocking. These controls block known trackers and some ad trackers, but they are **not** a complete EasyList/uBlock-style ad blocker and do not remove every ad or cosmetic page element.

## Guest Mode and Tor

Guest Mode is an isolated, ephemeral GeckoView private session. It clears its session context when closed/restarted, but it does **not** route traffic through Tor and does not provide Tor's multi-hop anonymity. Nova's current GeckoView integration has no supported first-party proxy controller for a Tor SOCKS route. The Shield dialog now offers an Orbot launcher; the user must enable Orbot VPN mode and include Nova in Orbot's app list. Android Always-on VPN plus Block connections without VPN can provide fail-closed system routing. Nova cannot independently verify the Tor circuit or enforce the VPN. Do not describe Guest Mode as Tor-level browsing.

## Extensions

Nova uses GeckoView WebExtensions. Firefox-compatible signed extensions that support the embedded GeckoView version may work; Chrome/Chromium extensions and desktop-only APIs are not guaranteed. The extension manager asks for required permissions and keeps private/Guest access disabled by default.

## Sync

Cloud sync is end-to-end encrypted with a passphrase that is not uploaded. Each device must use the same passphrase. Bookmark, workspace and saved-password deletions plus history-clear timestamps now merge across devices. Supabase updated_at triggers plus compare-and-swap writes detect simultaneous-device edits instead of silently overwriting a newer revision; retry Sync now after a conflict. Private/Guest data is excluded. Automatic sync remains opt-in and requires the encrypted passphrase to be stored locally under Android Keystore protection; the passphrase is never stored in plaintext or sent to Supabase.
