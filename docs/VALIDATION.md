# Validation report

## Completed in this environment

- Parsed all 14 Android XML files, including the main and inbox-flavor manifests.
- Verified `READ_SMS` is declared only in `app/src/inbox/AndroidManifest.xml` and is absent from the Play-safe main manifest.
- Verified the application contains no legacy API URL/key settings code.
- Verified none of the captured CP header values are embedded as source constants.
- Verified automatic CP configuration uses `https://www.cp.pt/fe-config.json` and maps:
  - `travelApiUrl` and `travelApiKey` to travel calls;
  - `stationsApiUrl` and `stationsApiKey` to station-information calls;
  - `xcck` and `xccs` to all CP API calls.
- Verified the configuration cache is Android Keystore-backed, has a 24-hour lifetime, falls back to stale data during a configuration outage, and is forced to refresh after HTTP 401/403.
- Added a JVM unit test for the observed `fe-config.json` shape.
- Verified all referenced drawables exist.
- Scanned the Kotlin source with the Kotlin compiler parser; no syntax/parser errors were found. Android/Compose symbols remain unresolved without the Android dependency classpath, as expected.
- Verified the corrected `build.sh` with a simulated Gradle executable. It discovers `:app`, invokes both unit-test and APK variants, and finds both resulting APK paths.
- Verified `build.sh` shell syntax.
- Preserved the existing passenger-state, status-chip, and SMS parser unit tests.

## Not completed here

A complete Android Gradle build was not possible because this execution environment has neither an Android SDK nor external DNS access for downloading the SDK and Maven/Google dependencies.

Run on the configured build VM:

```bash
./build.sh
```

The script builds and tests both variants:

```text
:app:testPlayDebugUnitTest
:app:testInboxDebugUnitTest
:app:assemblePlayDebug
:app:assembleInboxDebug
```

Expected APKs:

```text
app/build/outputs/apk/play/debug/app-play-debug.apk
app/build/outputs/apk/inbox/debug/app-inbox-debug.apk
```

Device validation must include:

- First launch with no cached `fe-config.json`.
- Launch with a cached configuration and no network.
- Configuration refresh after 24 hours.
- Simulated HTTP 401/403 followed by configuration refresh and one retry.
- Station catalogue, train catalogue, station board, station details, and train timetable calls.
- Share and Paste SMS imports.
- Google Messages and Samsung Messages notification layouts.
- Notification access enable/revoke behavior.
- Sender-title filtering for `CP`.
- The installer/device-policy behavior required for hard-restricted `READ_SMS` in the inbox variant.
- Android 16 Live Update behavior and standard notification fallback.
