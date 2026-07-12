# Delivery summary — 0.6.0

This archive contains one CP Companion Android application source project using application ID `pt.cpcompanion`.

## Included

- All lifecycle, performance, persistence, navigation, SMS-concurrency, and past-ticket-control fixes listed in `CHANGELOG_0.6.0.md`.
- Automatic future-ticket T-60 activation and recovery scheduling.
- No automation switch for departed or past tickets.
- Room ticket storage with migration from the earlier encrypted JSON format.
- DataStore preferences.
- Shared CP request coalescing, bounded caches, global HTTP 429 cooldown, and adaptive polling.
- Direct SMS inbox, paste/share, and notification-listener import in one application.
- English and Portuguese UI/notification resources.
- JVM and Android test sources, CI configuration, and optional release signing.

## Build artifact status

Stale build output and APKs are excluded. This execution environment does not contain an Android SDK, so a newly compiled APK is not represented as verified. Run `./build.sh` on a configured Android machine. The script compiles unit tests, lint, Android tests, debug, and release variants.
