# Validation — 0.4.0

Completed in the delivery environment:

- `build.sh` shell syntax validation.
- Parsing of all Android XML resources and manifests.
- Drawable-reference consistency check.
- Kotlin delimiter/truncation checks across source files.
- Standalone Kotlin compilation of `SmsTicketParser`.
- SMS parser smoke tests covering:
  - round-trip tickets;
  - Portuguese and ISO dates;
  - colon and `h` times;
  - abbreviated and full carriage/seat labels;
  - unlabelled one-way tickets;
  - historic ticket dates.
- Checked that both the inbox importer and notification listener contain their CP sender-validation logic.

A complete Android Gradle build was not run in the delivery environment because it has no Android SDK or downloaded Android/Compose artifacts. Run `./build.sh` in the configured build VM; it builds and tests both `playDebug` and `inboxDebug` variants.
