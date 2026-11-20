# Cefrium third-party license and release compliance

Cefrium 0.21.0 identifies its license as **LGPL-3.0-or-later**. This is an integration checklist, not legal advice. Complete it before distributing a Nova release that includes Cefrium.

## Required release records

- Pin the same exact version for `com.cefrium` Gradle plugin and `com.cefrium:cefrium-sdk` dependency. Phase 1 pins `0.21.0`.
- Preserve the upstream release's `LICENSE`, `NOTICE`, SBOM, `SHA256SUMS` and detached signature. Verify the GPG signature and artifact checksums against the upstream release before producing a public release.
- Include the applicable LGPL-3.0 license text and required third-party notices in Nova's legal/about materials.
- Record the exact Cefrium source tag and Chromium/CEF revision included in each Nova release.
- Review the LGPL obligations for distributing the linked SDK, including user rights relating to replacement/modification of the LGPL component and any restrictions introduced by signing or packaging. Obtain legal review before publication.
- Do not modify Cefrium binaries or strip licensing notices. If Nova patches Cefrium, retain and publish the corresponding source changes as required by the license.

## Upstream references

- [Cefrium releases and signed artifact checksums](https://cefrium.com/releases/)
- [Cefrium project and license](https://cefrium.com/about/)
- [Cefrium source tags](https://codeberg.org/cefrium)
- [GNU LGPL v3](https://www.gnu.org/licenses/lgpl-3.0.html)

**Release gate:** no production distribution until the exact upstream artifact, notices, source availability and app packaging obligations have been checked for the pinned Cefrium version.
