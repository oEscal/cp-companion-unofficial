CP Companion privacy policy
Last updated: 7 September 2026

CP Companion is an unofficial Android journey companion. It has no developer-operated backend, advertising SDK, analytics SDK, or automatic crash-report upload in this version.

Information on your device

The app saves imported journey details: train and service date, origin and destination, departure and arrival times, ticket reference, carriage and seat when present, and import source identifiers. Ticket payloads and tracking recovery state are encrypted using a key held by Android Keystore. Preferences, import checkpoints, and public timetable/catalogue caches are also stored locally. Database row identifiers and update timestamps are not encrypted; device storage protection still applies. Android backup and device transfer are disabled for this app's data.

SMS inbox access is optional. After you grant READ_SMS, an inbox scan examines a bounded recent window (up to 2,000 messages per scan), including non-CP messages, to identify CP ticket formats locally. Automatic inbox scans are separately opt-in. Raw message bodies are processed in memory and are not retained as message archives or uploaded. Parsed ticket fields are saved. Paste/share import asks for confirmation before saving.

Notification-listener access is optional and is a broad Android permission. Android can deliver other apps' notifications to the listener; CP Companion rejects notifications outside the device's default SMS app before parsing candidate ticket text. It keeps deduplication fingerprints and parsed journey fields, not an archive of notifications. Notification and SMS senders are not cryptographic proof that a ticket is authentic.

Information sent over the network

The app downloads CP’s public service settings from www.cp.pt/fe-config.json. These settings provide the API addresses and request values needed to retrieve data. The app then connects over HTTPS to api-gateway.cp.pt for station catalogues, station boards, station information, and train timetables. Requests can include station codes, train numbers, service dates, and board time filters. CP and its infrastructure providers necessarily receive the connection IP address and normal request metadata. Their processing is governed by their own policies. The app does not send SMS bodies, ticket references, seats, passenger names, or notification contents to these services. Opening a project/support link contacts the selected website in your browser.

Notifications and controls

Journey notifications may show route, platform, carriage, and seat details. They use private lock-screen visibility, but your device's notification settings control what is displayed. Disabling visible notifications may prevent reliable tracking. Exact-alarm access improves scheduling; Android may delay fallback work.

Delete a ticket from Tickets to remove its local record and active tracking state. Already queued background work may briefly remain until reconciliation. Deleting a ticket does not delete the original SMS; an explicit rescan or a new matching notification may import it again. Turn off automatic inbox import in the Automation tab and revoke SMS/notification-listener access in Android Settings to stop these import paths. To erase all tickets, caches, preferences, and import history, use Android Settings > Apps > CP Companion > Storage > Clear storage, or uninstall the app. This also loses saved journeys because backups are disabled. Public caches expire or are replaced during normal use; tickets remain until deleted or all app data is cleared.
