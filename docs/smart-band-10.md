# Smart Band 10 live tracking

This document describes the experimental Xiaomi Smart Band 10 integration. It is
disabled by default and is deliberately limited to one active CP Companion
journey. The Android application remains the source of all CP data and train
state; the watch only validates, stores, renders, and locally counts down from
the latest snapshot.

## Existing tracking architecture

The relevant existing flow is:

1. `TrainTrackingService` owns the existing polling loop, error handling,
   recovery, and session lifecycle.
2. `TrainRepository` obtains `TrainTrip` data from CP's public services.
3. `TripStateResolver.resolve()` combines a saved `Ticket` and a `TrainTrip`
   into a structured `TrackingSnapshot`. It determines passenger phase,
   relevant event times, delay, platform, next stop, and the event time zone.
4. `AppStores.saveTracking()` retains snapshots per ticket and exposes the
   primary active session through `activeTicketId` and `tracking` flows. The
   oldest active session is the existing primary session used by single-session
   UI surfaces.
5. `TrackingNotificationFactory` consumes the same snapshot to build the
   existing Android Live Update or fallback ongoing notification.

The watch adapter observes `AppStores` after the resolver has produced a
snapshot. It does not call CP services, alter polling intervals, or duplicate
notification calculations. When the service removes a tracking session, the
adapter emits a `journey_cleared` message if a snapshot had previously been
sent.

## Responsibilities

| Component | Responsibility |
| --- | --- |
| `TripStateResolver` and `TrainTrackingService` | Existing CP requests, state calculation, polling, recovery, and lifecycle |
| `WatchSnapshotAdapter` | Convert one existing `TrackingSnapshot` into the small watch contract |
| `SmartBandSyncController` | Observe the primary active snapshot, deduplicate displayed values, sequence messages, and isolate failures |
| `GadgetbridgeTransport` | Send an explicit package-targeted Android broadcast to Gadgetbridge |
| Vela parser/state modules | Validate messages, reject stale/out-of-order data, and maintain display state |
| Vela page | Render the latest validated data and update the visible countdown while open |

The watch application identifier is `pt.cpcompanion.smartband10`. It is the
identifier passed to Gadgetbridge's `appId` extra and is not the Android
application ID.

## Message contract

Messages are UTF-8 JSON strings. Timestamps are epoch milliseconds and are
rendered in the supplied journey time zone (`Europe/Lisbon` for CP journeys).

### `journey_snapshot`

```json
{
  "version": 1,
  "type": "journey_snapshot",
  "sequence": 1793995200000,
  "sentAtEpochMs": 1793995200000,
  "journey": {
    "id": "session-8c9b4c4e2b1a",
    "train": "IC 000",
    "origin": "Coimbra-B",
    "destination": "Lisboa Oriente",
    "status": "in_progress",
    "nextStop": "Pombal",
    "countdownTarget": "destination",
    "countdownEpochMs": 1793995800000,
    "scheduledDepartureEpochMs": 1793994000000,
    "estimatedDepartureEpochMs": 1793994060000,
    "scheduledArrivalEpochMs": 1793997600000,
    "estimatedArrivalEpochMs": 1793998200000,
    "delayMinutes": 10,
    "platform": null,
    "timeZoneId": "Europe/Lisbon",
    "timeSource": "estimated",
    "lastSyncEpochMs": 1793995190000,
    "dataStale": false
  }
}
```

The journey identifier is a truncated SHA-256-derived value of the local
ticket/session ID. Ticket references, passenger details, carriage/seat data,
SMS text, QR codes, credentials, and complete persistence/network objects are
not transmitted.

The adapter maps existing passenger phases as follows:

| Existing phase | Watch status |
| --- | --- |
| `PRE_TRIP`, `APPROACHING_ORIGIN`, `BOARDING_SOON` | `upcoming_departure` |
| `ON_BOARD` | `in_progress` |
| `APPROACHING_DESTINATION` | `approaching_destination` |
| `ARRIVED` | `completed` |
| `CANCELLED` | `cancelled` |
| `DATA_UNAVAILABLE` | `waiting` or `outdated` as indicated by the snapshot |
| `STOPPED` | `stopped` |

The countdown uses the same relevant event timestamp already selected by the
resolver: the passenger origin event before boarding and the passenger
destination event during the journey. The contract includes scheduled and
estimated departure/arrival values separately so the renderer can label their
source. The watch never infers an operational delay or train position from
elapsed time alone.

### `journey_cleared`

```json
{
  "version": 1,
  "type": "journey_cleared",
  "sequence": 1793995201000,
  "sentAtEpochMs": 1793995201000,
  "journeyId": "session-8c9b4c4e2b1a"
}
```

The watch accepts a snapshot only when its sequence is newer than the current
message for the same process/session. A newer sequence for a different journey
replaces the previous journey; a late message from the previous journey cannot
overwrite it. Duplicate and malformed messages are ignored safely.

