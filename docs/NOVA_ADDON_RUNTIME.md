# Nova Add-on Runtime Contract

## Current engine boundary

Nova currently has two distinct add-on mechanisms:

- **GeckoView WebExtensions**: packages are installed through GeckoView's native WebExtension API. The Gecko runtime remains authoritative for manifest parsing, permission prompts, API support, and install failures.
- **Cefrium Nova Script Add-ons**: locally stored JavaScript is registered as a document-start script against explicit origin rules. This is not a WebExtensions runtime.

The compatibility policy in `NovaAddonRuntimePolicy` prevents the UI from treating these mechanisms as interchangeable.

## Routing rules

| Package | Normal / GeckoView | Normal / Cefrium | Private or Guest |
|---|---|---|---|
| WebExtension | Native Gecko install and manifest validation | Request explicit switch to GeckoView | GeckoView only |
| Nova Script | Not supported | Origin-scoped document-start script only | Not supported |

Cefrium Nova Script Add-ons do not receive background workers, extension storage, tab control, dynamic scripting, request interception, cookie access, or native messaging. If a package declares any of those features, the compatibility policy rejects it instead of silently installing a partial implementation.

## Security and UX requirements for the next integration step

1. Inspect package manifests before installation and show requested permissions.
2. Treat host permissions as a separate, user-visible grant; never infer them from a script body.
3. Require explicit user approval before enabling an add-on or expanding its host access.
4. Keep add-on storage separate by browser profile. Private and Guest sessions must not inherit persistent Cefrium scripts.
5. On engine switching, explain that cookies, site data, login state, and in-page state are not migrated automatically.
6. Display unsupported APIs and install errors; never label a content-script shim as full WebExtensions compatibility.

## Scope of this change

This commit adds the engine-neutral compatibility contract and unit tests. It does not claim full WebExtensions parity, implement an extension background runtime for Cefrium, or add a package marketplace/importer UI. Those require separate runtime, permission, storage, lifecycle, and security work.
