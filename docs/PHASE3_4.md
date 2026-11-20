# Nova Browser — Phase 3 and Phase 4 implementation status

Last reconciled: 2026-11-20. This document describes the current feature branch. It does not claim device acceptance.

## Phase 3: bookmarks, history, downloads, uploads and page tools

- **Bookmarks:** structured title/URL/folder/time records; search; folder grouping; edit/delete; create folders; JSON import/export through Android's document picker. Legacy URL-only bookmarks are migrated on read.
- **History:** timestamped entries, title/host labels, search, date grouping, deduplicated visits, 500-entry cap and clear-history. Private and Guest tabs do not write history.
- **Downloads:** app-controlled HTTP downloader with persisted job metadata and partial files, pause/resume/cancel, HTTP Range and If-Range validation, byte progress, safe filenames and publication to Downloads/Nova through MediaStore on Android 10+. Servers that ignore Range cause a safe restart from byte zero.
- **Webpage downloads:** GeckoView response interception saves the authenticated response body instead of issuing a second unauthenticated request. Private and Guest response streams are closed without saving.
- **Uploads:** Android Storage Access Framework supports single/multiple file selection. GeckoView folder-upload requests receive explicit Allow/Deny confirmation and use Android's directory picker.
- **Desktop site:** per-site mobile/desktop preference. Cefrium applies a desktop user agent and viewport; GeckoView retains its native path.
- **Reader mode:** GeckoView uses its built-in reader. Cefrium extracts article content and renders a simplified reading page.
- **Translation:** GeckoView native translation controller with source/target selection. Model availability depends on the GeckoView build and device.
- **Sharing:** Android share sheet.

## Phase 4: Private browsing and Guest Mode

- **Private:** Private tabs use Cefrium's in-memory incognito profile when Cefrium is selected, or GeckoView's private session when GeckoView is selected. They are excluded from normal history, bookmarks, workspaces and session restore. Cefrium Private tabs share the in-memory Private profile, which is cleared when the final Private Cefrium browser closes.
- **Guest:** Guest is a distinct tab category locked to GeckoView. It uses a dedicated isolated Gecko session context and is excluded from normal tabs/session restore, workspaces, history, bookmarks and saved downloads. Guest does not read owner browsing records. Owner credential callbacks are disabled while a Private or Guest tab exists.
- **Cleanup:** Guest context cleanup runs at startup to recover after process death, after the final Guest tab closes, and during Activity teardown after sessions close. The context ID rotates for the next Guest session.
- **Tab switcher:** normal, Private and Guest tabs remain separate; split-screen is restricted to normal tabs.

## Verification status and limitations

- Unit tests cover guest persistence/read boundaries, profile/engine selection, session retention and related privacy policies.
- Android CI and lint passed on commit `5c83f759b5aeef9f310d04498070fcede1f1af96`.
- The latest Android Emulator Smoke run failed during emulator setup/ADB configuration after boot completion and before the instrumentation test command. ADB returned a broken-pipe/exit-224 error; this is not a test assertion result. A workflow reliability change is being applied and must be rerun.
- Device acceptance is still required on Android 16 / Realme RMX5313 for downloads, cookie/session isolation, folder upload, translation models, Reader Mode, Private/Guest storage cleanup and process-death recovery.
- HTTP Range resume remains server-dependent. Do not claim all downloads can resume.
- Translation language pairs depend on the bundled GeckoView translation engine and downloaded model support.
- Guest Mode is ephemeral local browsing, not Tor routing or Tor-level anonymity.
