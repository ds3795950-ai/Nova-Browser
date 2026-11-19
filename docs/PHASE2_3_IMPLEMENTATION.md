# Nova Browser — Phase 2 and Phase 3 implementation boundary

Branch: `feature/phase-1-dual-engine-foundation`

## Phase 2: engine selection and production tab migration

### Implemented foundation
- `BrowserEnginePreferenceStore` persists the Normal-mode engine choice. New installations default to Cefrium; users may choose Automatic or GeckoView in Settings.
- `EngineSelectionPolicy` is pure and unit-testable. Normal mode defaults to Cefrium; Automatic prefers Cefrium, honors exact-site affinity and falls back to GeckoView when Cefrium is unavailable or the site is known incompatible. Explicit Normal-mode choices take precedence over site affinity.
- Guest mode is locked to GeckoView. Private mode uses Cefrium’s in-memory incognito profile when the active site does not require a Gecko-only feature; no Guest engine override exists.
- `BrowserEngineProfilePolicy` reserves `nova-normal-v1` for persistent Normal Cefrium browsing and requires Private/Guest Cefrium profiles to be ephemeral. Gecko Guest context IDs remain separate.

### Production integration landed (partial)
- Normal tabs now resolve their engine through `BrowserEnginePreferenceStore`; new installations default to Cefrium, and the Settings menu exposes Cefrium (default), Automatic and GeckoView modes.
- Guest tabs always use GeckoView. This is enforced both in production tab creation and the unit-tested engine-selection policy; changing the Normal-mode engine cannot affect Guest tabs.
- Normal-tab rendering, navigation, back/forward, reload, tab switching, session-budget eviction and Activity pause/resume route through the Cefrium adapter when Cefrium is selected.
- Normal-tab engine selection is persisted with session restore. The Settings menu can save an exact-host engine affinity for the current Normal site.
- Normal and Private tabs can explicitly switch the current tab between GeckoView and Cefrium from Settings when compatible. The action warns that engine-native cookies and website storage are not migrated, preserves the source engine's stored profile, and blocks Cefrium when Nova Shield or saved-login autofill requires GeckoView. Protected DNS, Desktop Site and Reader Mode now have Cefrium implementations.
- Private tabs use Cefrium's memory-only incognito profile; all Private tabs share that Private profile, which is cleared when the final incognito browser closes. Guest remains on GeckoView and its existing isolated Gecko context is unchanged.
- Cefrium is marked incompatible when Gecko-only Nova Shield or saved-login autofill would otherwise lose its existing protection/autofill path. Automatic mode falls back to GeckoView in those cases. Saving or syncing a login for the active Cefrium site's origin also switches that tab to GeckoView. Protected DNS now applies to both engines; enabling it no longer forces an engine switch.
- Desktop Site now sets Cefrium's desktop user agent and a desktop-width viewport before navigation. Reader Mode extracts the main article, removes scripts/forms/event handlers and renders a simplified reading page; GeckoView retains its built-in reader.
- Cefrium site permission requests use the typed `CefriumChromeClient` callback. Nova displays the requesting origin and permission names, then asks for any required Android runtime permissions only after the user chooses Allow. Denial is the default if no UI callback is supplied.
- Cefrium surface browsers use the SDK's built-in file chooser for `<input type=file>`; Nova does not replace it with an unverified custom picker.

### Remaining production work
- `MainActivity.BrowserTab` still retains a `GeckoSession?` for Gecko-specific delegates and features; the common interface is not yet the sole production tab owner.
- Split-pane can host either GeckoView or Cefrium in the secondary pane. Gecko-specific extension APIs, translation, Reader and some prompts remain engine-specific; keep those actions on GeckoView until capability adapters exist.
- Add Cefrium equivalents for Nova Shield and Protected DNS before allowing Cefrium on protected routes. Cefrium downloads are now published to `Downloads/Nova` and registered in Nova's persisted download list. Cefrium password save/fill is not exposed by the current SDK's documented public API; origins with saved logins continue to use GeckoView's encrypted vault and autofill.
- Private now uses Cefrium's in-memory incognito profile. Guest remains GeckoView-only; do not add a Cefrium Guest profile.
- Complete automated regression coverage for explicit per-tab engine switching, lifecycle and crash recovery; physical Android device acceptance remains required.

## Phase 3: shared browser data and site migration

