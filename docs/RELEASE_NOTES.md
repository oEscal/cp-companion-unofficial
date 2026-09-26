# CP Companion 0.7.0 beta

Unofficial Android journey companion, independent of CP — Comboios de Portugal. Requires Android 11 or newer. Imported journeys do not replace a valid travel ticket.

This beta includes station boards, journey details, local ticket import, and automatic journey tracking. Public-project preparation adds an ordinary Gradle wrapper, contributor setup, privacy and third-party notices available offline in the Automation tab, emulator CI, secret checks, and a verified signing/release workflow.

Automatic activation can be delayed by Android or manufacturer battery restrictions. CP service support, reuse terms, and production polling quotas remain unconfirmed. Promoted notifications, long routes, and scheduling across real-device/OEM scenarios require further validation. No account login, purchases, payment, or QR/PDF retrieval is provided. See docs/KNOWN_LIMITATIONS.md and docs/VALIDATION.md in the source archive.

Install cp-companion.apk after checking SHA256SUMS. Compare the certificate fingerprint in SIGNING_CERTIFICATE.txt with the established maintainer fingerprint. Updates retain data only when installed over a build signed with the same key. Uninstalling/clearing storage loses saved journeys because backup is disabled.

For bugs, use the repository's issue tracker with synthetic/redacted data. Use SECURITY.md for sensitive reports. Beta tags are published as prereleases; use a manual run with `draft=true` when you need to review assets before publishing.
