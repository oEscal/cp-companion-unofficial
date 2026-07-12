# Historical implementation plan

> Retained for traceability; current behavior is documented in `docs/IMPLEMENTATION_STATUS.md`.

> **Historical input plan:** This file came from the initial archive and records earlier constraints.
> The implemented scope follows the later user requirements and is documented in
> `README.md`, `TODO.md`, and `docs/IMPLEMENTATION_STATUS.md`.

# CP Android Application — Implementation Plan

## 1. Objective

Build a native Android application in Kotlin for CP live train tracking. The application must allow a user to:

- Search the captured CP station catalogue.
- Select a station and read its current arrivals or departures board.
- Select a train and inspect its captured live-status timetable.
- Explicitly track a selected train with an ongoing Android notification that refreshes its status.
- Leave a clear extension point for future ticket-derived train selection, without implementing ticket, account, payment, or CP user authentication flows.
- Use Android Live Update notifications and progress-centric notifications on supported devices.
- Fall back cleanly to standard ongoing notifications on unsupported devices or when promotion is unavailable.

The application is a native Android application. Do not use Flutter, React Native, or a web-wrapper architecture.

## 2. Ground rules for Codex

1. Use Kotlin, coroutines, Flow, and Jetpack Compose.
2. Use Material 3 Expressive and current stable AndroidX components.
3. Use `compileSdk = 37` and `targetSdk = 37` unless the locally installed stable SDK requires a temporary adjustment. This workspace uses API 36 because its API 37 platform is not installable as a stable SDK.
4. Use `minSdk = 26` unless a dependency forces a higher minimum.
5. Prefer the latest stable library versions available in the configured repositories. Do not use alpha dependencies when a stable alternative exists.
6. Navigation must use stable Navigation 3.
7. Use edge-to-edge layouts, predictive back, adaptive navigation, and adaptive list-detail layouts.
8. Never invent a CP endpoint, header, cookie, request parameter, response field, authentication step, or payment step. Derive these only from the reverse-engineering material provided in the repository.
9. Never commit access tokens, passwords, session cookies, payment details, QR payloads, or unredacted captured traffic.
10. Do not log credentials, authorization headers, cookies, complete account objects, ticket QR payloads, or card QR payloads.
11. Keep all provider-specific behavior behind interfaces so endpoint changes do not affect the UI or domain logic.
12. Add tests with every feature. Do not leave core logic as untested TODOs.
13. Use small, independently buildable changes. The project must compile and tests must pass after each milestone.
14. Do not copy CP application source code, proprietary assets, signing material, or private keys. If this is an unofficial client, use neutral branding and include a clear non-affiliation notice.


## 3A. Observed OpenAPI contract supplied for this project


### Observed hosts

```text
https://login.cp.pt
https://api-gateway.cp.pt
```

### Removed authentication operations

```text
GET  https://login.cp.pt/realms/cpclients/protocol/openid-connect/auth
POST https://login.cp.pt/realms/cpclients/login-actions/authenticate
POST https://login.cp.pt/realms/cpclients/protocol/openid-connect/token
```

This tracking-only build does not implement these operations or store any CP user authentication material. The following notes are retained as reverse-engineering evidence only:

- Use the observed OpenID Connect Authorization Code flow with PKCE.
- Open authorization in a Custom Tab through AppAuth for Android or an equivalent maintained OIDC client.
- Do not reproduce the Keycloak HTML login form as a native username/password request.
- Do not call `login-actions/authenticate` directly from application code. It is an internal browser-flow action whose fields, cookies, and execution identifiers can change.
- Capture and document the exact `client_id`, redirect URI, scopes, PKCE parameters, token response, refresh behavior, and logout behavior before implementing production authentication.
- Register and validate the application redirect URI.
- Store refresh material only in Keystore-backed encrypted storage.

### Travel and live-information operations

```text
POST /cp/services/travel-api/journeys
GET  /cp/services/travel-api/stations
GET  /cp/services/travel-api/codes/classes
GET  /cp/services/travel-api/rules
GET  /cp/services/travel-api/stations/{stationId}/timetable/{date}
GET  /cp/services/travel-api/trains/{trainNumber}/timetable/{date}
GET  /cp/services/stations-api/stations/infos/{stationId}
```

Initial feature mapping:

| Application capability | Observed operation |
|---|---|
| Station catalogue/search | `GET /travel-api/stations` |
| Journey search | `POST /travel-api/journeys` |
| Travel classes | `GET /travel-api/codes/classes` |
| Travel rules | `GET /travel-api/rules` |
| Current arrivals/departures at a station | `GET /stations/{stationId}/timetable/{date}` |
| Train calling points and live timetable | `GET /trains/{trainNumber}/timetable/{date}` |
| Station details | `GET /stations-api/stations/infos/{stationId}` |

For the first implementation, `GET /trains/{trainNumber}/timetable/{date}` is the only evidenced train-number-specific tracking operation. Do not create multiple train endpoint adapters or category-specific routing rules until another capture proves that they exist.

Before live tracking can be considered implemented, collect redacted response fixtures proving which fields represent:

- Train number and service date.
- Complete station-call order.
- Service-origin station.
- Ticket boarding station.
- Ticket destination station.
- Scheduled arrival and departure.
- Expected arrival and departure.
- Actual arrival and departure, if supplied.
- Platform or line.
- Delay.
- Cancellation or suppression.
- Last-update timestamp.
- Train category and public display name.
- Stable station identifiers.

The tracking adapter should resolve the ticket's boarding and destination calls by stable station ID. The service-origin station should normally be obtained from the train timetable's first call. Do not assume that the passenger's boarding station is the train's service origin.

### MyCP operations

```text
GET /cp/services/mycp-api/v1/users/{userId}
GET /cp/services/mycp-api/v1/users/{userId}/avatar
GET /cp/services/mycp-api/v1/users/{userId}/preferences
GET /cp/services/mycp-api/v1/preferences
GET /cp/services/mycp-api/v1/user-requests/user/{userId}
GET /cp/services/mycp-api/v1/enums/{resource}
```

