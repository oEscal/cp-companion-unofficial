# CP Companion 0.6.0

Unofficial native Android application for CP station boards, train-trip details, passenger tickets, SMS ticket import, and automatic live journey tracking.

## Core behavior

Every validated future ticket has automatic tracking enabled by default. The app schedules activation for one hour before the passenger's departure, starts an ongoing notification without requiring a Track action, adapts polling between approximately 10 and 60 seconds, and stops after a confirmed destination arrival or terminal disruption.

The automation switch is shown only while a ticket is still in the future. Once departure has passed, the switch is removed and the ticket cannot accidentally remain enabled as historical work. **Track now** remains an optional early-start action for future tickets.

This repository builds one application with the application ID `pt.cpcompanion`. Direct SMS inbox import, paste/share import, and notification-listener import are included in the same app. Inbox access remains opt-in at runtime.

## 0.6.0 changes

- Prevents completed or cancelled journeys from being reactivated by reconciliation.
- Removes stale notification actions from terminal journeys and safely handles actions from detached notifications.
- Stops an active service immediately when automation is disabled or its ticket is deleted.
- Hides automation controls for departed and past tickets while preserving the default-on behavior for future tickets.
- Serializes manual and periodic SMS scans and atomically merges cursor/settings changes.
- Resolves station-board service dates correctly across midnight.
- Stores each ticket as an independently encrypted Room row instead of one encrypted JSON collection.
- Stores preferences in DataStore and orders persistence operations to avoid stale writes winning races.
- Restores navigation routes through Navigation Compose and saved state.
- Splits the Compose UI into Home, Search, Station, Trip, Tickets, and Settings features.
- Localizes the main UI, accessibility labels, channels, and journey notifications in English and Portuguese.
- Adds Room, navigation-state, ticket-automation, midnight-date, tracking, SMS, journey-state, and request-coordination tests.
- Adds CI and optional environment-based release signing.

The existing AlarmManager/WorkManager T-60 recovery, CP request coalescing, bounded caches, global HTTP 429 cooldown, adaptive polling, station time-zone handling, and System/Light/Dark themes remain in place.

## Build

Requirements:

- JDK 17 or newer
- Android SDK platform 37
- Compatible Android Build Tools
- Network access for uncached Gradle/Maven dependencies

Run:

```bash
./build.sh
```

The script runs:

```text
:app:testDebugUnitTest
:app:lintDebug
:app:assembleDebugAndroidTest
:app:assembleDebug
:app:assembleRelease
```

Expected debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Debug and release use the same application ID. Installing one replaces the other.

To execute the device tests after building:

```bash
./gradlew connectedDebugAndroidTest
```

## Optional release signing

Set all four variables before running `./build.sh`:

```text
CP_RELEASE_STORE_FILE
CP_RELEASE_STORE_PASSWORD
CP_RELEASE_KEY_ALIAS
CP_RELEASE_KEY_PASSWORD
```

Without them, Gradle still verifies release compilation and shrinking but does not produce a distributable signed release APK.

## Permissions

- `READ_SMS` is requested only when the user checks the inbox or enables automatic inbox import.
- `POST_NOTIFICATIONS` is required for visible ongoing journey tracking.
- `SCHEDULE_EXACT_ALARM` is used for preferred T-60 activation. Inexact AlarmManager and WorkManager recovery remain available when exact access is unavailable.

## Privacy

SMS parsing is local. The app does not log SMS bodies, passenger names, ticket references, QR contents, or CP runtime headers. It uses read-only observed CP timetable/configuration endpoints and does not implement authenticated account or ticket-purchase operations.

See `docs/CHANGELOG_0.6.0.md`, `docs/IMPLEMENTATION_STATUS.md`, and `docs/VALIDATION.md`.
