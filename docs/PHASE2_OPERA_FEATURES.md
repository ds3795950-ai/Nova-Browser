# Nova Browser Phase 2 — Opera-inspired feature slice

This document records the integrated Phase 2 work on `main` and distinguishes shipped behavior from work that still needs engine or service support.

## Integrated in this change
- **Discover:** opt-in RSS fetch for India, world, football and general sports using HTTPS Google News RSS feeds. Requests run off the UI thread, have bounded response size/time, accept only HTTP(S) article links and do not run in Private or Guest contexts.
- **Flow:** current-page sharing through Android's system Sharesheet. Nearby Share may be available when the OS offers it; Nova does not claim QR-paired Nova-to-Nova transport. Discover and reading-list links open in Nova by default.
- **Offline reading:** persistent, user-initiated reading-list links, with URL validation and a 200-item cap. This is not an offline page snapshot; the current engine adapters do not expose a common, verified page-archive API.
- **Data Saver:** GeckoView-only image blocking, with the prior image preference restored when disabled. The Hub reports the preference as saved until GeckoView confirms the update; failures are surfaced by the browser. Cefrium does not expose a verified equivalent in the current adapter.
- **Tab search:** searches title, URL and group, uses stable tab IDs for selection and keeps Guest search scoped to Guest tabs.

## Existing Nova AI capabilities
Nova already has a multi-provider AI assistant with encrypted local API-key/history storage, model selection, bounded context/history, and page-context provenance controls. The assistant remains user-selected and is not required for ordinary browsing.

## Not claimed complete
- QR-paired encrypted Nova Flow device-to-device transport (requires a pairing/transport protocol and peer lifecycle).
- True offline page snapshots/PDF export across both engines (requires verified archive/export support).
- Network compression/proxy-based data reduction and byte-saved accounting (image blocking is not compression).
- Voice I/O, native image generation, multi-tab AI comparison, and Discover live-score/notification providers.
- Video pop-out/Picture-in-Picture, screenshot annotation, and per-site dark-mode overrides.

These remain explicit follow-up work; no fake toggles or placeholder success states were added.