These operations support an initial account/profile implementation. Capture the successful response bodies and determine how `userId` is obtained from the OIDC token or a bootstrap response.

The observed contract does **not** identify an operation for:

- A CP customer card.
- Card validity and entitlement details.
- A card QR code.
- Refreshing a dynamic card QR code.

Do not infer those features from the user-profile endpoints. Keep `CardRepository` and the card screens backed by a fake implementation until a separate capture identifies the exact operations and payloads.

### Ticket purchase operations

```text
POST /cp/services/ticketing-api/sale
GET  /cp/services/ticketing-api/sale/configuration/{resource}
GET  /cp/services/ticketing-api/sale/{saleId}
PUT  /cp/services/ticketing-api/sale/{saleId}/{resource}
GET  /cp/services/ticketing-api/sale/{saleId}/items/available
GET  /cp/services/ticketing-api/sales/{saleId}
GET  /cp/services/ticketing-api/sales/{saleId}/available-operations
PUT  /cp/services/ticketing-api/train-seats/{saleId}
GET  /cp/services/ticketing-api/train-seats/{saleId}/trains/{trainNumber}
```

Model the purchase flow as a server-owned sale state machine. Based on the observed resource names, likely stages include:

```text
create sale
-> load available items
-> update items
-> update passengers
-> update client
-> update fiscal information
-> select seats
-> confirm
-> fetch final sale
```

This sequence is only a working hypothesis. Codex must derive the actual order, required bodies, concurrency behavior, and success conditions from captures. It must not send guessed mutations to production.

For every sale mutation, capture:

- The sale state immediately before and after the request.
- Required headers.
- Request body.
- Response body.
- Whether the operation is repeatable.
- Whether a version, ETag, nonce, or anti-CSRF value is required.
- What proves that payment and ticket issuance succeeded.
- How hosted payment, redirect, 3-D Secure, cancellation, and timeout are represented.

### Trips and e-tickets

```text
GET /cp/services/ticketing-api/trips/{email}
GET /cp/services/ticketing-api/trips-count/{email}
GET /cp/services/ticketing-api/e-ticket/{saleId}/{ticketReference}/{ticketId}
```

Use `trips/{email}` to discover account trips only after verifying that the authenticated account is authorized to access the supplied email. URL-encode the path segment and never log the full URL in release builds because it contains personal data.

The trip, final-sale, and e-ticket captures must establish where the following values are found:

- Ticket ID.
- Sale ID.
- Ticket reference.
- Passenger.
- Journey legs.
- Train number per leg.
- Service date.
- Boarding and destination station IDs.
- Scheduled times.
- Carriage and seat.
- Ticket QR or barcode payload.
- Refundability and allowed operations.

Do not parse a rendered ticket image or PDF when structured values are available in JSON.

### Post-sale operations

```text
POST /cp/services/ticketing-api/post-sale/refund
GET  /cp/services/ticketing-api/post-sale/refund/{refundId}
PUT  /cp/services/ticketing-api/post-sale/refund/{refundId}
GET  /cp/services/ticketing-api/post-sale/refund/available/tickets/{saleId}
GET  /cp/services/ticketing-api/post-sale/ownership/{saleId}/passengers/{passengerIndex}
```

Refunds and ownership changes are not required for the first minimum viable release. Keep them in a later milestone after purchase, ticket sync, and tracking are reliable.

### Observed authentication headers

The contract identifies:

```text
X-Access-Token
X-Api-Key
```

`X-Access-Token` is attached to the observed MyCP and ticketing groups. `X-Api-Key` is declared but not attached to an operation in the supplied document. Before implementation, determine:

- Which operations require each header.
- Whether the API key is public application configuration or confidential server-side material.
- Whether a cookie, bearer token, CSRF value, or additional application header is also required.
- How token expiry is signalled.
- Whether token refresh changes the `X-Access-Token` value.

Never embed a confidential API credential in the APK. Values recoverable from the official web client should still be treated as configuration whose use may be restricted.

## 3B. OpenAPI normalization before code generation

Do not feed the supplied document directly into Retrofit/OpenAPI code generation without correction.

Known structural limitations:

1. Response and request schemas are generic `object` values, so generated DTOs would provide no useful type safety.
2. Reusable operations are stored under `components.x-operations`. This is an extension, not a standard reusable OpenAPI component category, and many generators will ignore it.
3. Some generic operation references attach path parameters that do not exist on the concrete path. Examples include a `saleId` parameter on `POST /sale`, a `userId` parameter on `/v1/preferences`, and a `refundId` parameter on `POST /post-sale/refund`.
4. Only successful responses are described. Authentication, validation, conflict, rate-limit, and server errors remain unknown.
5. The `X-Api-Key` security scheme is declared without an evidenced operation using it.
6. Request bodies for sale creation, updates, journey search, seats, and refunds remain unspecified.

Required normalization process:

```text
observed HAR
-> redacted request/response fixtures
-> corrected operation-specific OpenAPI
-> schema validation
-> generated or handwritten DTOs
-> MockWebServer contract tests
-> production adapter
```

Create `docs/api/cp-normalized.openapi.yaml` only after the exact request and response fixtures are available. Prefer handwritten Kotlin DTOs during early reverse engineering because they make optionality and uncertain fields explicit.

Mark each operation and field with one of:

```text
OBSERVED
INFERRED
UNKNOWN
```

Only `OBSERVED` fields may be required in production DTOs. `INFERRED` fields must remain nullable and covered by fixtures. `UNKNOWN` behavior must not be implemented by guessing.

## 3C. Live-tracking integration based on the observed contract

The first live-tracking implementation should use this data path:

```text
synced ticket or final sale
-> select current ticket leg
-> obtain train number and service date
-> obtain boarding and destination station IDs
-> GET /travel-api/trains/{trainNumber}/timetable/{date}
-> match station calls by stable ID
-> normalize scheduled/expected/actual values
-> update tracking state and notification
```

