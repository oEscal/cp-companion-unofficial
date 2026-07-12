# Delivery summary

This archive contains a greenfield native Android implementation because the supplied project scaffold had no application source files.

## Working source included

- Material 3 Expressive Compose UI.
- Station and train catalogue search.
- Station arrival/departure boards.
- Train timetable and passenger-segment details.
- Manual future-ticket storage and validation.
- CP SMS parsing for one-way and return journeys.
- Share-target and paste-based SMS import.
- Opt-in notification-listener detection for newly posted CP message notifications.
- One opt-in inbox scan, alongside Share, Paste, and notification-based ticket detection.
- Deferred station-code resolution when SMS import occurs before the station catalogue is available.
- One-hour future-trip reminders.
- Explicit one-train foreground tracking with approximately 10-second polling.
- Android 16 progress-centric Live Update requests.
- Standard ongoing-notification fallback.
- Platform/countdown, carriage/seat, and destination-countdown status-chip formatting.
- Encrypted on-device CP runtime-configuration and ticket storage.
- Core domain and SMS parser unit tests plus standalone JVM smoke tests.

## Automatic CP configuration

The app requires no API settings. It obtains the same public runtime configuration as the CP website from `https://www.cp.pt/fe-config.json`, caches it securely, refreshes it daily, and retries with a fresh configuration after HTTP 401/403 responses.

Automatic notification-based SMS detection still requires the user to read the prominent disclosure and explicitly enable notification access in Android settings. Android or the messaging app may redact sensitive notification content, so existing or missed messages can always be shared or pasted without SMS permissions.

## Deferred

Authenticated CP account ticket synchronization was not implemented because no verified ticket/account contract was available.

The app asks for `READ_SMS` only when inbox import is selected. Android or an installer may restrict it; Share, Paste, and notification-based detection remain available in that case.

## Build validation

XML, source-structure, domain-state, status-chip, and SMS-parser validation passed. A full Android Gradle build was not possible in the delivery environment because it lacked the Android SDK, Gradle, the supplied wrapper JAR, and dependency-download access. See `VALIDATION.md`.
