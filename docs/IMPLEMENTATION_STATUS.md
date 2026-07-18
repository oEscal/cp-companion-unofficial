# Implementation status — 0.7.0

## Completed application scope

| Area | Result |
|---|---|
| Station and train search | Searchable station/train catalogues, favorites, train service date selection, and direct trip opening. |
| Station boards | Arrival/departure selection, arbitrary service date, searchable trips, refresh, midnight date resolution, and finished/upcoming/cancelled visual states. |
| Trip details | Calling points, passenger segment selection, delay/expected-time state, current/passed/skipped/origin/destination chips, and long-route progress data. |
| Ticket import | Manual form, shared/pasted CP text, opt-in SMS inbox scan, and optional notification-listener import. |
| Automatic tracking | Validated future tickets default to T-60 activation with exact/inexact AlarmManager and WorkManager recovery. |
| Delayed journeys | Scheduled arrival alone does not complete automation; recent journeys remain eligible for live verification. |
| Concurrent tickets | Per-ticket sessions and notifications can run concurrently; duplicate launch paths for one ticket are rejected. |
| Live notification | Android 16+ progress/Live Update request with standard ongoing-notification fallback and promotion retry. |
| Persistence | Per-ticket encrypted Room rows, DataStore settings, Android Keystore-backed recovery state, atomic catalogue files, and corruption recovery. |
| Networking | Runtime CP configuration discovery, HTTPS/host/path validation, request coalescing, token-bucket pacing, bounded caches, global 429 cooldown, and stale-data fallback. |
| Navigation/UI | Navigation Compose, predictive back, edge-to-edge layout, Material 3 Expressive theme, dynamic color, and System/Light/Dark modes. |
| Localization/accessibility | English and Portuguese resources and semantic labels for modified interactive controls. |
| Privacy | Local SMS/notification parsing, backup exclusion, cleartext denial, bounded external input, and no logging of passenger/ticket/runtime-header data. |

## Preserved reliability behavior

- Boot, application-update, exact-alarm permission, clock, and time-zone reconciliation.
- Notification-permission/channel blocked states and recovery scheduling.
- Stale ETA/ETD retention, last-seen delay continuity, and endpoint-aware failure classification.
- Service heartbeat and process-local duplicate-launch protection.
- Stable per-ticket notification IDs and terminal action cleanup.
- Bounded SMS import fingerprints, catalogue caches, request caches, and tracking checkpoints.

## Out of current verified scope

Authenticated CP account, payment, purchase, card, and QR operations are not implemented because the supplied material does not establish a supported authorization and data contract. See `docs/api/unresolved.md` and `TODO.md`.