Fallback and validation path:

```text
GET /travel-api/stations/{stationId}/timetable/{date}
```

Use the station timetable only to validate or supplement the boarding-station view when the train timetable is incomplete. Do not merge conflicting responses silently. Record which endpoint supplied each normalized value and prefer the response with a provider timestamp or clearly stronger semantics.

Polling rules specific to these operations:

- Use one request every approximately 10 seconds only during the active foreground tracking window.
- Cache station metadata separately; do not refetch it every cycle.
- Do not refetch ticket, e-ticket, or account data every 10 seconds.
- Stop polling after the destination call completes or the trip is cancelled.
- Back off on `429`, `5xx`, network loss, or repeated malformed responses.
- Honor `Retry-After`.
- Add random jitter when recovering from an outage so multiple clients do not synchronize.
- Never poll production APIs from unit or UI tests.
- Add a developer-configurable polling interval for mock builds only.

## 4. Recommended project structure

```text
app/
build-logic/
core/
  common/
  model/
  network/
  database/
  datastore/
  designsystem/
  notifications/
  testing/
feature/
  authentication/
  home/
  journeysearch/
  purchase/
  trips/
  traindetails/
  livetracking/
  card/
  account/
docs/
```

### Module responsibilities

- `app`: application entry point, dependency graph, top-level navigation, deep links, notification intents.
- `core:model`: provider-independent domain models.
- `core:network`: HTTP client, authentication interceptors, endpoint DTOs, provider adapters.
- `core:database`: Room entities, DAOs, migrations, and local cache.
- `core:datastore`: user preferences and non-sensitive settings.
- `core:designsystem`: Material 3 Expressive theme, typography, shapes, reusable components, icons.
- `core:notifications`: channels, Live Update construction, fallback notifications, notification actions.
- `core:testing`: fake clock, fake repositories, fixtures, dispatchers, and test utilities.
- `feature:*`: screens, ViewModels, feature-specific use cases, and navigation keys.

Use dependency inversion. Feature modules may depend on domain interfaces, but must not depend directly on Retrofit services, Room DAOs, or provider DTOs.

## 5. Technology choices

Use:

- Kotlin.
- Kotlin coroutines and Flow.
- Jetpack Compose.
- Material 3 and Material 3 Expressive components.
- Material 3 Adaptive.
- Navigation 3.
- Hilt for dependency injection.
- Room for persisted account summaries, stations, tickets, trips, and tracking state.
- DataStore for preferences.
- Retrofit and OkHttp, or an equivalent typed HTTP stack, with Kotlin serialization.
- WorkManager for deferrable and recovery work only.
- AlarmManager for the precise pre-trip activation time when exact-alarm access is available.
- A foreground service for the active 10-second polling session.
- Android Keystore-backed encryption for long-lived authentication material.
- Custom Tabs for hosted authentication, 3-D Secure, or hosted payment pages when the captured production flow uses browser redirects.

Use a Gradle version catalog. Pin versions in `gradle/libs.versions.toml`, but select current stable versions at implementation time.

## 6. Domain model

Create provider-independent models similar to the following:

```kotlin
data class Station(
    val id: StationId,
    val name: String,
    val shortName: String?,
    val code: String?,
    val timeZoneId: String
)

data class TrainServiceKey(
    val trainNumber: String,
    val serviceDate: LocalDate,
    val serviceOriginStationId: StationId?,
    val boardingStationId: StationId,
    val destinationStationId: StationId
)

data class TripLeg(
    val id: TripLegId,
    val train: TrainServiceKey,
    val scheduledDeparture: Instant,
    val scheduledArrival: Instant,
    val carriage: String?,
    val seat: String?,
    val platform: String?
)

data class Ticket(
    val id: TicketId,
    val bookingReference: String,
    val passengerName: String?,
    val legs: List<TripLeg>,
    val ticketQr: QrPayload?,
    val status: TicketStatus
)

data class LiveTrainSnapshot(
    val key: TrainServiceKey,
    val observedAt: Instant,
    val scheduledBoardingTime: Instant,
    val expectedBoardingTime: Instant?,
    val actualBoardingDepartureTime: Instant?,
    val scheduledDestinationTime: Instant,
    val expectedDestinationTime: Instant?,
    val currentOrNextStation: StationCall?,
    val boardingPlatform: String?,
    val delayMinutesAtBoarding: Int?,
    val delayMinutesAtDestination: Int?,
    val cancelled: Boolean,
    val completed: Boolean,
    val rawStatus: String?
)
```

All persisted times must use `Instant`. Convert to `Europe/Lisbon` or the station-provided time zone only at presentation boundaries. Include the service date in every train lookup because train numbers can be reused on different dates.

## 7. API abstraction

Define interfaces before implementing CP-specific adapters:

```kotlin
interface AuthenticationRepository
interface AccountRepository
interface CardRepository
interface StationRepository
interface JourneySearchRepository
interface FareRepository
interface PurchaseRepository
interface TicketRepository
interface CurrentTrainRepository
interface LiveTrainRepository
```

The live train API must expose a single provider-independent function:

```kotlin
suspend fun getLiveTrainSnapshot(key: TrainServiceKey): LiveTrainSnapshot
```

Implement a `TrainEndpointResolver`, but begin with exactly one evidenced production route:

```text
GET /cp/services/travel-api/trains/{trainNumber}/timetable/{date}
```

The resolver receives:

- Train number.
- Service date.
- Ticket boarding station identifier.
- Ticket destination station identifier.

Add train-category, prefix, or service-origin routing only when another capture proves it is required. The resolver must never infer a station from display text when a stable station identifier is available.

### Train lookup resolution order

1. Read the ticket leg.
2. Obtain the train number and service date.
3. Read the boarding and destination station identifiers from the ticket.
4. Request the observed train timetable using train number and service date.
5. Resolve the service-origin station from the first valid station call when needed.
6. Match boarding and destination calls by stable station ID.
7. Build or complete `TrainServiceKey`.
8. Normalize the live status.
9. Reject ambiguous matches instead of silently choosing the first result.

