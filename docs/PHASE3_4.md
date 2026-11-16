# Nova Browser — Phase 3 and Phase 4 implementation

## Phase 3: bookmarks, history, downloads and page tools

- Bookmarks now use structured JSON records with title, URL, folder, and creation time. The bookmark manager supports search, folder grouping, editing, deletion, folder creation, and JSON import/export through Android's document picker. Existing URL-only bookmark data is migrated on read.
- History records timestamps and titles (host labels), deduplicates revisits, caps retained entries at 500, and provides text search, date grouping, and clear-history support. Legacy history entries remain readable.
- Downloads use Android DownloadManager, save into the public Downloads directory, show OS notifications, retain Nova download IDs, and expose status/progress snapshots plus cancellation and the system downloads screen. Android DownloadManager does not expose a reliable app-controlled pause/resume API in this implementation; live in-app polling and browser-native download interception remain outstanding.
- HTML file-input prompts are connected to Android's Storage Access Framework. Single and multiple file selection are supported. Folder uploads are explicitly dismissed as unsupported.
- Desktop-site mode uses GeckoView desktop user-agent and viewport settings and remembers the preference by site for normal tabs. Private and Guest browsing do not persist this preference.
- Reader mode opens GeckoView's reader URI for supported pages. Translation opens Google Translate for the current page. Existing share-page support remains available.

## Phase 4: private browsing and Guest Mode

- Private tabs use GeckoView private mode and are excluded from history, bookmarks, normal tab restore, and saved workspaces. Closing private sessions lets GeckoView discard their private data.
- Guest Mode is a distinct tab category with its own Gecko session storage context ID, in addition to GeckoView private mode. Guest tabs are excluded from normal persistence, workspaces, history, and bookmarks. Downloads are blocked in private and Guest Mode because downloaded files remain on-device.
- Guest storage context is cleared at startup (to clean up after a process kill), when the final Guest tab is closed, and during activity teardown after its sessions close. Exiting Guest Mode closes its tabs and rotates the context ID for the next guest session.
- The tab switcher displays normal, private, and Guest tabs separately. Split-screen selection remains restricted to normal tabs.

## Verification and known limitations

- Source-level checks passed for privacy/persistence boundaries, expected feature entry points, duplicate methods, and balanced delimiters.
- Android compilation, unit tests, APK assembly, and device interaction must still pass CI before these features can be marked verified.
- Download pause/resume, automatic interception of arbitrary page downloads, folder upload, native on-device translation, and full browser reader extraction/UI remain unfinished. Reader mode currently relies on GeckoView's internal reader URI and must be tested against actual pages.
- Guest context cleanup is wired through GeckoView StorageController and must be exercised on-device, including force-stop/relaunch.
