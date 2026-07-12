# Validation report — 0.6.0

## Source checks completed in this environment

- Confirmed one Android application module and one application ID: `pt.cpcompanion`.
- Confirmed version name 0.6.0, version code 8, and CP User-Agent 0.6.0.
- Parsed every Android XML manifest/resource file.
- Verified English and Portuguese resource-name parity and all referenced resources.
- Checked shell syntax for `build.sh`.
- Checked Kotlin/KTS delimiter balance and cross-file symbol visibility with source-level checks.
- Verified the archive excludes previous APKs, Gradle build directories, local SDK paths, and keystores.
- Included JVM regression tests for trip resolution, ticket-time behavior, station time zones, midnight service dates, API coordination, SMS parsing, and tracking-session claims.
- Included Android tests for independent Room rows, navigation-state restoration, and ticket automation-switch visibility/default state.

## Android build limitation

This environment has no Android SDK, so Gradle Android compilation, lint, and device tests cannot be executed here. Run:

```bash
./build.sh
```

Then execute connected tests on an emulator or device:

```bash
./gradlew connectedDebugAndroidTest
```

Expected debug output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Required device checks before release

- Process dead/backgrounded at T-60, Doze, Battery Saver, and exact-alarm permission revocation.
- Notification permission or tracking-channel revocation during a live journey.
- Reboot, application update, clock change, and time-zone change.
- Completed/cancelled journey followed by immediate reconciliation.
- Disabling or deleting the actively tracked ticket.
- Duplicate and progressively updated messaging notifications.
- Concurrent manual and periodic inbox scans.
- Station boards spanning midnight.
- Two overlapping tickets and a delayed first trip.
- HTTP 429 with delta-seconds and HTTP-date `Retry-After`.
- Long journeys and foreground-service timeout behavior.
