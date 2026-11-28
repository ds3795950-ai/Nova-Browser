# Incomplete features status — replacement for PR #36

Branch: `feature/complete-incomplete-features` (replacement work based on current `main`).

## Implemented in this change

| Item | Implementation / boundary |
|------|----------------------------|
| HTTPS-Only | Address-bar, restored-tab and split navigation upgrades; Cefrium safe GET link requests upgrade; GeckoView HTTP page requests fail closed because its callback does not expose a safe method/body replay contract. Malformed HTTP input fails closed. Local/private IPv4 and IPv6 exceptions are configurable in policy. |
| Fire clear | Refuses to run while Private or Guest tabs are open; waits for GeckoView async deletion and Cefrium profile rotation/deletion result; only then closes Normal tabs without adding them to recently-closed history and clears history. |
| Picture-in-Picture | Automatic fullscreen path remains gated; explicit “Pop out video” action is reachable from the overflow menu and probes active inline video on Cefrium. GeckoView explicit pop-out is clearly reported as unsupported. |
| Print / Save as PDF | Uses the existing engine-specific PR #34 path: Cefrium's current engine adapter or GeckoView's session PDF export. Private/Guest printing is blocked; no temporary default WebView is created. |
| Per-site dark mode | Persists per-host mode and applies/removes CSS on Cefrium page completion and immediately on mode changes. GeckoView is explicitly reported as unsupported until it exposes a safe shared script API. |
| Offline snapshot honesty | Reading list accepts HTTP(S) links only and says clearly that it stores a link, not an offline page archive. |
| Sleeping tab messaging | Memory-budget eviction marks tabs; returning to a tab explains that the page reloads and form/scroll state may not survive. |
| VPN / Tor honesty | VPN UI distinguishes browser-only proxy routing from an Android-wide VPN; Guest Tor copy says it is an embedded Tor route via GeckoView, not Tor Browser. |
| SentinelActivity | Kept as a thin compatibility alias to Nova's VPN & Firewall activity. |

## Requires physical-device / external acceptance

- Guest Tor leak matrix: device IP, DNS, WebRTC, IPv6 and UDP behavior on physical networks.
- 1VPN leak matrix: verify all engine requests, DNS and WebRTC; this is a browser proxy, not Android `VpnService`.
- Per-site dark mode and explicit PiP on supported Cefrium devices; GeckoView support remains engine-limited.
- Print/PDF output validation across both engines and Android print providers.
- Cloud OTP and two-device sync acceptance with real inboxes/devices and applied Supabase migrations.
- APK size below 100 MB with both engines (requires an engine-delivery/product decision).
- QR Nova-to-Nova Flow, true offline page archive, voice AI and other future scope remain separate projects.

## Non-goals

- Claiming Tor Browser equivalence or system-wide VPN protection.
- Clearing Private/Guest storage from Fire while those sessions remain open.
- Replaying unknown/non-GET requests as GET during HTTPS upgrades.
- Claiming a link-only reading-list entry is an offline page snapshot.
- Reintroducing user-installable extensions.
