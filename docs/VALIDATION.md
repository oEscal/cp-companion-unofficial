# Validation report — 0.7.0

## Checks completed in this environment

- Parsed every Android manifest/resource XML file.
- Verified default/Portuguese string, plural, and array resource parity.
- Verified Kotlin/XML resource references against declared resources.
- Verified application ID, compile/target SDK, backup policy, cleartext policy, and Material 3 Expressive dependency invariants.
- Checked shell syntax for `build.sh` and `gradlew`.
- Checked Git whitespace/error conditions with `git diff --check`.
- Verified every file listed in `docs/SOURCE_SHA256SUMS.txt` against its SHA-256 digest.
- Reviewed exported components, required confirmation for shared ticket imports, and restricted notification imports to the default SMS app.
- Reviewed suspend failure paths and corrected cancellation swallowing in workers, ticket validation, imports, and UI requests.
- Reviewed CP URL construction, runtime configuration validation, global 429 coordination, persistence writes, and encrypted-value recovery.
- Verified private tracking-notification visibility, Gradle bootstrap checksum enforcement, and immutable CI action revisions.
- Verified no legacy Compose Material imports are used; all UI is under the Material 3 Expressive theme.
- Verified the final archive excludes generated build products, local SDK configuration, captures, and signing material.

Run the source-only checks directly with:

```bash
python3 tools/update_source_checksums.py
python3 tools/validate_source.py
```

## Android build limitation

Outbound DNS/network access was unavailable and no Android SDK or Gradle distribution was preinstalled. A fresh Gradle compile, lint execution, release shrink, and connected test run could not be performed against the modified source in this environment. Old generated reports from the supplied archive were treated as stale and are excluded from the delivery.

Run on a configured machine or through the included GitHub Actions workflow:

```bash
./build.sh
./build.sh connectedDebugAndroidTest
```

## Required device validation

- T-60 activation with process death, Doze, Battery Saver, exact-alarm revocation, reboot, update, clock change, and time-zone change.
- Notification permission/channel revocation during a live session.
- Android 16/17 promoted notification behavior and long routes with more than four progress points.
- Two overlapping tickets, a delayed first journey, and service foreground-notification ownership transfer.
- Temporary missing ETD/ETA, last-seen delay retention, cancellation/suppression, and destination completion.
- Duplicate/progressively updated messaging notifications and concurrent inbox scans.
- HTTP 429 with both delta-seconds and HTTP-date `Retry-After` values.
