# Nova Browser — Phase 2 and Phase 3 implementation boundary

Branch: `feature/phase-1-dual-engine-foundation`

## Phase 2: engine selection and production tab lifecycle

### Implemented foundation
- `BrowserEnginePreferenceStore` persists the Normal-mode engine choice. New installations default to Cefrium; users may choose Automatic or GeckoView in Settings.
- `EngineSelectionPolicy` is pure and unit-testable. Normal mode defaults to Cefrium; Automatic prefers Cefrium, honors exact-site affinity and falls back to GeckoView when Cefrium is unavailable or the site is known incompatible. Explicit Normal-mode choices take precedence over site affinity.
- Private and Guest modes are both locked to GeckoView. Normal-mode preferences and site affinities cannot override either privacy profile.
- `BrowserEngineProfilePolicy` reserves `nova-normal-v1` for persistent Normal Cefrium browsing. Private uses GeckoView private sessions; Guest uses GeckoView private sessions plus a dedicated temporary context ID.

### Production integration landed (partial)
- Normal tabs now resolve their engine through `BrowserEnginePreferenceStore`; new installations default to Cefrium, and the Settings menu exposes Cefrium (default), Automatic and GeckoView modes.
- Guest tabs always use GeckoView. This is enforced both in production tab creation and the unit-tested engine-selection policy; changing the Normal-mode engine cannot affect Guest tabs.
- Normal-tab rendering, navigation, back/forward, reload, tab switching, session-budget eviction and Activity pause/resume route through the Cefrium adapter when Cefrium is selected.
- Normal-tab engine selection is persisted with session restore. The Settings menu can save an exact-host engine affinity for the current Normal site.
- Normal tabs can explicitly switch the current tab between GeckoView and Cefrium from Settings when compatible. Private and Guest tabs cannot switch engines. The action warns that engine-native cookies and website storage are never transferred, preserves the source engine's stored profile, and blocks Cefrium when Nova Shield or saved-login autofill requires GeckoView. Protected DNS, Desktop Site and Reader Mode now have Cefrium implementations.
- Private tabs always use GeckoView private sessions. Guest remains GeckoView-only and uses its separate ephemeral Gecko context.
- Cefrium is marked incompatible when Gecko-only Nova Shield or saved-login autofill would otherwise lose its existing protection/autofill path. Automatic mode falls back to GeckoView in those cases. Saving or syncing a login for the active Cefrium site's origin also switches that tab to GeckoView. Protected DNS now applies to both engines; enabling it no longer forces an engine switch.
- Desktop Site now sets Cefrium's desktop user agent and a desktop-width viewport before navigation. Reader Mode extracts the main article, removes scripts/forms/event handlers and renders a simplified reading page; GeckoView retains its built-in reader.
- Cefrium site permission requests use the typed `CefriumChromeClient` callback. Nova displays the requesting origin and permission names, then asks for any required Android runtime permissions only after the user chooses Allow. Denial is the default if no UI callback is supplied.
- Cefrium surface browsers use the SDK's built-in file chooser for `<input type=file>`; Nova does not replace it with an unverified custom picker.

### Remaining production work
- `MainActivity.BrowserTab` now stores the engine-neutral `BrowserEngine?` and does not retain a `GeckoSession`. `MainActivity` still installs Gecko-specific delegates and uses a narrow Gecko-session accessor for features without cross-engine equivalents; moving those delegate responsibilities fully behind the Gecko adapter remains outstanding.
- Split-pane can host either GeckoView or Cefrium in the secondary pane. Gecko-specific extension APIs, translation, Reader and some prompts remain engine-specific; keep those actions on GeckoView until capability adapters exist.
- Add Cefrium equivalents for Nova Shield and Protected DNS before allowing Cefrium on protected routes. Cefrium downloads are published under `Downloads/Nova/cefrium` and GeckoView downloads under `Downloads/Nova/geckoview`, with independent persisted lists and partial-file directories. Cefrium password save/fill is not exposed by the current SDK's documented public API; origins with saved logins continue to use GeckoView's encrypted vault and autofill.
- Private and Guest are both GeckoView-only. Do not add a Cefrium Private or Guest profile.
- Complete automated regression coverage for explicit per-tab engine switching, lifecycle and crash recovery; physical Android device acceptance remains required.

## Phase 3: strict engine profile isolation (supersedes the migration proposal)

