# Nova Browser — Phase 2 and Phase 3 implementation boundary

Branch: `feature/phase-1-dual-engine-foundation`

## Phase 2: engine selection and production tab migration

### Implemented foundation
- `BrowserEnginePreferenceStore` persists the global mode and optional Guest override. It is wired into the production Settings menu and normal-tab creation.
- `EngineSelectionPolicy` is pure and unit-testable. Automatic normal/private mode prefers Cefrium, honors exact-site affinity, and falls back to GeckoView when Cefrium is unavailable or the site is known incompatible. Explicit global GeckoView/Cefrium modes take precedence over site affinity.
- Guest mode defaults to GeckoView; Cefrium is selected only when an explicit Guest override is supplied. Cefrium unavailable/incompatible still falls back safely.
- `BrowserEngineProfilePolicy` reserves `nova-normal-v1` for persistent Normal Cefrium browsing and requires Private/Guest Cefrium profiles to be ephemeral. Gecko Guest context IDs remain separate.

### Production integration landed (partial)
- Normal tabs now resolve their engine through `BrowserEnginePreferenceStore`; the Settings menu exposes Automatic, Cefrium and GeckoView modes.
- Normal-tab rendering, navigation, back/forward, reload, tab switching, session-budget eviction and Activity pause/resume route through the Cefrium adapter when Cefrium is selected.
- Normal-tab engine selection is persisted with session restore. The Settings menu can save an exact-host engine affinity for the current Normal site.
- Normal tabs can explicitly switch the current tab between GeckoView and Cefrium from Settings. The action warns that engine-native cookies and website storage are not migrated, preserves the source engine's stored profile, and blocks Cefrium when Nova Shield, Protected DNS, desktop-site mode, Reader view or saved-login autofill requires GeckoView.
- Private and Guest tabs deliberately remain on GeckoView. Cefrium's shared incognito profile cannot currently provide the separate, independently erasable Private and Guest storage contexts required by Nova.
- Cefrium is marked incompatible when Gecko-only Nova Shield, Protected DNS or a saved-login site would otherwise lose its existing protection/autofill path. Automatic mode falls back to GeckoView in those cases. Saving or syncing a login for the active Cefrium site's origin also switches that tab to GeckoView. Enabling Shield or Protected DNS while a Cefrium tab is active switches that tab to GeckoView and warns that website state is not migrated.
- Desktop-site and Reader actions that require GeckoView switch the active tab to GeckoView before attaching a Gecko session.
- Cefrium site permission requests use the typed `CefriumChromeClient` callback. Nova displays the requesting origin and permission names, then asks for any required Android runtime permissions only after the user chooses Allow. Denial is the default if no UI callback is supplied.
- Cefrium surface browsers use the SDK's built-in file chooser for `<input type=file>`; Nova does not replace it with an unverified custom picker.

### Remaining production work
- `MainActivity.BrowserTab` still retains a `GeckoSession?` for Gecko-specific delegates and features; the common interface is not yet the sole production tab owner.
- Split-pane can host either GeckoView or Cefrium in the secondary pane. Gecko-specific extension APIs, translation, Reader and some prompts remain engine-specific; keep those actions on GeckoView until capability adapters exist.
- Add Cefrium equivalents for Nova Shield and Protected DNS before allowing Cefrium on protected routes. Cefrium downloads are now published to `Downloads/Nova` and registered in Nova's persisted download list. Cefrium password save/fill is not exposed by the current SDK's documented public API; origins with saved logins continue to use GeckoView's encrypted vault and autofill.
- Add isolated, erasable Cefrium profiles for Private and Guest before enabling those modes on Cefrium.
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
- Unit tests cover selection policy, Guest defaults/overrides, profile isolation, migration consent, portable-state counts and failed destination verification. The latest debug Engine Lab constructor fix, typed Cefrium permission handler and password fallback are committed; the CI/lint reruns are pending.
- Instrumented tests cover both engines in one process, multiple tabs, split-pane, pause/resume, crash recovery and close.
- Device tests cover Android 10+ ARM64, Cefrium memory/APK footprint, site compatibility and GeckoView regressions.
- Migration tests use disposable profiles and confirm destination state before any optional source cleanup.
- Do not mark Phase 2 or 3 complete until production integration, CI and device acceptance gates pass.