Add contract tests using redacted captured JSON fixtures. Network DTO tests must prove that every known response variant can be decoded.

## 8. Authentication and session handling

Implement the captured CP authentication flow exactly.

Requirements:

- Do not store the user's password after authentication.
- Persist only the minimum session/refresh material needed.
- Encrypt long-lived secrets using Android Keystore-backed storage.
- Refresh sessions centrally in an OkHttp authenticator or repository-level session manager.
- Serialize refresh operations so concurrent 401 responses do not trigger multiple refreshes.
- Clear local account, card, ticket, and QR data on logout.
- Redact authentication values from logs and crash reports.
- Provide a recoverable reauthentication screen when a session expires.

## 9. Application navigation and screens

Use four primary destinations:

1. Home
2. Travel
3. Tickets
4. Account

Use `NavigationSuiteScaffold` or the Navigation 3 equivalent so compact devices show a navigation bar and larger widths show a navigation rail.

### Home

Show:

- Next trip card.
- Active trip card when tracking is running.
- Buy ticket action.
- Search current trains action.
- Card QR shortcut.
- Service alerts returned by available APIs.
- Permission/setup banners only when actionable.

### Travel

Provide:

- Origin and destination station search.
- Swap stations action.
- Departure/arrival selection.
- Date and time pickers.
- Passenger and discount selection.
- Direct-train and transfer filters when supported.
- Search results grouped by journey.
- Journey details using an adaptive list-detail layout on large screens.
- Fare selection.
- Purchase flow.

### Tickets

Provide:

- Upcoming tickets.
- Active ticket.
- Past tickets.
- Ticket detail.
- Ticket QR or barcode when supplied.
- Per-leg train status.
- Manual “Track this trip” action.
- Tracking preference and notification status.

### Current trains

Support the endpoint capabilities found in the captures, for example:

- Search by train number.
- Search by station.
- Departures and arrivals.
- Scheduled and expected time.
- Platform/line.
- Delay.
- Cancellation.
- Calling points.

### Card

Show:

- Card name/type.
- Card identifier in masked form.
- Validity information.
- Associated passenger.
- Full-screen QR code.
- QR expiration or refresh status if the QR is dynamic.

For QR display:

- Prefer the exact image or payload supplied by the API.
- Do not transform signed QR contents.
- Increase screen brightness while the full-screen QR is visible and restore it afterward.
- Do not persist short-lived QR payloads unless required for offline use.
- Hide sensitive QR contents from logs and accessibility debug output.

### Account

Show:

- Profile information.
- Passenger profiles.
- Discounts or entitlement information available from the API.
- Saved preferences.
- Notification and live-tracking settings.
- Session/logout controls.
- Application attribution and non-affiliation notice when applicable.

## 10. Material 3 Expressive design requirements

Create a dedicated design system rather than styling screens independently.

Requirements:

- Edge-to-edge rendering.
- Dynamic color when enabled by the user, with a CP-inspired static fallback palette that does not copy protected assets.
- Expressive typography hierarchy.
- Consistent shape scale.
- Motion and container transformations that respect reduced-motion settings.
- Large or medium top app bars where appropriate.
- Search bars for stations and train numbers.
- Filter chips and segmented buttons for journey filters.
- Cards with clear hierarchy for trips and tickets.
- Modal bottom sheets for passenger, fare, and seat options.
- Current Material loading indicators.
- Pull-to-refresh where a manual refresh is useful.
- Predictive-back animations.
- Adaptive list-detail or supporting-pane layouts for journey results, ticket details, and train calling points.
- Correct window inset handling on phones, tablets, foldables, and desktop windowing.
- Minimum 48 dp touch targets.
- TalkBack descriptions for icons, train status, delay, QR actions, and progress.
- Do not rely on color alone to communicate delay, cancellation, or selection.

## 11. Ticket purchase flow

Implement purchase in discrete, resumable steps:

1. Select origin and destination.
2. Select date/time and search mode.
3. Fetch journeys.
4. Select a journey.
5. Fetch fares.
6. Select passenger profiles and discounts.
7. Select class, seat preferences, and options supported by the API.
8. Review price and conditions.
9. Create the purchase/booking session.
10. Complete payment using the captured flow.
11. Handle redirect, challenge, cancellation, timeout, and duplicate-submit cases.
12. Confirm booking server-side.
13. Fetch the issued ticket rather than assuming payment success means ticket issuance.
14. Persist the ticket transactionally.
15. Schedule trip notifications and tracking.

Use idempotency or the provider's booking/session identifier when available. Disable repeated payment submission while a request is pending. Never store payment card data.

## 12. Trip tracking lifecycle

Implement a persisted state machine:

```text
INACTIVE
SCHEDULED
STARTING
WAITING_FOR_TRAIN_DATA
PRE_BOARDING
ARRIVING_AT_BOARDING_STATION
BOARDING
ON_BOARD
APPROACHING_DESTINATION
COMPLETED
CANCELLED
FAILED
```

Persist the state, ticket ID, leg ID, last successful snapshot, last request time, retry state, and notification ID.

### State transitions

- `INACTIVE -> SCHEDULED`: ticket is purchased/imported and tracking is enabled.
- `SCHEDULED -> STARTING`: pre-trip trigger fires, normally 60 minutes before scheduled departure.
- `STARTING -> WAITING_FOR_TRAIN_DATA`: foreground service starts and initial request is pending.
- `WAITING_FOR_TRAIN_DATA -> PRE_BOARDING`: a valid live snapshot is obtained.
- `PRE_BOARDING -> ARRIVING_AT_BOARDING_STATION`: expected train arrival is within the configured threshold.
- `ARRIVING_AT_BOARDING_STATION -> BOARDING`: expected arrival is within approximately five minutes.
- `BOARDING -> ON_BOARD`: the train is reported as departed from the ticket's boarding station, or the user taps “I am on board.”
- `ON_BOARD -> APPROACHING_DESTINATION`: expected destination arrival is within the configured threshold, for example 15 minutes.
- `APPROACHING_DESTINATION -> COMPLETED`: destination call is completed or the expected arrival plus a grace period has passed with terminal data.
- Any active state -> `CANCELLED`: provider reports cancellation.
- Any active state -> `FAILED`: unrecoverable ticket or train resolution error.