- Cefrium and GeckoView use separate engine-owned website storage. Cookies, HTTP cache, localStorage, IndexedDB, service workers, Cache Storage, permissions, credentials, passkeys and native session state are never copied or read across engines.
- Nova-owned records that could otherwise bridge the profiles are namespaced by engine: bookmarks, history, history-clear markers, bookmark tombstones/folders, workspaces, saved Normal tab metadata, downloads and partial downloads.
- Bookmark backup files carry an engine identity; importing a file from the other engine or an unscoped legacy file is rejected. History exports are engine-tagged.
- Password vault access remains GeckoView-only. Private and Guest modes cannot read or persist owner bookmarks, history, workspaces or downloads. Guest remains on its dedicated ephemeral Gecko context; Private remains a private Gecko session.
- Encrypted cloud sync uses separate `profiles.GECKOVIEW` and `profiles.CEFRIUM` envelopes. Merge and restore operate within each engine namespace. Older unscoped snapshots are not assigned to either profile because their engine provenance cannot be proven.
- `WebsiteDataMigrationCoordinator` is a fail-closed guard only: same-engine requests are no-ops and every cross-engine request returns `BLOCKED_BY_ENGINE_ISOLATION`. It has no export/import adapter contract.
- Existing unscoped legacy bookmarks/history/workspaces/download metadata are left untouched but quarantined; the app does not guess which engine owns them.
- No cross-engine export, import, migration, synchronization, fallback read or profile reuse is permitted. Any future sharing proposal requires a separate security review and explicit product decision.

## Acceptance gates
- Unit tests cover Cefrium as the Normal default, Automatic fallback, Private GeckoView-only selection, Guest GeckoView locking, profile isolation, engine-scoped Nova storage keys, engine-tagged bookmark backups, profile-separated cloud-sync merge/restore, and fail-closed cross-engine migration rejection. Private/Guest engine-selection and engine-switch guards are committed; Phase 3 regression tests are being added.
- Instrumented tests cover both engines in one process, multiple tabs, split-pane, pause/resume, crash recovery and close.
- Device tests cover Android 10+ ARM64, Cefrium memory/APK footprint, site compatibility and GeckoView regressions.
- Isolation tests prove no cross-engine export/import, no fallback reads, no cross-profile cloud merge, and no use of unscoped legacy snapshots.
- Do not mark Phase 2 or 3 complete until production integration, CI and device acceptance gates pass.


## Phase 1 Cefrium compatibility update — 2026-11-19

### Implemented in the feature branch
- Normal uses the named persistent Cefrium profile by default.
- Private tabs always use GeckoView private sessions; they are not stored in a Cefrium profile.
- Guest remains GeckoView-only. Its engine and existing isolated Gecko context are not changed.
- Protected DNS is applied to Cefrium through the SDK's global Secure DNS setting in strict mode, alongside GeckoView's TRR_MODE_ONLY configuration. Cefrium component updates are disabled because they use a separate system network context outside profile routing.
- Desktop Site applies a desktop Chromium user agent and document-start desktop viewport handling. Per-site Normal preferences are retained; Private changes are session-only.
- Cefrium Reader Mode extracts the article DOM, removes scripts, forms, event handlers, embedded frames and unsafe resource URLs, then renders a simplified reading page. GeckoView retains its built-in Reader Mode.

### Phase 1: Cefrium 1VPN adapter
- The bundled 1VPN WebExtension remains the source of truth for proxy host selection and authentication. Its existing `onAuthRequired` handler now forwards the challenged proxy host and resolved credentials to Nova's native bridge.
- A short-lived hidden Gecko session triggers that existing authentication path; credentials are held in memory only and are not copied into app constants or persisted.
- Cefrium applies the authenticated HTTPS proxy to Normal profiles only, answers only matching proxy-auth challenges, disables non-proxied WebRTC UDP, and uses a fixed proxy with no direct fallback. Private and Guest remain GeckoView-only.
- The VPN settings screen exposes the free Singapore, Amsterdam and Los Angeles locations. If the bridge cannot obtain credentials, Cefrium remains fail-closed.
- Android build, lint, emulator and real-device network/leak acceptance must pass before Phase 1 is declared complete.

### Validation status
- Source changes and policy tests are staged on feature/phase-1-dual-engine-foundation.
- Android build, lint, unit tests, and real-device network/Reader/Desktop acceptance must pass before this phase can be called complete.
