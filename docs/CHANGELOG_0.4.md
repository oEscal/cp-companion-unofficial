# CP Companion 0.4.0

## Ticket import

- Existing SMS inbox imports no longer filter by journey date. Historic and future tickets are both accepted.
- Expanded CP sender matching for `CP`, `CP-INFO`, `CP Comboios`, and equivalent normalized sender IDs.
- Increased inbox scan limit to 500 messages.
- Added adjacent multipart-message reconstruction for devices that expose split SMS parts.
- Expanded ticket parsing for:
  - ISO and Portuguese dates;
  - `HH:mm` and `HHhmm` times;
  - `Ida`, `Volta`, and `Regresso` legs;
  - one-way messages without an explicit `Ida` label;
  - arrow and dash variants;
  - abbreviated and full carriage/seat labels;
  - past service dates.
- Improved failure text so it explicitly states that old tickets are supported.

## Search and dates

- Replaced keyboard date entry with a Material 3 calendar dialog in train search and manual ticket creation.

## Refresh

- Added Material 3 pull-to-refresh to every primary and detail page.
- The current screen reloads its appropriate data rather than always refreshing unrelated content.
- Existing station-board periodic refresh remains active while the station page is visible.

## Navigation

- Added a real screen-history stack.
- System back button and predictive-back gesture now pop the same history as the toolbar back button.
- Back navigation returns to the immediately previous page rather than always returning Home.
- Toolbar back navigation is shown whenever history is available.

## Color and trip readability

- Android 12+ now uses the complete wallpaper-derived dynamic color scheme.
- Older devices use distinct primary, secondary, tertiary, error, and surface fallback families.
- Passed, current, upcoming, completed, and cancelled stops use distinct semantic containers and labels.
- On-time, moderate-delay, severe-delay, status, date, platform, carriage, and seat values use different theme roles.
- Station and train search results use separate secondary and tertiary color roles.
