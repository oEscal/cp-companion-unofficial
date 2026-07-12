# Implementation status — 0.6.0

## Completed behavior

| Area | Result |
|---|---|
| Future automation | Validated future tickets default to automatic tracking and schedule T-60 activation. |
| Historical tickets | Automation controls are absent after departure and historical records are normalized to disabled/completed state. |
| Terminal sessions | Arrival/cancellation cancels all activation work, clears metadata, disables automation, and cannot be restarted by reconciliation. |
| Disable/delete | An active service stops immediately when the ticket is disabled or deleted. |
| Notification actions | Terminal notifications have no active-session Stop action; detached actions cannot leave a service alive. |
| SMS concurrency | Inbox scans are serialized and cursor/settings changes are atomically merged. |
| Midnight boards | Service dates are derived per board entry rather than copied blindly from the request date. |
| Ticket persistence | Room stores one encrypted row per ticket and migrates the previous encrypted JSON format. |
| Preferences | DataStore stores settings, theme, favorites, and import fingerprints. |
| Write ordering | Dedicated ordered IO lanes prevent stale persistence operations from overtaking newer state. |
| Navigation | Navigation Compose and saved state restore top-level and detail routes after recreation. |
| UI structure | Feature screens are separated into Home, Search, Station, Trip, Tickets, and Settings files. |
| Localization | Core UI, accessibility labels, notification channels, and journey notifications have English and Portuguese resources. |
| Testing | JVM, Room, navigation-state, and Compose automation-control coverage is included. |

## Existing reliability behavior retained

- Exact/inexact AlarmManager T-60 activation with WorkManager recovery.
- Boot, application-update, permission, clock, and time-zone reconciliation.
- Explicit notification/exact-alarm blocked states.
- Atomic one-session ownership with visible overlap queuing.
- CP request single-flight, token-bucket budget, `Retry-After`, jitter, and global cooldown.
- Lifecycle-aware screen polling and shared foreground-session trip data.
- Delay-derived expected times, endpoint-aware suppression, and stale-data retention.
- Bounded caches, pre-indexed/debounced search, and station IANA time zones.
- System, Light, and Dark themes.

## Remaining release validation

The source includes instrumentation coverage, but exact alarms, Doze, OEM background limits, notification-channel revocation, reboot, and foreground-service behavior still require execution on representative Android devices. Release signing also requires the project-specific keystore variables described in the README.
