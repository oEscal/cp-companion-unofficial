# CP Companion

**Unofficial Android beta for train journeys in Portugal.** View CP station boards and train details, import journey information from tickets, and follow a journey with an ongoing notification.

This project is independent of, and is not endorsed by, CP — Comboios de Portugal. Imported journey records are not travel documents: keep your valid ticket. Live data and automatic background activation can be delayed or unavailable.

## Features

- Station departures/arrivals, train calling points, and journey status.
- Manual entry, confirmed paste/share import, opt-in SMS inbox import, and optional default-SMS-app notification detection.
- Automatic tracking scheduled one hour before departure for validated future journeys, with recovery after reboot and schedule changes.
- Local encrypted ticket storage, English and Portuguese UI, and light/dark/system themes.

Account login, purchases, payments, refunds, and ticket QR/PDF retrieval are not implemented. See [known limitations](docs/KNOWN_LIMITATIONS.md) and [upstream integration](docs/UPSTREAM.md).

## Screenshots

> Screenshot placeholder: add redacted or synthetic screenshots to [docs/screenshots](docs/screenshots/README.md), then replace this block with the image links. Suggested views: station board, journey details, ticket list, and tracking notification.

## Install

Requires **Android 11 (API 30) or newer**. Check [Releases](https://github.com/oEscal/my-cp-companion/releases) for a signed beta APK. If no release is listed, build from source; CI artifacts are development builds.

1. Download `cp-companion.apk` and `SHA256SUMS` from the same release. Compare the APK's SHA-256 with the listed value.
2. Open the APK on Android and allow installation from that browser/file manager when prompted.
3. Grant notifications for visible tracking. SMS inbox, notification-listener, and exact-alarm access are optional and explained in the Automation tab.

Install future releases over the existing app to preserve journeys. Updates must use the same signing certificate. A debug installation uses a different certificate; uninstalling it to install a release deletes its local data. See [privacy and deletion](PRIVACY.md).

## Build

Use JDK 17 (the version used by CI), Python 3.10+, Android SDK Platform 37, and Build Tools 37.0.0. Initial dependency downloads require network access. Linux, macOS, and Windows are supported by the committed Gradle wrapper.

```bash
git clone https://github.com/oEscal/my-cp-companion.git
cd my-cp-companion
```

Open the project in Android Studio and install the requested SDK components, or use the SDK command-line tools. Set `ANDROID_HOME` to the SDK directory, or copy `local.properties.example` to `local.properties` and set `sdk.dir`. The local file is ignored and is allowed during development.

On Linux/macOS:

```bash
./build.sh
```

On Windows (PowerShell):

```powershell
python tools/validate_source.py
python tools/validate_repository.py
.\gradlew.bat :app:exportReleaseDependencies :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest :app:assembleDebug :app:assembleRelease
python tools/generate_notices.py --check
```

The script runs source checks, unit tests, lint, instrumentation APK compilation, debug assembly, release shrinking, and dependency-notice verification. It does not execute device tests without a connected device and the task below. Release output is unsigned unless all four `CP_RELEASE_*` signing variables are configured.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

```bash
./gradlew :app:connectedDebugAndroidTest
```

CI runs instrumentation tests on Android API 30 and 36 emulators. See [validation evidence](docs/VALIDATION.md) for the distinction between automated tests and the remaining real-device journey checks.

## Contribute and get help

Read [CONTRIBUTING.md](CONTRIBUTING.md) for architecture, checks, and dependency updates. Report bugs in [Issues](https://github.com/oEscal/my-cp-companion/issues), including app/device versions and reproduction steps. Use synthetic journeys and redact screenshots; never post real ticket references, SMS text, passenger details, QR codes, or credentials.

Read [SECURITY.md](SECURITY.md) before reporting a vulnerability. There is no guaranteed response time for this volunteer beta project.

## Privacy and attribution

SMS and notification parsing happen locally. Requests for station/train data go directly to CP, which receives the requested train/station/date and connection metadata. Read the [privacy policy](PRIVACY.md); it and third-party notices are also available offline in the Automation tab.

Third-party licenses are preserved separately from the maintainer-selected project license. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). CP names, marks, and upstream data are not relicensed by this repository.

Maintainers: follow the [signed release guide](docs/RELEASING.md) and [known limitations](docs/KNOWN_LIMITATIONS.md).