## Gadgetbridge transport

The Android adapter uses the documented Watch App Messages API:

```text
Action: nodomain.freeyourgadget.gadgetbridge.command.SEND_WATCH_APP_MESSAGE
Package: nodomain.freeyourgadget.gadgetbridge
Extras:
  device: configured Bluetooth MAC address
  appId: pt.cpcompanion.smartband10
  payload: UTF-8 JSON message
```

The user must enable Gadgetbridge's Intent API globally and enable “Allow
messages to watch apps” for the band. A broadcast being issued is not an
acknowledgement that the band received or displayed it, so CP Companion does
not expose a delivery claim or persistent retry queue. Gadgetbridge missing,
disconnected, uninstalled watch app, invalid address, and other transport
failures are isolated from train tracking.

The setting is opt-in, has no Bluetooth scan or pairing behavior, and transmits
nothing while disabled or incomplete.

Xiaomi's direct `system.interconnect` documentation describes a separate
companion-app path with package-name and signing-certificate requirements. This
project does not change the Android application ID or reuse a production
signing key for that path. The Vela app registers the documented receive
callback for the Gadgetbridge-delivered payload, but physical compatibility
still requires device validation.

### Current Gadgetbridge message-session limitation

The current physical behavior has an important session boundary. Gadgetbridge
2026.11.0 queues a watch-app payload until the Smart Band 10 app reports its
launched session. When the app is already visible, sending another broadcast
can wake the watch without delivering the new payload to the page. Leaving the
app and opening it again can then deliver the queued payloads as a batch. The
watch parser and state store do not intentionally cache or defer these
messages; they can only process callbacks that Gadgetbridge actually delivers.

This is tracked upstream in [Gadgetbridge PR #7250](https://codeberg.org/Freeyourgadget/Gadgetbridge/pulls/7250),
which is the follow-up two-way watch-app messaging work. At the time of this
document's update (2026-11-06), it is not included in the 2026.11.0 baseline.
For current physical tests, leave the watch app to the watch face before
sending a payload, then reopen the app.

After a Gadgetbridge release contains that change, update this note and verify:

1. Two payloads with increasing `sequence` values update the open watch app
   immediately.
2. `REAL MESSAGES` and `RECENT EVENTS` change without closing the app.
3. The behavior is unchanged after a watch-app restart and a phone process
   restart.
4. Only then consider enabling the retained link-check implementation with
   `linkCheckEnabled: true` in `watch/smart-band-10/src/diagnostics/config.js`.
5. Record the tested Gadgetbridge version and remove the temporary workaround
   from this section if it is no longer needed.

## Vela application

The source is under `watch/smart-band-10/`. It uses a `designWidth` of `106`
for the Smart Band 10's 212 × 520 display. The parser, state store, countdown
formatter, and fixtures are independent of the interconnect module so they can
be tested with Node.js without a watch or Gadgetbridge installation.

The Vela project uses the official `@system.interconnect` receive callbacks
when running on a compatible device. If the emulator does not provide that
interface, the Node test fixtures exercise the same parser and state modules.

## Development and validation

From the repository root:

```bash
cd watch/smart-band-10
npm test
npm run validate
npm run build                 # requires the Vela AIoT IDE/aiot toolchain
```

The `Android and watch verification` GitHub Actions workflow runs the watch
tests, source validation, and RPK build on pushes, pull requests, manual runs,
and release verification. Successful ordinary runs expose the generated RPK as
the `smart-band-10-watch-build` artifact. Release runs download that verified
artifact and include the RPK in the GitHub Release assets.

With the installed AIoT toolchain, the build currently produces
`watch/smart-band-10/dist/pt.cpcompanion.smartband10.debug.0.1.0.rpk`.
The generated package contains the watch manifest, compiled page, and app
icon; the local source tests and manifest validation pass as well.

With the community emulator scripts available and an emulator running:

```bash
./scripts/emu-deploy.sh --project watch/smart-band-10
node scripts/emulator-grpc.js screenshot /tmp/smart-band-10.png
```

The route timeline, status chips, countdown, long station names, and vertical
scrolling are covered by fixture/state tests; the local 212 × 520 emulator
checks the packaged shell and diagnostics layout. The installed watch package
contains no synthetic on-watch test control. Physical Smart Band 10 message
delivery remains subject to the Gadgetbridge session behavior documented above.

## References

- [Vela Quick App documentation](https://iot.mi.com/vela/quickapp/en/)
- [Vela `system.interconnect`](https://iot.mi.com/vela/quickapp/en/features/network/interconnect.html)
- [Vela multi-screen guidance](https://iot.mi.com/vela/quickapp/en/tools/debug/multi-screens.html)
- [Gadgetbridge Intent APIs](https://gadgetbridge.org/internals/automations/intents/)
- [Xiaomi wearable development reference](https://github.com/oryonatan/xiaomi-band-development)