Do not require device location merely to infer that the passenger is on the train. Prefer provider station-call data and an explicit user action.

For tickets with transfers, track one leg at a time. Promote the current leg. After completion, schedule or activate the next leg. If two trips overlap, promote only the most imminent active trip and keep the other as a standard notification.

## 13. Scheduling the tracking start

The target start is 60 minutes before the scheduled departure of the ticket's boarding station.

### Preferred path

1. After ticket issuance or sync, calculate `scheduledDeparture - 60 minutes`.
2. Request notification permission in a contextual flow.
3. Explain and request “Alarms & reminders” special access when the user enables automatic precise tracking.
4. If exact alarms are available, schedule an exact alarm for the start time.
5. The alarm receiver starts `TripTrackingService` and immediately posts its foreground notification.
6. Also enqueue unique recovery work so tracking can be reconstructed if local scheduling state is lost.

### Fallback path

If exact alarm access is unavailable:

- Enqueue unique one-time WorkManager work near the target time.
- Clearly indicate in settings that automatic activation can be delayed by system battery scheduling.
- Allow the user to start tracking manually from the ticket screen.
- If the application is open within the tracking window, start tracking directly.

On reboot or application update:

- Read future tracked trips from Room.
- Reschedule their alarms and recovery work.
- Do not start the data-sync foreground service directly from `BOOT_COMPLETED`.

## 14. Foreground service and polling

Create `TripTrackingService` as a foreground service with the `dataSync` type because it continuously fetches live train data.

Manifest requirements include:

