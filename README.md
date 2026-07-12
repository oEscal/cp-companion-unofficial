# CP Companion

Unofficial native Android client for CP station boards, train-trip details, future tickets, SMS ticket import, and explicit live train tracking.

## Implemented application

- Kotlin and Jetpack Compose.
- **Material 3 Expressive**, using `MaterialExpressiveTheme` from Material 3 `1.5.0-alpha23`.
- Station/train search and locally cached catalogues.
- Station arrivals and departures.
- Train calling points, delays, expected times, platforms, and cancellations.
- Future-ticket storage, validation, and a reminder one hour before departure.
- Parsing of CP one-way and return-ticket SMS messages.
- One explicit tracked passenger journey with 10-second active polling.
- Android 16 progress-centric Live Update request with a complete standard ongoing-notification fallback.

See [`docs/IMPLEMENTATION_STATUS.md`](docs/IMPLEMENTATION_STATUS.md) for the exact implemented and deferred scope.

See [`docs/SMS_IMPORT.md`](docs/SMS_IMPORT.md) for SMS parsing and privacy details.

## CP SMS ticket import

The parser supports messages such as:

```text
Ida 2026-07-12: IC 721 - Coimbra-B - 11h35 >> Aveiro - 12h02 ; Bilhete 0002-23456789: Ida IC 721 Car:21 Lug:95 Preco 0,00Eur
```

It also supports return journeys and legs where `Car`/`Lug` are absent. Each journey leg becomes an independent ticket identified by ticket reference, direction, date, and train number.

Three import paths are included:

1. **Share from the SMS app** — share the complete message as text and choose CP Companion.
2. **Paste SMS** — paste the complete message in the Tickets screen.
3. **Detect new CP message notifications** — optionally enable Android notification access in Settings. The listener accepts message-category notifications only, recognizes CP sender-title variants, parses locally, and does not upload notification content.

The app declares `READ_SMS` so it can scan the device inbox after the user explicitly chooses **Check CP SMS**. It checks a bounded recent window on the first run and, on later checks, stops at the most recently imported ticket SMS. Recognized tickets are retained locally. Android and installers may restrict this permission, so the app also supports Share, Paste, and notification-based detection.

Imported tickets resolve station names against the cached CP station catalogue. If the catalogue is not available yet, the ticket remains saved with its station names and is resolved after a later catalogue refresh. Tracking is blocked until both station codes are resolved.

## Interaction updates in 0.4.0

- Train service dates use a Material 3 calendar dialog rather than keyboard entry.
- Pull down on any main or detail page to refresh that page.
- System back gestures and toolbar back actions share a real navigation history.
- Trip calling points and status values use primary, secondary, tertiary, error, and surface roles from the active dynamic theme.

See [`docs/CHANGELOG_0.4.md`](docs/CHANGELOG_0.4.md) for the complete change list.

## Automatic CP configuration

No API setup is required. At startup, the app downloads the public runtime configuration used by the CP website:

```text
https://www.cp.pt/fe-config.json
```

From that document it obtains the current travel/station service URLs and the headers used by the website client. The configuration is encrypted in local storage, reused while offline, refreshed every 24 hours, and refreshed immediately after an HTTP 401 or 403 response.

The app then calls the observed read-only CP endpoints directly. Users never enter an API URL, key, connect ID, or connect secret. Browser-only headers such as `Sec-Fetch-*` are not required by a native Android client; the CP `Origin` and `Referer` values are added centrally to mirror the website request.

## Build

The single app includes inbox scanning, Share, Paste, and notification-based detection.

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Requirements:

- JDK 17 or newer.
- Android SDK platform 37.
- Android Build Tools 36.0.0 or newer.
- Gradle 9.4.1.

Run `./build.sh`. It uses the checked-in wrapper when complete or downloads and checksum-verifies Gradle 9.4.1 automatically.

## Notification behavior

Tracking starts only after a direct user action and runs as a `dataSync` foreground service.

- Android 16+: the app uses `Notification.ProgressStyle`, marks the notification ongoing, requests promoted ongoing behavior, and supplies status-chip time/critical text.
- Earlier Android or unavailable promotion: the same state is rendered as a silent standard ongoing `BigTextStyle` notification.
- The full notification always includes scheduled/expected times, delay, platform, and carriage/seat where available.
- At five minutes before the origin event, the UI and notification enter boarding-soon mode and prioritize carriage/seat.
- Once on board, the countdown targets the ticket destination.

`dataSync` foreground services have platform time limits on newer Android versions. Long-duration production use must be validated against the target Android and Play policy before release.

## Privacy and safety model

- SMS parsing occurs on-device.
- Inbox scanning is requested only after the user chooses it; Share, Paste, and notification access remain available when it is denied.
- Notification access is optional, requires a prominent disclosure, and is controlled in Android system settings.
- The notification listener rejects notifications explicitly categorized as non-message content and ignores notifications outside recognized CP sender-title variants. Some Android versions, devices, lock-screen privacy settings, or messaging apps may redact the SMS body; Share and Paste remain the reliable fallbacks.
- No SMS bodies, ticket references, QR payloads, CP configuration values, or passenger names are logged or uploaded.
- Read-only observed timetable endpoints only.
- No login, purchase, or ticket-account API is guessed.
- A train catalogue row is not treated as a live vehicle.
- A concrete run uses train number plus service date; passenger tracking also requires origin and destination station codes.
- Station coordinates are never presented as the train's current location.
