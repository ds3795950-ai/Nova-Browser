# Phase 2 — tab management and engine integration

## Engine-neutral tab ownership

- `BrowserTab` stores a `BrowserEngine` runtime, not a raw `GeckoSession` or a concrete Cefrium engine field.
- `CefriumBrowserEngine` and `GeckoViewBrowserEngine` implement the same per-tab contract for URL loading, back/forward, reload, view, activation, host pause/resume, and close.
- `GeckoViewBrowserEngine` owns the `GeckoSession` and Gecko-specific permission snapshots created for that tab. Gecko-only delegates and APIs remain behind the Gecko adapter/factory; no engine-neutral caller may obtain another engine's native session.
- `BrowserTab` contains no `GeckoSession` or Gecko permission-object fields. `MainActivity` routes shared navigation and lifecycle through `BrowserEngine`.
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

## Phase 3 — strict engine profile isolation (replaces data migration)

- GeckoView and Cefrium use independent engine-owned profiles and storage. No website data is shared between them, in either direction.
- Cookies, browsing history, HTTP cache, localStorage, IndexedDB, service workers, Cache Storage, permissions, passwords, passkeys, downloads, and session state remain within the owning engine/profile.
- There is no cross-engine export, import, migration, synchronization, fallback read, or profile reuse.
- Switching a tab to another engine starts with that engine's own profile state. The other engine's cookies or authenticated sessions must never appear.
- Private and Guest modes remain isolated from Normal mode and from each other according to their existing profile policy; they must not persist owner browsing data.
- `WebsiteDataMigrationCoordinator` is a fail-closed guard only. Cross-engine requests return `BLOCKED_BY_ENGINE_ISOLATION`; same-engine requests are no-ops. It contains no export/import interface.
- Any future request to share data between engines is out of scope and requires a separate explicit security review and product decision.

## Phase 4 — validation and hardening

- Validate tab switching, split-screen, sleeping-tab recreation, engine switching, process recreation, and resource cleanup on Android 16 and BrowserStack.
- Verify both directions of engine switching do not expose cookies, history, cache, localStorage, IndexedDB, credentials, permissions, or session state.
- Verify Private and Guest tabs never write browsing records into Normal profile persistence.
- Do not mark the work verified until Android build, unit tests, emulator smoke tests, and device suite pass.