- `INTERNET`
- `POST_NOTIFICATIONS`
- `POST_PROMOTED_NOTIFICATIONS`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_DATA_SYNC`
- `SCHEDULE_EXACT_ALARM` when precise automatic activation is enabled

The service must:

1. Call `startForeground()` immediately.
2. Restore the tracking session from Room.
3. Resolve the `TrainServiceKey`.
4. Poll the live endpoint approximately every 10 seconds.
5. Use a monotonic clock to prevent accumulated drift.
6. Write normalized snapshots to Room.
7. Update UI flows.
8. Update the ongoing notification only when visible information changes or a displayed minute changes.
9. Stop after completion, cancellation, explicit user stop, or an unrecoverable error.
10. Implement foreground-service timeout handling and stop itself safely.

### Polling policy

Normal active cadence:

```text
10 seconds
```

Rules:

- Never run overlapping requests. If a request takes longer than 10 seconds, start the next interval after the current request completes.
- Apply a short bounded retry for transient connection failures.
- Honor `Retry-After` and server rate limits.
- Use exponential backoff after repeated failures, capped at a reasonable interval such as 60 seconds.
- Return to 10 seconds after the next successful response.
- Use ETag or conditional requests if supported.
- Do not issue a notification update every 10 seconds if the displayed values are unchanged.
- Mark data as stale when no successful response has been received for a configured period.
- Keep the last known values visible with “Last updated …” rather than clearing the notification.
- End tracking after destination completion plus a short grace period.

Android limits `dataSync` foreground-service execution time. Implement `Service.onTimeout()` where available, persist the state, stop cleanly, and provide a user-visible recovery path. Do not attempt to bypass platform limits by misdeclaring the foreground-service type.

## 15. Live Update notification design

Create two channels:

### `trip_tracking`

- Ongoing status.
- Low or default interruption.
- Used by the foreground service.
- Updated silently.

### `trip_alerts`

- Delay begins.
- Delay materially increases.
- Platform/line changes.
- Cancellation.
- Severe service disruption.
- Connection-risk warning when derivable.

Do not alert repeatedly for the same unchanged delay.

### Supported-device behavior

On devices supporting Live Updates:

- Use an ongoing notification.
- Request promoted ongoing behavior.
- Use `Notification.ProgressStyle`.
- Set a content title.
- Do not use custom `RemoteViews`.
- Use a train tracker icon.
- Use progress segments and points for boarding station, intermediate journey progress, and destination.
- Check whether promoted notifications are permitted.
- If the system or OEM does not promote it, continue as a normal foreground notification.

### Older-device fallback

Use an ongoing `BigTextStyle` notification containing the same critical text and actions. The application's core tracking behavior must not depend on Live Update availability.

### Standard notification at one hour

At approximately T-60:

- Post or update the trip notification.
- Start polling when the service can be started.
- Request Live Update promotion when the journey is sufficiently imminent.
- Keep the threshold configurable, defaulting to 60 minutes.

If promotion is judged inappropriate or refused by the system, retain the standard ongoing notification.

## 16. Notification content and status chip rules

### Before the train arrives

Notification title example:

```text
IC 721 · Lisboa-Oriente → Porto-Campanhã
```

Body:

```text
Scheduled 14:09 · Expected 14:21
12 min late · Platform 5
```

On time:

```text
Scheduled 14:09 · On time
Platform 5
```

Status chip:

- Always use a monochrome train small icon.
- Use a compact value such as `L5·12m`, meaning platform/line 5 and 12 minutes remaining.
- If the platform is unavailable, show the countdown only, such as `12m`.
- If no expected time is available, show a short indeterminate value and explain the state in the notification body.

### Approximately five minutes before expected arrival

Replace the chip text with carriage and seat when available:

```text
C3·18A
```

Fallbacks:

- Carriage only: `C3`
- Seat only: `18A`
- Neither available: retain arrival countdown

The expanded notification must still show expected arrival, delay, and platform.

### While on board

Status chip:

- Show time until expected arrival at the ticket's destination.
- Prefer the system countdown based on the expected destination time when only ETA is needed.

Notification body example:

```text
Destination Porto-Campanhã
Expected 17:02 · 8 min late
Next: Vila Nova de Gaia-Devesas
```

The progress tracker should move according to station calls or elapsed journey progress. Never claim a physical train location unless the provider data supports it.

### Delay alerts

Post an alert when:

- Delay changes from 0 to a positive value.
- Delay increases by a meaningful threshold, for example five minutes.
- Delay decreases materially if this changes user action.
- The platform changes.
- The train is cancelled.

Example:

```text
IC 721 is now expected 12 minutes late. Expected at platform 5 at 14:21.
```

### Notification actions

Provide:

- Open trip.
- Stop tracking.
- “I am on board” while appropriate.
- Refresh now when the service is in an error state.

Use immutable `PendingIntent`s unless mutability is explicitly required.

## 17. Status chip length constraint

Keep chip text extremely short. Define and unit-test a formatter:

```kotlin
interface StatusChipFormatter {
    fun preBoarding(platform: String?, minutes: Int?): String?
    fun boarding(carriage: String?, seat: String?, minutes: Int?): String?
    fun onBoard(minutesToDestination: Int?): String?
}
```

Preferred outputs should remain under seven characters whenever possible:

```text
L5·12m
C3·18A
42m
+12m
```

The complete meaning must always remain available in the expanded notification and in TalkBack content.

## 18. Delay and ETA calculation

Prefer explicit provider fields. Only derive delay when necessary.

Rules:

```text
boardingDelay = expectedBoarding - scheduledBoarding
destinationDelay = expectedDestination - scheduledDestination
```

- Clamp small negative values to zero unless the provider explicitly reports early running.
- Keep boarding delay and destination delay separate.
- Do not assume the same delay applies to every station.
- Use the ticket boarding station for pre-boarding information.
- Use the ticket destination station for onboard ETA.
- If a train status response returns multiple matching station calls, match by stable station ID and service date.
- If the provider changes the expected arrival, update the countdown and notification text.

## 19. Local persistence

Persist:

- Station catalogue and aliases.
- Account summary.
- Card metadata.
- Tickets and legs.
- Booking references.
- Train lookup keys.
- Tracking preferences.
- Scheduled tracking triggers.
- Last successful live snapshot.
- Notification IDs.
- Tracking state.

Do not persist:

- Passwords.
- Payment card data.
- Unnecessary raw authentication responses.
- Complete sensitive QR payload history.
- Unredacted network captures.

Use Room migrations from the first release. Do not use destructive migration in production.

## 20. Offline and error behavior

The application must remain useful with intermittent connectivity.

- Display cached upcoming tickets.
- Display the last successful train snapshot and its age.
- Display card metadata offline if permitted.
- Display QR offline only if the payload is valid offline and its lifetime is understood.
- Distinguish authentication failure, no network, endpoint failure, train not found, ambiguous train, and malformed response.
- Preserve purchase state safely across process death.
- Never retry a payment blindly after an unknown result; query the booking state first.
- Show a clear recovery action.

## 21. Mock and development mode

Add a `mock` build type or product flavor.

The mock implementation must support scenarios:

- On-time train.
- Train gradually delayed.
- Delay reduced.
- Platform change.
- Train cancelled.
- Missing platform.
- Missing carriage/seat.
- Train not yet published by live API.
- Network timeout.
- HTTP 401 and successful refresh.
- HTTP 429 with `Retry-After`.
- Malformed provider response.
- Journey with transfer.
- Destination reached.
- Long trip approaching foreground-service limits.

Provide a developer screen to choose a scenario and accelerate time.

## 22. Testing strategy

### Unit tests

Test:

- Train endpoint resolution.
- Station matching.
- Service-date handling.
- Delay calculations.
- ETA calculations.
- Tracking state transitions.
- Status chip formatting.
- Notification text formatting.
- Multi-leg selection.
- Retry and backoff.
- Session refresh serialization.
- Time-zone and daylight-saving transitions.

### Contract tests

For every captured endpoint:

- Decode successful fixtures.
- Decode error fixtures.
- Verify required headers and parameters.
- Verify redaction.
- Verify adapter-to-domain mapping.

Use MockWebServer or an equivalent local test server. Production tests must not depend on live CP endpoints.

### UI tests

Cover:

- Login and session-expired flows.
- Journey search.
- Fare selection.
- Purchase review.
- Upcoming ticket.
- Ticket detail.
- Card and QR display.
- Current train search.
- Tracking permission setup.
- Active-trip screen.
- Accessibility semantics.
- Compact and expanded window sizes.

### Notification/service tests

Cover:

- Notification permission denied.
- Exact alarm access denied.
- Promoted notification unavailable.
- API 36+ Live Update.
- Older Android fallback.
- Service restart from persisted state.
- User dismisses/demotes the Live Update.
- User stops tracking.
- Delay begins and changes.
- Platform changes.
- Boarding threshold.
- Onboard transition.
- Destination completion.
- Foreground-service timeout.

## 23. Security checklist

- Enforce HTTPS.
- Do not disable certificate validation.
- Do not add permissive trust managers.
- Do not use a production certificate pin unless certificate rotation is understood and maintainable.
- Redact `Authorization`, `Cookie`, `Set-Cookie`, CSRF values, booking references, QR payloads, and personal data.
- Disable verbose HTTP body logging in release builds.
- Obfuscate release builds where compatible.
- Use exported components only when required.
- Validate all deep-link input.
- Use explicit intents for internal receivers/services.
- Mark services and internal receivers `exported=false` unless a system broadcast requires otherwise.
- Use `FLAG_SECURE` for QR or ticket screens only if the desired UX accepts blocked screenshots.
- Include a privacy page explaining stored data and polling behavior.
- Include account deletion or data-clearing behavior if supported by the provider.

## 24. Performance and battery requirements

- Poll only while a user-relevant trip is active.
- Stop immediately when tracking is no longer needed.
- Do not hold a manual wake lock unless profiling proves it is required and the use is justified.
- Avoid writing an unchanged snapshot to Room every 10 seconds.
- Avoid rebuilding the notification when its visible content is unchanged.
- Cache the station catalogue.
- Use conditional HTTP requests when supported.
- Measure service CPU, network usage, wakeups, and battery impact.
- Run Android background-task and battery quality checks before release.

## 25. Implementation milestones

### Milestone 0 — Repository and API evidence

- [ ] Create the project.
- [ ] Add Gradle convention plugins and version catalog.
- [ ] Add the module structure.
- [ ] Add CI commands for build, lint, unit tests, and instrumentation tests.
- [ ] Store the supplied file as `docs/api/cp-observed.openapi.yaml`.
- [ ] Correct invalid generic path-parameter references in a separate normalized contract.
- [ ] Import only redacted request and response samples.
- [ ] Complete `docs/api/endpoint-matrix.md`.
- [ ] Add `OBSERVED`, `INFERRED`, or `UNKNOWN` confidence to each operation and field.
- [ ] Confirm the exact OIDC client, redirect, scopes, PKCE, refresh, and logout behavior.
- [ ] Capture schemas for journeys, train timetable, station timetable, sale, trips, final sale, and e-ticket.
- [ ] Capture the missing CP-card and card-QR operations.
- [ ] Identify unsupported or uncertain endpoint flows.

Deliverable: compiling empty application, the preserved observed contract, a corrected normalized contract for evidenced schemas, and a complete endpoint/gap inventory.

### Milestone 1 — Design system and navigation

- [ ] Implement Material 3 Expressive theme.
- [ ] Implement dynamic/static color behavior.
- [ ] Implement edge-to-edge.
- [ ] Implement stable Navigation 3.
- [ ] Implement adaptive primary navigation.
- [ ] Add placeholder Home, Travel, Tickets, and Account screens.
- [ ] Add predictive back.

Deliverable: navigable adaptive shell on phone and tablet.

### Milestone 2 — Network foundation and authentication

- [ ] Implement HTTP client and safe logging.
- [ ] Implement authentication DTOs and adapter.
- [ ] Implement session persistence and refresh.
- [ ] Implement login/logout/reauthentication.
- [ ] Add contract tests.

Deliverable: user can sign in and refresh account data.

### Milestone 3 — Account, card, and QR

- [ ] Implement account repository and screen.
- [ ] Implement card repository and card screen.
- [ ] Implement QR retrieval/rendering.
- [ ] Implement secure caching rules.
- [ ] Add offline and expiration behavior.

Deliverable: account and card information, including QR, are usable.

### Milestone 4 — Stations, current trains, and journey search

- [ ] Implement station catalogue and search.
- [ ] Implement current train lookup.
- [ ] Implement journey search.
- [ ] Implement adaptive result/detail UI.
- [ ] Implement provider mapping tests.

Deliverable: user can inspect trains and search journeys.

### Milestone 5 — Purchase

- [ ] Implement fare lookup.
- [ ] Implement passengers/discounts.
- [ ] Implement booking session.
- [ ] Implement hosted payment/challenge flow if required.
- [ ] Implement booking confirmation.
- [ ] Persist issued tickets.
- [ ] Protect against duplicate submission.

Deliverable: end-to-end ticket purchase in a test or safe environment.

### Milestone 6 — Tickets and trip details

- [ ] Sync tickets from account.
- [ ] Persist tickets and legs.
- [ ] Implement upcoming/history lists.
- [ ] Implement ticket detail and QR/barcode.
- [ ] Implement train lookup key resolution.

Deliverable: purchased and account tickets are visible and correctly normalized.

### Milestone 7 — Tracking engine

- [ ] Implement tracking state machine.
- [ ] Implement `TrainEndpointResolver`.
- [ ] Implement 10-second polling service.
- [ ] Implement retries, stale data, and cancellation.
- [ ] Implement exact-alarm scheduling and fallback work.
- [ ] Implement reboot/update rescheduling.
- [ ] Add mock scenarios and fake clock.

Deliverable: active trip tracking works without notifications first.

### Milestone 8 — Notifications and Live Updates

- [ ] Create notification channels.
- [ ] Implement standard ongoing fallback.
- [ ] Implement promoted Live Update request.
- [ ] Implement `ProgressStyle` tracker.
- [ ] Implement status chip formatter.
- [ ] Implement delay/platform/cancellation alerts.
- [ ] Implement boarding and onboard transitions.
- [ ] Implement notification actions.
- [ ] Test dismissal and promotion-disabled behavior.

Deliverable: the complete trip experience appears in notification shade, lock screen, and status chip where supported.

### Milestone 9 — Hardening

- [ ] Accessibility audit.
- [ ] Adaptive-layout audit.
- [ ] Process-death testing.
- [ ] Network-failure testing.
- [ ] Battery and background execution testing.
- [ ] Security review.
- [ ] Database migration test.
- [ ] Release logging/redaction review.
- [ ] Privacy and attribution screens.

Deliverable: release candidate.

## 26. Acceptance criteria

### Observed OpenAPI acceptance

- The original observed contract is preserved unchanged for provenance.
- The normalized contract contains no nonexistent path parameters.
- Every production DTO has at least one redacted successful response fixture.
- Every mutation has a redacted request fixture.
- Card and card-QR features remain disabled until their operations are observed.
- Live tracking uses the train timetable endpoint and matches calls by stable station ID.


The project is complete only when all of the following are true:

1. The application is fully native Kotlin and Compose.
2. The main UI uses Material 3 Expressive and adaptive Android components.
3. A user can sign in and view account information.
4. A user can view card details and the card QR code.
5. A user can search journeys and current trains.
6. A user can complete the supported ticket purchase flow.
7. Purchased tickets appear in the application.
8. Every trip leg contains a reliable train lookup key.
9. The application schedules tracking approximately one hour before departure.
10. Active tracking fetches live train information approximately every 10 seconds.
11. The application displays scheduled and expected boarding times.
12. The application displays delay and the number of delayed minutes.
13. The application displays platform/line when available.
14. The status chip shows a train icon and compact platform/countdown information before arrival.
15. Around five minutes before expected arrival, the chip changes to carriage/seat information when available.
16. After boarding, the chip shows time until expected arrival at the ticket destination.
17. The expanded notification always contains the complete, non-abbreviated status.
18. Material delay, platform, and cancellation changes produce controlled alerts without notification spam.
19. Live Updates work on supported Android versions.
20. Unsupported devices retain a functionally equivalent ongoing notification.
21. Tracking survives process recreation using persisted state.
22. Tracking stops after the destination or cancellation.
23. No secrets or personal data appear in logs or committed fixtures.
24. Unit, contract, UI, and service tests pass.

## 27. First tasks for local Codex

Execute these tasks first, in order:

1. Inspect the repository and locate all reverse-engineering notes and redacted captures.
2. Create `docs/api/endpoint-matrix.md`; do not write production API code before this matrix exists.
3. Scaffold the Kotlin/Compose multi-module project with `compileSdk` and `targetSdk` 37, `minSdk` 26, Material 3, Material 3 Adaptive, and stable Navigation 3.
4. Create the domain models and repository interfaces.
5. Add a `mock` flavor with fake repositories and a fake clock.
6. Implement the adaptive application shell and design system.
7. Implement authentication only after its endpoint contract tests pass.
8. Continue milestone by milestone; run build, lint, and tests after each milestone.

When an endpoint field or flow is uncertain, record it in `docs/api/unresolved.md` with the capture evidence needed to resolve it. Do not guess and do not silently omit the feature.

# HAR-verified addendum

This section supersedes any earlier assumptions where the 10 July 2026 web HAR provides stronger evidence.

## Authentication blocker

The observed OIDC client is the website client (`websitecp`) and its redirect URIs are owned by `www.cp.pt`. This is not a valid native Android login configuration. Keep authenticated features behind a feature flag until the Android client's OIDC metadata and redirect URI are captured or CP authorizes a native client.

Do not submit credentials directly to Keycloak's HTML form action. Use Authorization Code with PKCE in a system browser once a valid native redirect is available.

## Live tracking contract

Use:

```text
GET /cp/services/travel-api/trains/{trainNumber}/timetable/{serviceDate}
```

Map the ticket leg to `trainStops` using exact station codes. Use:

- Boarding countdown: `ETD`, then scheduled departure plus stop delay.
- Destination countdown: `ETA`, then scheduled arrival plus stop delay.
- Platform/line: the matched stop's `platform`.
- Delay text: the matched stop's delay, with train-level delay only as a fallback.
- Progress anchor: `lastStationCode`, treated as provider state rather than a guaranteed actual-arrival event.
- Operational state: `status`; observed values include `IN_TRANSIT` and `AT_STATION`.
- Disruption indicator: `hasDisruptions` and `messages`.
- Cancellation/suppression: provider field `supression`, whose concrete non-null schema remains uncaptured.

All API time strings must be resolved against the service date in `Europe/Lisbon`, including midnight rollover.

The station endpoint is:

```text
GET /cp/services/travel-api/stations/{stationId}/timetable/{serviceDate}
    ?view=ARRIVALS|DEPARTURES
    &start=HH:mm
