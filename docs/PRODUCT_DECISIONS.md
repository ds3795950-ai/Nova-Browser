# Nova Browser — locked product decisions

These are the previously agreed product choices. Do not replace them with generic defaults or re-ask the user unless a genuine technical blocker requires a decision.

## 1. Visual design and home screen
- Dark purple/black, Chrome-like interface.
- Kali Dragon background artwork.
- Guest Mode uses English by default.
- Home-screen shortcuts can be hidden/shown with a Settings toggle.
- Keep the home screen customizable and visually consistent with the established Nova design.

## 2. Search and address bar
- Search-engine selector button sits beside the address bar.
- Built-in web search providers: Google, Bing, DuckDuckGo, Brave Search.
- Perplexity is also offered as an AI assistant, not silently substituted for a normal search provider.
- Add a + search-attachment control for images/files. Preserve the typed query; use Android's share flow when a provider does not support direct attachment upload.
- Search results open inside Nova.

## 3. Tabs and organization
- Grid-style tab switcher.
- Tab groups.
- Private tabs remain separated from normal tabs.
- Also implement bookmark folders/import-export, workspaces, saved sessions and tab sleeping.

## 4. Private browsing
- Separate private browsing state from normal browsing.
- Clear private session data on exit; do not leak private history/cookies into normal profile.

## 5. Guest mode
- Separate temporary guest data, no account required, English default.
- Guest mode is not automatically Tor and must not be described as Tor-level anonymity. The current implementation offers a user-initiated external Orbot hand-off and a Tor Project egress check; the check verifies only the app process's current egress and does not prove every tab/request was routed through Tor.

## 6. Nova Shield
- User-selectable modes: Standard / Aggressive / Off.
- Include ad and tracker blocking, fingerprinting protection, cookie isolation, HTTPS-first behavior and cleanup controls.
- Provide per-site controls and make the selected mode visible.

## 7. Security
- Include phishing and malware protection.
- Security features must be implemented with verifiable sources and tests; do not overstate protection.

## 8. AI assistants
- Offer ChatGPT, Claude and Perplexity alongside one another.
- Keep assistant selection user-controlled; do not require API secrets for ordinary browser navigation.

## 9. Authentication and cloud sync
- Preserve the planned Supabase email OTP login and opt-in cloud sync.
- Never commit Supabase service-role keys, API secrets or signing keys.
- Define encrypted sync and recovery before syncing sensitive data. Password sync must not be enabled by default.

## 10. Additional browser capabilities
- Extensions.
- Split-screen.
- Bookmark folders and import/export.
- Workspaces.
- Saved sessions.
- Tab sleeping.
- Phishing/malware protection.
- Also retain the previously discussed desktop-site mode with per-site preferences, downloads, file uploads, reader mode, translation, password manager/autofill and session restore as roadmap items.

## Engine constraint
- Current rebuild target is Android/Kotlin with GeckoView as the only browser engine. Do not add Chromium or Android WebView as a fallback.

## Implementation policy
These decisions define the target product, not the current implementation status. Update docs/ROADMAP.md as features are implemented and tested. A feature is not complete until its acceptance tests pass. Never claim Tor routing, security protection, encryption, login or sync is working without technical verification.