### Implemented foundation and shared state
- `SiteEngineAffinityStore` persists exact-host choices for Normal mode only. It is wired to a Settings action and does not guess registrable domains or share affinity with Private/Guest.
- Normal-tab URLs, tab groups/session metadata, history and bookmarks remain in Nova-owned app storage, so both engine paths can use the same browser-level records.
- The existing password vault remains app-owned; Cefrium is conservatively avoided for origins with saved logins because the current Cefrium SDK does not document an equivalent native password-autofill delegate.
- `WebsiteDataMigrationCoordinator` requires explicit consent, exports only through engine adapters, verifies destination import, and never deletes source data. It is still a contract, not an end-to-end migration feature.

### Remaining production work
- No concrete GeckoView/Cefrium `WebsiteDataPort` adapters are wired. The current GeckoView public `StorageController` exposes clearing and permission operations, not a general cookie/storage export API. A complete, lossless cross-engine session migration therefore cannot be implemented against the documented public APIs; source profiles must remain intact and users may need to reauthenticate.
- Permission prompts are wired into Nova's Activity, and completed Cefrium downloads are wired into Nova's download repository. Secure password save/fill remains GeckoView-only; do not expose saved credentials to arbitrary page JavaScript as a substitute.
- Keep engine-native data distinct; service workers, Cache Storage, sessionStorage, HttpOnly/partitioned cookies, passkeys and device-bound credentials are not guaranteed portable. Require re-fetch or reauthentication where needed.
- Add related-domain affinity for authentication flows without broadly grouping unrelated origins.
- Verify failed/partial migrations leave the source profile intact and provide a user-visible recovery path.

## Acceptance gates
- Unit tests cover Cefrium as the Normal default, Automatic fallback, Private Cefrium incognito selection, Guest GeckoView locking, profile isolation, migration consent, portable-state counts and failed destination verification. The latest debug Engine Lab constructor fix, typed Cefrium permission handler and password fallback are committed; the CI/lint reruns are pending.
- Instrumented tests cover both engines in one process, multiple tabs, split-pane, pause/resume, crash recovery and close.
- Device tests cover Android 10+ ARM64, Cefrium memory/APK footprint, site compatibility and GeckoView regressions.
- Migration tests use disposable profiles and confirm destination state before any optional source cleanup.
- Do not mark Phase 2 or 3 complete until production integration, CI and device acceptance gates pass.


## Phase 1 Cefrium compatibility update — 2026-11-19

### Implemented in the feature branch
- Normal uses the named persistent Cefrium profile by default.
- Private uses Cefrium's memory-only incognito profile when no Gecko-only feature is required. Private tabs share that private profile; it is cleared when the last private Cefrium browser closes.
- Guest remains GeckoView-only. Its engine and existing isolated Gecko context are not changed.
- Protected DNS is applied to Cefrium through the SDK's global Secure DNS setting in strict mode, alongside GeckoView's TRR_MODE_ONLY configuration. Cefrium component updates are disabled because they use a separate system network context outside profile routing.
- Desktop Site applies a desktop Chromium user agent and document-start desktop viewport handling. Per-site Normal preferences are retained; Private changes are session-only.
- Cefrium Reader Mode extracts the article DOM, removes scripts, forms, event handlers, embedded frames and unsafe resource URLs, then renders a simplified reading page. GeckoView retains its built-in Reader Mode.

### Phase 1: Cefrium 1VPN adapter
- The bundled 1VPN WebExtension remains the source of truth for proxy host selection and authentication. Its existing `onAuthRequired` handler now forwards the challenged proxy host and resolved credentials to Nova's native bridge.
- A short-lived hidden Gecko session triggers that existing authentication path; credentials are held in memory only and are not copied into app constants or persisted.
- Cefrium applies the authenticated HTTPS proxy to Normal and Private profiles, answers only matching proxy-auth challenges, disables non-proxied WebRTC UDP, and uses a fixed proxy with no direct fallback. Guest remains GeckoView.
- The VPN settings screen exposes the free Singapore, Amsterdam and Los Angeles locations. If the bridge cannot obtain credentials, Cefrium remains fail-closed.
- Android build, lint, emulator and real-device network/leak acceptance must pass before Phase 1 is declared complete.

### Validation status
- Source changes and policy tests are staged on feature/phase-1-dual-engine-foundation.
- Android build, lint, unit tests, and real-device network/Reader/Desktop acceptance must pass before this phase can be called complete.
