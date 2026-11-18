# Nova Firewall — TacUNS integration

Nova bundles the open-source TacUNS Personal Firewall engine and dashboard as an in-process Android library. The source is pinned as a Git submodule at `vendor/tacuns` (upstream commit `89acc4b8bab8dfecd4823ae8b1b314546f500eb2`). The upstream project is licensed under Apache-2.0; retain its `LICENSE` and `NOTICE` files and do not use the TacUNS name/logo as Nova branding.

## What this provides

- An on-device Android `VpnService` local tunnel; no external VPN app, account, login, or remote VPN server is required.
- DNS-based ad, tracker, and malicious-domain filtering; per-app network blocking; custom allow/block rules; and on-device activity logs.
- The upstream TacUNS dashboard opened from Nova Settings as a screen inside the Nova app package. Its launcher entry point is not exposed as a second app.

## Important limits

This is a **local firewall**, not a commercial remote VPN. It does not change the public IP address, provide country/server selection, or make traffic exit through a remote VPN server. DNS filtering cannot stop apps that connect directly to fixed IP addresses or use their own encrypted DNS. The upstream implementation documents IPv4-only tunnel behavior and requires Android 10 (API 29) or newer. Nova's minimum Android version is therefore API 29.

Android allows one active VPN service per user/profile. Enabling Nova Firewall conflicts with Orbot or any other VPN service; starting another VPN can stop Nova Firewall. Android's strict Private DNS mode is also incompatible with this firewall and is guarded by the upstream app.

The app requests `QUERY_ALL_PACKAGES` to provide the upstream per-app firewall controls. Distribution through Google Play may require a qualifying permitted-use declaration and review. Do not remove the disclosure or claim Play approval without it.

## Build

Clone Nova with submodules enabled (`git clone --recurse-submodules ...`). Gradle includes `:firewall`, whose source/resource/assets directories point to the pinned upstream submodule. The upstream source and licence remain unmodified; Nova supplies a library manifest that registers the service and dashboard without a second launcher activity.
