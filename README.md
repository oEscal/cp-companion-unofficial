# CP Companion 0.7.0

Unofficial native Android application for CP station boards, train-trip details, locally imported passenger tickets, and automatic live journey tracking.

## Current behavior

Validated future tickets enable automatic tracking by default. The app schedules activation for one hour before the passenger departure, recovers scheduling after reboot, application update, clock/time-zone changes, or exact-alarm permission changes, and starts an ongoing journey notification without requiring a manual **Track now** action. Polling adapts to journey phase, shares identical requests, retains bounded stale data during CP outages or HTTP 429 cooldowns, and stops after a confirmed destination arrival or terminal disruption.

Recent scheduled arrivals are not treated as proof that a delayed train has completed. The automation layer keeps a six-hour verification window and uses live CP data before terminalizing the journey.

The project contains one application with application ID `pt.cpcompanion`. Direct SMS inbox import, confirmed paste/share import, and default-SMS-app notification detection are included in the same app. Inbox access remains opt-in at runtime.

## 0.7.0 finalization

- Applies Material 3 Expressive globally through `MaterialExpressiveTheme`, expressive shapes/typography, dynamic color, and System/Light/Dark modes.
- Updates Material 3 Expressive to `1.5.0-alpha24` and AndroidX Concurrent to `1.3.0`.
- Fixes delayed-trip automation being completed solely from scheduled arrival time.
- Preserves coroutine cancellation in workers, validation, imports, and UI loading paths instead of turning cancellation into retries or errors.
- Makes catalogue persistence atomic and self-healing after interrupted or corrupt writes.
- Hardens encrypted-value decoding and removes only unreadable entries after key invalidation or restore damage.
- Enforces HTTPS-only CP traffic, exact gateway host/port/path validation, and rejects credentials, queries, and fragments in runtime service URLs.
- Adds explicit backup/device-transfer exclusions and keeps Android backup disabled for ticket and tracking data.
- Validates exported activity input, requires confirmation before importing shared text, restricts notification detection to the default SMS app, and bounds shared/deep-link/notification input.
- Makes the global HTTP 429 cooldown update atomic and bounds `Retry-After` to 24 hours.
- Removes journey/ticket identifiers from notification diagnostic logging.
- Verifies fallback Gradle downloads and pins CI actions to immutable revisions.
- Completes English/Portuguese resource parity for the modified search, trip-status, automation, and error surfaces.
- Adds source validation, archive exclusions, and an Android CI workflow.

See `docs/CHANGELOG_0.7.0.md`, `docs/IMPLEMENTATION_STATUS.md`, `docs/VALIDATION.md`, and `TODO.md`.

## Build

Requirements:

- JDK 17 or newer
- Python 3 for source and checksum validation
- Android SDK platform 37
- Android Build Tools 36.0.0 or a compatible newer version
- Network access for uncached Gradle and Maven dependencies

Run:

```bash
./build.sh
```

The script first validates XML, resources, translation parity, and security/build invariants. It then runs:

```text
:app:testDebugUnitTest
:app:lintDebug
:app:assembleDebugAndroidTest
:app:assembleDebug
:app:assembleRelease
```

After intentional source changes, refresh and verify the delivery checksums with:

```bash
python3 tools/update_source_checksums.py
python3 tools/validate_source.py
```

The archive intentionally does not contain generated build output. When the Gradle wrapper JAR is absent, `build.sh` downloads Gradle 9.4.1 and verifies the official SHA-256 checksum before execution.

Expected debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

To run connected tests after building:

```bash
./build.sh connectedDebugAndroidTest
```

## Optional release signing

Set all four variables before running `./build.sh`:

```text
CP_RELEASE_STORE_FILE
CP_RELEASE_STORE_PASSWORD
CP_RELEASE_KEY_ALIAS
CP_RELEASE_KEY_PASSWORD
```

Without them, Gradle can validate release compilation and shrinking, but the release artifact is not suitable for distribution.

## Permissions and privacy

- `READ_SMS` is requested only when the user checks the inbox or enables automatic inbox import.
- `POST_NOTIFICATIONS` is required for visible ongoing journey tracking.
- `SCHEDULE_EXACT_ALARM` is used for preferred T-60 activation; inexact AlarmManager and WorkManager recovery remain available.
- Notification-listener access is optional and protected by Android's binding permission.
- Shared ticket text requires confirmation before it is saved, and automatic notification detection accepts candidates only from the user-selected default SMS app.
- Tracking notifications are private on the lock screen; full route, platform, carriage, and seat details remain available after the device applies the user's notification privacy settings.
- SMS and notification parsing are local. The app does not log SMS bodies, passenger names, ticket references, QR contents, or CP runtime headers.
- The app uses observed read-only CP timetable/configuration operations. Authenticated account, payment, ticket-purchase, card, and QR operations remain absent until their contract and authorization are verified.
