# Nova Browser reconstruction roadmap

Status key: `NOT STARTED` = no implementation in this repository; `BASELINE` = minimal foundation only; `NEEDS VERIFICATION` = implementation must be tested before being called complete.

| Area | Status | Acceptance target |
|---|---|---|
| Android/Kotlin project | BASELINE | Clean debug build on JDK 17 / SDK 36 |
| GeckoView browser surface | BASELINE | Load HTTPS pages, lifecycle-safe session, navigation callbacks |
| Home screen and search | BASELINE | Search/address parsing, suggestions, engine selection |
| Tabs and session restore | NOT STARTED | Multiple tabs, private tabs, restore after process death |
| Bookmarks and history | NOT STARTED | Persistent local storage, CRUD, privacy separation |
| Downloads and uploads | NOT STARTED | Download manager, document picker, MIME handling |
| Private mode | NOT STARTED | Separate private profile/storage; no persistence of private history/cookies after exit |
| Guest mode | NOT STARTED | Ephemeral isolated profile and clear-on-exit; not Tor unless traffic is actually routed through Tor |
| Nova Shield | NOT STARTED | Filter lists, request blocking, per-site controls, measurable tests |
| Safe browsing | NOT STARTED | Threat-source integration, warnings, false-positive controls |
| Search engines | NOT STARTED | Configurable providers and query templates |
| AI assistants | NOT STARTED | User-selected ChatGPT, Claude, Perplexity integrations; keys kept out of source |
| Login / email OTP | NOT STARTED | Supabase Auth, verified OTP flow, abuse controls |
| Cloud sync | NOT STARTED | Explicit opt-in, encrypted sync design, conflict handling |
| Settings / accessibility | NOT STARTED | Theme, privacy controls, accessible labels and scaling |
| Security and QA | NOT STARTED | Unit, instrumentation, dependency, privacy and regression tests |

## Rebuild order

1. Verify project sync/build and GeckoView runtime on a physical device.
2. Build browser core: navigation, back/forward/reload, progress, errors, permissions, downloads.
3. Add persistent tabs, bookmarks, history and session restore.
4. Implement isolated private and guest profiles and verify data deletion.
5. Implement Nova Shield and Safe Browsing with test fixtures and per-site controls.
6. Add search providers, settings and AI assistant entry points.
7. Add Supabase OTP and opt-in cloud sync only after local data models are stable.
8. Run security, privacy, performance and regression test suites; publish only after passing.

## Security constraints

- Never commit API keys, Supabase service-role keys, signing keys, or user secrets.
- Do not describe guest mode as Tor-level anonymity without a real Tor transport implementation and independent verification.
- Private browsing must not write browsing history, cookies, cache or downloads into the normal profile. Any exceptions must be explicit and user-controlled.
- Cloud sync must be opt-in and use a documented encryption and key-recovery model.