```

Use it as a secondary station-board view, not as the primary per-ticket tracking source.

## Notification state machine

At `scheduledDeparture - 60 minutes`, start the foreground tracking service and Live Update.

### Approaching boarding station

Expanded notification:

```text
IC 519 · Coimbra-B → Aveiro
Scheduled departure 20:40
Expected departure 20:52 · 12 min late
Platform 1
```

Status chip:

```text
[train icon] P1 · 12m
```

The countdown is based on expected departure, not expected arrival, because this is the passenger's boarding stop.

### Five minutes before expected departure

Expanded notification keeps the live operational details and prominently adds:

```text
Carriage 3 · Seat 18
```

Status chip:

```text
[carriage icon] C3 · S18
```

If carriage or seat is unavailable, retain the train/platform/countdown chip.

### On board

Use the ticket destination stop:

```text
Destination Aveiro
Scheduled arrival 21:15
Expected arrival 21:27 · 12 min late
32 min remaining
```

Status chip:

```text
[train icon] 32m
```

The app cannot know with certainty that the passenger boarded from the captured APIs alone. Enter `ON_BOARD` when one of the following occurs:

1. The user taps `I am on board`.
2. The user previously enabled automatic transition and expected departure has passed.
3. A future evidenced endpoint provides boarding validation.

Do not claim actual passenger boarding based only on train movement.

## Purchase flow now evidenced

Implement the sale coordinator in this order:

```text
create sale
load rules, item choices, ID types, and fiscal countries
load pending sale
set passengers
set client
set fiscal details
set requested items
load seat maps
set seats
confirm sale
load final sale and available operations
```

Treat duplicate update/confirm calls in the HAR as UI behavior or retries, not required protocol steps.

The captured purchase used a pass with a zero balance. Do not mark paid ticket purchase complete. Add a `PAYMENT_FLOW_NOT_CAPTURED` feature gate and retain mock payment until a paid purchase capture establishes payment initiation, redirect/deep-link return, 3-D Secure, failure, cancellation, and reconciliation.

## Ticket QR now evidenced

A confirmed sale contains structured `qrcode`, `barcode`, `ticketID`, `ticketURL`, carriage, and seat data. The e-ticket endpoint returns a PDF. Implement the ticket QR screen from the structured ticket value, with the PDF as a separate action.

This does not solve the CP customer-card QR feature. No customer-card endpoint was observed.

## Required Codex inputs

Use:

```text
docs/HAR_ANALYSIS.md
docs/api/cp-har-normalized.openapi.yaml
fixtures/*.json
```

Never use the raw HAR as a runtime or test fixture.
