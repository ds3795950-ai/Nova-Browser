# Phase 2 — tab management and engine integration

## Engine-neutral tab ownership

- `BrowserTab` stores a `BrowserEngine` runtime, not a raw `GeckoSession` or a concrete Cefrium engine field.
- `CefriumBrowserEngine` and `GeckoViewBrowserEngine` implement the same per-tab contract for URL loading, back/forward, reload, view, activation, host pause/resume, and close.
- `GeckoViewBrowserEngine` owns the `GeckoSession` and Gecko-specific permission snapshots created for that tab. `MainActivity` resolves the session through a narrow Gecko-specific accessor only for APIs that do not yet have a cross-engine equivalent (translation, prompt delegates, and download callbacks).
- `BrowserTab` contains no `GeckoSession` or Gecko permission-object fields. `MainActivity` routes shared navigation and lifecycle through `BrowserEngine`; it no longer calls `GeckoView.setSession`, `GeckoSession.loadUri`, or stores Gecko-only permission state in the tab model.
- Gecko adapter attach/detach moves a session between the primary and secondary panes and releases the old view before attaching the new one. Closing a tab closes its engine once; Cefrium's default attach/detach hooks leave its engine-owned view unchanged.
- Cefrium-only reader extraction and desktop user-agent control remain explicit adapter-specific capabilities rather than being falsely advertised as common features.

## Existing tab behavior

- Normal and Private tabs remain in separate sections; Guest tabs remain isolated from owner tabs.
- Tabs are grouped by their assigned group name and support collapsed group headings, expand-all, and collapse-all controls.
- Nova keeps the active tab and up to three most-recently-used background runtimes open. Older runtimes are closed and recreated lazily when selected.
- A sleeping tab reloads its last URL; it does not promise preservation of in-page form state, scroll position, or the full back/forward session history.
- Private and Guest URLs are excluded from normal tab persistence and workspace snapshots.

## Split-screen

- Split mode supports two distinct tab-owned engine views in stacked panes, each with its own address field and Go control.
- The second pane can be selected from existing Normal tabs only; Private and Guest tabs are excluded.
- Gecko sessions are attached to exactly one pane at a time. Switching or closing a tab detaches its Gecko view before it is re-used or released.
- The secondary address field and Go control expose stable accessibility labels for automation and assistive technology.
- Split state is temporary and is not restored after process recreation.

## Remaining Phase 2 work

- Gecko session construction, one-time delegate registration ordering, and session opening are owned by `GeckoViewEngineFactory`; Gecko-specific callbacks are passed through a typed `GeckoSessionDelegateInstaller`. The callback implementations remain host-owned because they invoke Android UI, vault, download, and permission services.
- The shared `BrowserEngine.supports()` contract now gates Reader Mode, downloads, and Shield entry points; translation and extensions use the capability-routing policy. Continue migrating remaining feature-specific checks to this contract as adapters are extracted.
- Add deterministic tests for engine selection, privacy-profile overrides, Cefrium availability/compatibility fallbacks, tab-session retention, and split-pane retention. Validate tab switching, split-screen, sleeping-tab recreation, engine switching, process recreation, and resource cleanup on Android 16 and BrowserStack.
- Do not mark Phase 2 verified until the Android build, unit tests, emulator smoke tests, and BrowserStack device suite pass.

## Verification status

- Source integration: implemented in the current feature branch; CI/device verification is pending for this revision.
- The Gecko callback bodies remain host-managed intentionally, but the factory now owns session creation, delegate registration ordering, and opening. Shared navigation and lifecycle remain engine-neutral; Android UI callbacks still require host integration.
