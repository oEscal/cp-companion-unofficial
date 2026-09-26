# Device validation runbook

Use synthetic ticket references and legitimate timetable requests. Do not induce rate limits against CP. Record app commit/version, Android/API version, manufacturer, battery/permission settings, expected result, actual result, and pass/fail. Redact any attached evidence.

## Automated checks

```bash
adb devices
./gradlew :app:connectedDebugAndroidTest
```

CI executes tests on API 30 and 36. Tests cover navigation encoding, Room row independence, ticket automation controls, and encrypted storage. Compilation of the test APK alone is not execution. Emulator tests do not certify OEM scheduling behavior.

## Real-device acceptance matrix

All rows below remain pending until supported by an observed result; use Android 11 and recent Android 16/17 devices where available.

| Scenario | Procedure | Expected result | Result |
| --- | --- | --- | --- |
| T-60 activation | Save a valid future journey, leave the app before activation | Tracking begins near departure minus one hour, with visible notification | Pending |
| Process death / Doze | Schedule, background the app, kill its background process; use a test device's idle controls | Schedule remains recoverable; no duplicate service or notification | Pending |
| Battery Saver / OEM restrictions | Repeat with Battery Saver and manufacturer restrictions enabled | Record actual delay; no crash or silent claim of guaranteed activation | Pending |
| Exact alarm revocation | Schedule, revoke exact-alarm access, then return to app | Fallback/reconciliation remains available and state reflects permission | Pending |
| Reboot / update | Schedule and reboot, then repeat across an in-place same-key update | Future activation survives without duplicate work | Pending |
| Clock / time zone | Change device zone and clock on a test device; restore afterward | Passenger service time stays correct and alarms reconcile | Pending |
| Notification revocation | Revoke permission or disable channel during tracking | App handles inability to show notification without crashing | Pending |
| Overlapping journeys | Enable two journeys, including a delayed first journey | Notification/service ownership transfers; neither finishes from schedule alone | Pending |
| Delay / missing times / terminal disruption | Exercise synthetic fixtures for missing ETD/ETA, delay, cancellation, and destination arrival | Last-known data is bounded; completion requires appropriate evidence | Pending |
| 429 / outage | Use local fixtures for both Retry-After forms and temporary network loss | Shared cooldown, no retry storm, bounded stale data | Pending |
| Notification imports | Repeat/update default-SMS notifications; use unrelated-app notifications too | Imports deduplicate; unrelated packages are ignored | Pending |
| Promoted notification / long route | Test Android 16/17 with promotion enabled/disabled and more than four calling points | Readable fallback and promoted presentation where supported | Pending |
| Privacy / deletion | View bundled policies; delete journey, revoke import access, clear app storage | Documents work offline; deletion behaves as described in PRIVACY.md | Pending |

## Signed upgrade acceptance

1. Verify both APKs with `apksigner verify --verbose --print-certs`; the release certificate must match the published fingerprint.
2. Install the previous signed beta on a test device. Add invented ticket references, a future journey, and preferences. Record expected values privately.
3. Install the new signed APK using `adb -s SERIAL install -r NEW.apk` (or Android's installer), without clearing data.
4. Verify tickets/preferences, encrypted data readability, future scheduling, and deletion. Launch, background, reboot, and verify again.
5. Record both versions, certificate fingerprint, device/API, and observations. Debug-to-release replacement is not an upgrade test because the certificates differ.

A repeat install of the same build checks package replacement only. It is not evidence of compatibility with a previous public release or of Android manufacturer behavior.
