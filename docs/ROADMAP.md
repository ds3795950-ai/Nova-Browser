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
