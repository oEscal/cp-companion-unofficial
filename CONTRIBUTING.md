# Contributing

Small bug fixes, documentation improvements, and reproducible reports are welcome. Discuss larger changes in an issue before implementation.

## Development

Follow the build requirements in [README.md](README.md). Android Studio can create `local.properties`; it stays local. Never commit a keystore, API credential, traffic capture, APK, or passenger data.

```bash
python3 tools/validate_source.py
python3 tools/validate_repository.py
python3 -m unittest discover -s tools/tests -v
./build.sh
./gradlew :app:connectedDebugAndroidTest  # running emulator/device required
```

Use `gradlew.bat` and `python` on Windows. `./build.sh --rerun-tasks` forces a fresh validation run. Ordinary edits do not require source-checksum regeneration. The optional `docs/SOURCE_SHA256SUMS.txt` is a delivery snapshot; refresh with `python3 tools/update_source_checksums.py` and check it with `python3 tools/validate_source.py --checksums` when preparing a source delivery.

## Code map

- `network`: CP configuration, HTTP requests, rate limiting, shared requests, and caches.
- `data`: encrypted persistence, Room database, preferences, and repository.
- `domain` and `model`: journey state, normalization, and time-zone logic.
- `sms`: local parsing and import trust boundaries.
- `automation`, `worker`, and `tracking`: scheduling, recovery, foreground service, and completion.
- `ui/feature`: Compose screens; `res/values` and `res/values-pt`: matching translations.

Preserve coroutine cancellation, bounded parsing/polling, HTTPS host validation, private notification visibility, and explicit user consent. New user-visible strings need English and Portuguese resources. Add regression tests for behavior changes; real-device scheduling tests are recorded in [DEVICE_TESTING.md](docs/DEVICE_TESTING.md).

## Dependency updates

Versions live in `gradle/libs.versions.toml`; Gradle may select newer transitive versions. Do not assume that the requested Compose version equals the resolved runtime version. Review alpha/beta API changes and the resolved dependency graph.

1. Run `./gradlew :app:exportReleaseDependencies`.
2. Update `docs/third-party/dependencies.json` for changed coordinates using each artifact's published Maven POM, including inherited licenses. Review embedded notices and any new license text requirements. Do not guess a license from the group name.
3. Run `python3 tools/generate_notices.py`, then `./build.sh`. This preserves embedded notices and checks every resolved runtime coordinate against the reviewed inventory.

Gradle wrapper updates must include `gradlew`, `gradlew.bat`, the wrapper JAR, properties, and the official distribution SHA-256. CI validates the wrapper JAR and requires immutable action commit pins.

## Pull requests and reports

Explain the concrete problem, resulting behavior, and validation. Use invented ticket references and journey fixtures. Do not attach raw inbox exports or unrestricted logcat captures. Run `tools/scan_secrets.sh` before publication; Linux x86_64 can bootstrap the pinned scanner, while other systems need Gitleaks installed. Fetch all branches/tags first for a complete local history scan.

The maintainer-selected project license governs contributions. Confirm you have the right to contribute code and assets; third-party material needs its original license and attribution. CP branding and data require separate consideration.
