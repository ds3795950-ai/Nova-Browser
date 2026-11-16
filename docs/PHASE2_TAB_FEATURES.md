# Phase 2 tab-management completion

## Collapsible tab groups
- Normal and private tabs remain in separate sections.
- Tabs are grouped by their assigned group name; each group heading collapses/expands its cards.
- Expand all and Collapse all controls are available in the tab switcher.

## Session memory saver
- Nova keeps the active tab and up to three most-recently-used background sessions open (a split-screen second pane is retained while visible).
- Older background GeckoSessions are closed and recreated lazily when their tab is selected again.
- A sleeping tab reloads its last URL. This deliberately does not promise preservation of in-page form state, scroll position, or the full back/forward session history.
- Private tab URLs are not added to normal tab persistence or workspace snapshots.

## Split-screen
- Split mode displays two distinct GeckoViews in stacked panes, each with its own address field and Go control.
- The second pane can be selected from existing normal tabs only; private tabs are excluded.
- The same GeckoSession is never intentionally attached to both panes. Split state is temporary and is not restored after process recreation.

## Verification status
- Source-level sanity checks completed for nullable session handling, delimiter balance, split ownership ordering, and feature entry points.
- Android compilation, APK assembly, and device-level interaction still require CI/device verification; implementation is not marked verified until those checks pass.
