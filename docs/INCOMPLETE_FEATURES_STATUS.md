# Incomplete features status

Branch: `feature/incomplete-features-slice` (PR work).

## Completed in code (this branch)

| Item | Status |
|------|--------|
| HTTPS-Only mode | Implemented + unit tests + menu toggle |
| Fire clear (tabs + site data) | Implemented + unit tests |
| User-initiated PiP / pop-out video | Implemented + unit tests |
| Print / Save as PDF entry | `NovaPrintPolicy` + MainActivity PrintManager path |
| Per-site dark mode preference | Policy + menu cycle (CSS injection best-effort) |
| Offline snapshot honesty | Policy: link-only; no fake snapshot success |
| Sleeping tab restore hint | Policy + messaging |
| VPN / Tor honesty labels | Policy strings for UI |
| SentinelActivity | Redirects to Nova VPN activity |

## Still requires live environment (cannot be "done" in CI alone)

| Item | Remaining |
|------|-----------|
| Cloud OTP + two-device sync acceptance | Real inbox OTP, two physical devices, apply Supabase migrations |
| Guest Tor leak matrix | Device IP/DNS/WebRTC/IPv6/UDP tests |
| 1VPN leak matrix | Device tests; remains browser proxy not VpnService |
| Cefrium full Shield / Safe Browsing parity | Engine capability limits |
| APK under 100 MB with both engines | Needs product decision (on-demand engine or single-engine flavor) |
| QR Nova-to-Nova Flow | Requires pairing protocol |
| True offline page archive | Needs engine archive API |
| Voice AI / multi-tab AI / image gen | Product scope |
| User-installable extensions | Intentionally out of scope |

## Non-goals (unchanged)

- Tor Browser equivalence claims
- System-wide VPN
- Cross-engine website data migration
- Fake toggles for unavailable features
