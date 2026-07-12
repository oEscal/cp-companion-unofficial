# Implementation status

## Implemented

- Native Kotlin/Jetpack Compose single-activity application.
- Material 3 Expressive theme using `MaterialExpressiveTheme`, expanded shape tokens, and the Expressive morphing `LoadingIndicator` from Material 3 1.5.0-alpha23.
- Dynamic color, expressive shapes, typography, cards, navigation, and motion-aware screen transitions.
- Zero-configuration CP API bootstrap from the public `fe-config.json`, with Android Keystore-backed caching, 24-hour refresh, separate travel/station API keys, and automatic 401/403 refresh.
- Station and train catalogues with local file caching and local search.
- Station detail plus arrival/departure boards.
- Train trip detail with ordered calling points, scheduled/expected times, delay, platform, and cancellation state.
- Manual future-ticket storage with train/date/origin/destination/carriage/seat.
- CP SMS parser for one-way and return journeys, including optional carriage/seat per leg.
- Deterministic SMS ticket deduplication using reference, direction, service date, and train number.
- Share-target and paste-based SMS import without SMS permissions.
- Opt-in `NotificationListenerService` for automatic detection of new CP message notifications. It rejects explicit non-message categories, requires the normalized sender title to equal `CP`, and falls back to Share/Paste when notification content is redacted.
- One app with opt-in `READ_SMS` inbox import, plus Share, Paste, and notification-based ticket detection.
- Station-name resolution for SMS tickets, including deferred resolution after the station catalogue becomes available.
- Ticket validation against the trip endpoint and a standard reminder scheduled one hour before the resolved or SMS-provided origin departure.
- Explicit one-train tracking foreground service.
- 10-second successful polling interval with 10/20/30/60-second failure backoff and no overlapping requests.
- Passenger-segment state machine based on stable station codes; origin tracking prefers expected train arrival while reminders use the boarding departure.
- Android 16 `Notification.ProgressStyle`, promoted-ongoing request, compact status-chip platform/countdown and carriage/seat values, and tracker icon changes.
- Standard silent ongoing BigText notification fallback on earlier Android releases or when promoted notifications are disabled.
- Stop action, delete intent, deep-link target, persistence, and automatic completion at destination.
- Unit tests for passenger-phase transitions, compact Live Update chip formatting, and all three supplied SMS structures using synthetic ticket references.

## Deliberately not implemented

- Captured API secrets in source or resources.
- Authenticated CP account/ticket synchronization without a verified contract.
- Journey purchase or any production POST/PUT endpoint.
- Precise live map position inferred from station coordinates.
- Automatic background activation of a future Live Update.
- Multi-train concurrent tracking.
- `RECEIVE_SMS`; the app does not intercept SMS broadcasts.
- Assuming `READ_SMS` is available when Android or the installer denies it; Share, Paste, and notification detection remain fallbacks.

## Validation limitation in this delivery

The source tree should still be validated on a physical device, particularly for `READ_SMS` behavior under the intended installer and Android version.
