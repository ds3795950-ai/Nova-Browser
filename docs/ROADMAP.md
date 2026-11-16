# Nova Browser reconstruction roadmap

Status key: DONE = implemented in this repository; NEEDS VERIFICATION = code exists but build/device verification is pending; NOT STARTED = not implemented.

| Area | Status | Notes |
|---|---|---|
| Android/Kotlin project | NEEDS VERIFICATION | AGP 9.1.1, API 37 compile SDK, Java 17 |
| GeckoView browser engine | NEEDS VERIFICATION | GeckoView only; no WebView or Chromium fallback |
| Address/search bar | DONE | URL detection and selectable search provider |
| Search providers | DONE | Google, Bing, DuckDuckGo, Brave Search |
| Back/forward/reload/home | DONE | Basic navigation controls |
| Multiple tabs | DONE | Create, switch and close tabs |
| Private tabs | NEEDS VERIFICATION | GeckoView private sessions; no normal history/bookmark writes |
| Bookmarks | DONE | Local bookmark save/remove/list |
| Browsing history | NEEDS VERIFICATION | Local normal-session URL history and clear action |
| Share page | DONE | Android share chooser |
| Settings shell | DONE | Search provider, clear history, close private tabs, about |
| Downloads and uploads | NOT STARTED | GeckoView delegate integration and Android document flows |
| Guest mode | NOT STARTED | Must use isolated temporary profile and clear it on exit |
| Nova Shield | NOT STARTED | Filter lists, request blocking, per-site controls and tests |
| Safe browsing | NOT STARTED | Threat-source integration and warning UX |
| AI assistants | NOT STARTED | ChatGPT, Claude and Perplexity entry points |
| Supabase email OTP | NOT STARTED | Needs verified project configuration; no secrets in source |
| Opt-in cloud sync | NOT STARTED | Requires client-side encryption and recovery design |
| Extensions, reader, translation, split-screen | NOT STARTED | Later feature batches |
| Security and regression QA | NOT STARTED | Requires build and device testing |

## Next implementation batches
1. Verify this browser-core slice builds and launches.
2. Add reliable session restoration, download/upload delegates and permissions.
3. Implement isolated guest profile and Nova Shield request filtering.
4. Add AI provider handoff, accessibility and settings coverage.
5. Add authentication and encrypted opt-in sync only after local data models are stable.

Never describe Guest Mode as Tor anonymity unless traffic is actually routed through Tor and independently verified.


## Phase 2 implementation progress (2026-11-16)
- Added a two-column visual tab grid with distinct Normal Tabs and Private Tabs sections.
- Added per-tab open, close, rename, and group/workspace assignment actions.
- Added named workspace snapshots and a workspace restore flow.
- Added normal-tab session persistence across activity pause/recreation and app restarts.
- Private tabs are deliberately excluded from session persistence and saved workspaces.
- Inactive GeckoSession instances are deactivated and unfocused when switching tabs; active tab is reactivated on selection.
- Added close-current and close-private-tabs paths that maintain an available normal tab.

### Phase 2 verification and remaining work
- CI build and tests are pending for this batch.
- Physical-device testing of tab grid layout, session restore, and memory behavior remains pending.
- True split-screen with two simultaneously visible GeckoViews is not implemented yet.
- Fine-grained automatic tab sleeping/memory eviction is not implemented; current behavior only deactivates unfocused sessions.
- Group management currently assigns a shared group/workspace label; richer collapsed group management remains future work.


## Phase 5 — Nova Shield implementation (2026-11-16)
- Added Off, Standard and Aggressive modes backed by GeckoView native anti-tracking, cookie isolation, ETP, query-parameter stripping and Safe Browsing settings.
- Safe Browsing remains enabled in every mode; turning Shield Off only disables the extra tracking protections.
- Enabled Global Privacy Control and native fingerprinting protection in Aggressive mode (and private browsing).
- Added per-site tracking-protection exception control using GeckoView's permission object and a per-page protection log view.
- Added site permission inspection/change and site-data clearing controls.
- Note: exact tracker coverage depends on GeckoView's shipped protection lists; this is not a DNS firewall or a claim that every ad is blocked.

## Phase 6 — Browser AI integrations (2026-11-16)
- Added ChatGPT, Claude and Perplexity browser handoff through their official web apps.
- Prompts are user-entered; page URL context is opt-in and only sent after the user presses Send. Page contents are not scraped or transmitted by Nova.
- No API keys or provider credentials are embedded in the APK.

## Phase 7 — Extensions and advanced tools (2026-11-16)
- Added GeckoView WebExtension manager: list, enable, disable, uninstall and install a direct HTTPS XPI. GeckoView validates signed add-ons.
- Required extension permissions require an explicit prompt; private-browsing access and data-collection consent are denied by default; optional permission requests are denied.
- Added encrypted password storage using AES-GCM with a non-exportable Android Keystore key, GeckoView login fetch/save/select delegates, and a local vault UI. Automatic one-entry autofill is intentionally off; user selection is required. Password saving/autocomplete is disabled in private and Guest tabs.
- Added per-site permission controls, site-data clearing, adjustable page text scaling and JSON history export. Bookmark JSON import/export already exists.
- No raw passwords are exported.

### Verification caveats
- This implementation requires CI compilation and regression tests before build-verified status.
- Extension availability depends on Mozilla-signed XPI compatibility with the embedded GeckoView version.
- AI services run in their official websites and require the user to sign in with each provider.
- Physical-device testing remains required for permission prompts, Android Keystore behavior, extension installs and password form flows.
