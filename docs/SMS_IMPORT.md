# CP SMS ticket import

## Supported message structures

The parser accepts Portuguese CP ticket messages containing:

- one-way journeys, including messages that omit the literal `Ida` label;
- `Ida`, `Volta`, and `Regresso` journeys;
- ISO dates and Portuguese `dd/MM/yyyy` or `dd-MM-yyyy` dates;
- `HH:mm` and `HHhmm` times;
- a `Bilhete`, `Ticket`, `Reserva`, or reference value when present;
- optional abbreviated or full carriage and seat labels independently for each leg;
- common arrow, dash, whitespace, line-break, and multipart-SMS variations;
- both historic and future service dates.

Each journey leg is stored as a separate ticket. Deduplication uses the ticket reference, direction, service date, and train number.

The project fixtures use synthetic ticket references. User-provided ticket references are not included in the source tree.

## Import paths

### Share or paste

Available in every build and requires no SMS permission. The text is parsed locally.

### Notification access

The user can explicitly enable Android notification access. The listener:

- rejects notifications explicitly categorized as non-message content;
- accepts recognized CP sender titles such as `CP`, `CP-INFO`, and `CP Comboios`;
- accepts only text that passes the CP ticket parser;
- stores data locally and does not upload notification content.

Android, the messaging application, or lock-screen privacy settings may redact the message body. Share or Paste remains the reliable fallback.

### Inbox scan

The app declares `READ_SMS` and asks for it only after the user chooses inbox import. The first scan inspects up to 2,000 recent messages; later scans stop as soon as they reach the SMS that most recently produced an imported ticket. It retains only messages with a recognized CP sender or strong CP-ticket content markers before parsing locally. Android or an installer may restrict this permission; Share, Paste, and notification access remain available when it is denied.

## Station resolution

SMS messages contain station names rather than stable station codes. Imported tickets retain those names immediately. When the CP station catalogue is available, the app normalizes accents, punctuation, and whitespace and resolves a unique code for each endpoint. Tracking is blocked until both station codes are known.

## Date behavior

Import does not discard old tickets. The SMS-provided departure and arrival times are resolved in `Europe/Lisbon`; an arrival earlier than its departure is treated as occurring on the next day. A one-hour reminder is scheduled from the parsed departure time, then enriched from the CP trip endpoint when station codes and CP live data are available.
