# Changelog — 0.7.0

## Correctness and recovery

- Retains recently scheduled-arrival tickets for a six-hour live verification window instead of marking delayed journeys complete from timetable data alone.
- Reconciles tickets whose T-60 activation time has passed even when their scheduled arrival has also passed inside that verification window.
- Allows automation-attempt timestamps to be explicitly cleared when rescheduling.
- Preserves coroutine cancellation in WorkManager, validation, SMS import, and UI request paths.
- Serializes catalogue writes and uses `AtomicFile` replacement; unreadable cache files are isolated and rebuilt.
- Removes corrupt encrypted values after Android Keystore invalidation or restore damage so startup can recover.

## Network and performance

- Makes the process-wide CP cooldown an atomic monotonic update under concurrent HTTP 429 responses.
- Caps server-directed `Retry-After` at 24 hours.
- Requires HTTPS, the exact CP gateway host and standard TLS port, expected service path prefixes, and URLs without embedded credentials/query/fragment data.
- Retains single-flight requests, bounded caches, token-bucket request pacing, stale-response fallback, adaptive polling, and request-family diagnostics.

## Security and privacy

- Adds explicit Android cloud-backup and device-transfer exclusions while retaining `allowBackup=false`.
- Adds a cleartext-denying network-security policy.
- Validates deep-link IDs as UUIDs and limits external shared text and notification-import payload sizes.
- Requires explicit confirmation before shared ticket text is imported.
- Restricts automatic notification imports to the user-selected default SMS app and preserves the extracted sender during validation.
- Marks tracking notifications private so Android can redact route, platform, carriage, and seat details on secured lock screens.
- Whitelists all accepted platform-broadcast actions.
- Removes ticket/route identifiers from notification diagnostic output.
- Adds generated-artifact, capture, local SDK, and signing-material exclusions.

## UI and platform

- Uses `MaterialExpressiveTheme` globally with expressive shapes, typography, dynamic color, and System/Light/Dark appearance.
- Updates Material 3 to `1.5.0-alpha24` and AndroidX Concurrent to `1.3.0`.
- Localizes modified search empty states, service-date controls, station favorites, stop-state chips, automation actions, and fallback errors in English and Portuguese.
- Adds accessibility semantics for the favorite-station control.
- Keeps Android 16+ progress-centric journey notifications and normal ongoing-notification fallback.

## Build and validation

- Advances the application to version 0.7.0 (version code 9).
- Pins and verifies the Gradle 9.4.1 distribution checksum.
- Pins third-party GitHub Actions to immutable commit revisions.
- Requires Python 3, runs source validation from `build.sh`, and verifies the checked-in source checksum manifest.
- Adds a CI workflow for unit tests, lint, Android-test compilation, debug assembly, and release shrinking.
- Removes stale build products from the delivery archive.
