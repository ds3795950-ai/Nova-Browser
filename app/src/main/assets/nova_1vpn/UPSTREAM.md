# 1VPN upstream attribution

Nova bundles the official 1VPN Firefox add-on from Mozilla Add-ons, pinned to version 4.2.2 and a SHA-256 digest in `app/build.gradle.kts`. The Gradle `prepare1VpnAssets` task verifies the archive before extracting it, patches its Gecko add-on ID, and injects Nova's native-message bridge.

Upstream source: https://github.com/1vpn/browser-extension
License: GNU General Public License v3.0. The upstream `LICENSE` and `NOTICE` are retained in this directory.

Only the upstream free locations exposed in Nova settings (Singapore, Amsterdam, and Los Angeles) are enabled by the native bridge. The feature uses the upstream public free-tier credentials and does not require a 1VPN user account.

The source-level `manifest.json` and `background.js` in this directory are reference-only and are not installed. The app installs only the checksum-verified generated package from `nova_1vpn_runtime`.
