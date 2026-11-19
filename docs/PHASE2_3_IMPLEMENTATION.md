# Nova Browser — Phase 2 and Phase 3 implementation boundary

Branch: `feature/phase-1-dual-engine-foundation`

## Phase 2: engine selection and production tab migration

### Implemented foundation
- `BrowserEnginePreferenceStore` persists the global mode and optional Guest override. It resolves through the policy and exact-host affinity repository; it is not yet wired into `MainActivity`.
- `EngineSelectionPolicy` is pure and unit-testable. Automatic normal/private mode prefers Cefrium, honors exact-site affinity, and falls back to GeckoView when Cefrium is unavailable or the site is known incompatible. Explicit global GeckoView/Cefrium modes take precedence over site affinity.
- Guest mode defaults to GeckoView; Cefrium is selected only when an explicit Guest override is supplied. Cefrium unavailable/incompatible still falls back safely.
- `BrowserEngineProfilePolicy` reserves `nova-normal-v1` for persistent Normal Cefrium browsing and requires Private/Guest Cefrium profiles to be ephemeral. Gecko Guest context IDs remain separate.

### Remaining production work
- `MainActivity.BrowserTab` still contains a `GeckoSession?`; many feature delegates and browser actions access Gecko APIs directly. The common interface is not yet the production tab owner.
- Migrate tab records to engine-neutral `BrowserTab` + `BrowserEngine`, and route rendering, back/forward, navigation, lifecycle and close through the interface.
- Re-home Gecko-specific prompts, permissions, extension APIs, translation, downloads, autofill and Shield behind capability adapters. Unsupported features must be explicit, not silently dropped.
- Add the user-facing engine selector only after the production tab manager uses the policy.
- Preserve split-pane, tab groups, session restore, Private and Guest isolation.

## Phase 3: shared browser data and site migration

### Implemented foundation
- `SiteEngineAffinityStore` persists exact-host choices for Normal mode only. It does not guess registrable domains or share affinity with Private/Guest.
- `WebsiteDataMigrationCoordinator` requires explicit consent, exports only through engine adapters, verifies destination import, and never deletes source data.
- Portable data contract covers cookies, localStorage and opaque adapter-provided IndexedDB payloads.

### Remaining production work
- Implement GeckoView and Cefrium `WebsiteDataPort` adapters and test real export/import semantics.
- Wire existing app-level bookmarks, history, downloads, password vault, permissions, Shield and normal-tab/session metadata to engine-neutral repositories; keep secrets and Private/Guest data out of sync.
- Add related-domain affinity for authentication flows without broadly grouping unrelated origins.
- Keep service workers, Cache Storage, sessionStorage, passkeys and device-bound credentials out of the portable guarantee; require re-fetch or reauthentication.
- Verify failed/partial migrations leave the source profile intact and provide a user-visible recovery path.

## Acceptance gates
- Unit tests cover selection policy, Guest defaults/overrides, profile isolation, migration consent, portable-state counts and failed destination verification. CI result is pending.
- Instrumented tests cover both engines in one process, multiple tabs, split-pane, pause/resume, crash recovery and close.
- Device tests cover Android 10+ ARM64, Cefrium memory/APK footprint, site compatibility and GeckoView regressions.
- Migration tests use disposable profiles and confirm destination state before any optional source cleanup.
- Do not mark Phase 2 or 3 complete until production integration, CI and device acceptance gates pass.
