# Nova Browser — Phase 3 and Phase 4 completion update

## Phase 3: bookmarks, history, downloads, uploads and page tools

- Bookmarks: structured title/URL/folder/time records; search; folder grouping; edit; delete; create folders; JSON import/export via Android document picker. Legacy URL-only bookmarks are migrated on read.
- History: timestamped entries, title/host labels, search, date grouping, deduplicated visits, 500-entry cap, and clear-history. Private and Guest tabs never write history.
- Downloads: replaced the limited platform DownloadManager path with an app-controlled HTTP downloader. It persists job metadata and partial files, supports pause/resume/cancel, uses HTTP Range and If-Range validators when the server supports them, shows byte progress, and publishes completed files to Downloads/Nova using MediaStore on Android 10+. On Android 8/9, it uses the app-specific Downloads directory. Resume requires a server that honors byte-range requests; if a server ignores Range, the downloader safely restarts from byte zero.
- Webpage download interception: GeckoView ContentDelegate.onExternalResponse consumes the actual response body stream and saves it, preserving the browser's already-authenticated response rather than issuing a second unauthenticated request. These downloads appear in the downloads list and can be cancelled while saving. Private and Guest response streams are closed without saving.
- Uploads: Android Storage Access Framework picker supports single and multiple file inputs. GeckoView folder-upload prompts now receive an explicit Allow/Deny confirmation instead of being silently rejected. Folder upload is controlled by GeckoView's folder-upload prompt; it is not an unrestricted filesystem picker.
- Desktop site: per-site mobile/desktop preference uses GeckoView user-agent and viewport settings. Preferences are stored only for normal browsing.
- Reader mode: uses GeckoView's built-in `about:reader` article extraction and reader UI, with an in-app explanation and Exit Reader action. The original URL is retained for exit.
- Translation: now invokes GeckoView's native translation controller, offers source and target language selection, and allows GeckoView to download required translation models. Actual language availability depends on the GeckoView translation engine and model support on the device; unsupported pairs report an error rather than silently switching to an external website.
- Sharing remains available via Android share sheet.

## Phase 4: private browsing and Guest Mode

- Private tabs use GeckoView private mode and are excluded from normal history, bookmarks, workspaces, and session restore.
- Guest Mode is a distinct tab category using private mode plus a dedicated Gecko session storage context. Guest tabs are excluded from normal persistence, workspaces, history, bookmarks, and downloads.
- Guest storage context is cleared at startup (cleaning up after process death), after the final Guest tab closes, and during activity teardown after its sessions close. The context ID rotates for the next Guest session.
- Tab switcher keeps normal, private, and Guest tabs separate; split-screen remains restricted to normal tabs.

## Verification and remaining limits

- Earlier combined Phase 3/4 commit passed GitHub Actions unit tests, debug APK assembly, and artifact upload.
- This follow-up adds new downloader and GeckoView delegate APIs and must pass a fresh CI build before it can be called build-verified.
- Device tests are still required on Android 16 / Realme RMX5313 for downloads, cookies/session isolation, folder upload, translation model downloads, and reader mode. No physical-device test is claimed here.
- HTTP Range support is server-dependent; non-range servers restart rather than resume. Downloads do not yet run in a dedicated foreground service, so active transfers may stop if Android kills the app process. Partial data and metadata remain so the user can resume after reopening Nova.
- GeckoView native translation requires its translation toolkit/model support; available language pairs vary by GeckoView build and device.


## Follow-up build fix

- Translation failure callback now handles GeckoResult nullable Throwable safely.
