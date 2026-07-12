# SMS ticket import

CP Companion is delivered as one all-feature application.

Ticket messages can be imported through:

- Explicit inbox checking after granting `READ_SMS`.
- Optional periodic incremental inbox checking after the user enables it.
- Pasting or sharing message text.
- Optional notification-listener detection.

All inbox scans share a process-wide coordinator, so a manual scan and a scheduled worker cannot overwrite each other's cursor or restore an older settings snapshot. Cursor fields are merged atomically into the latest DataStore settings.

Inbox processing uses a received-time plus message-ID checkpoint. Notification imports use a persistent package/key/content fingerprint and mark it processed only after a successful ticket parse. Reimporting unchanged ticket data does not reset validation, reschedule alarms, or rewrite ticket rows.

Future imported tickets default to automatic tracking. Historical or already-departed tickets are retained without an automation switch and cannot generate new CP polling schedules.

All parsing is local and raw SMS bodies are not logged.
