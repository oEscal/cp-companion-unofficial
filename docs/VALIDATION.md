# Validation evidence — public beta preparation

Checked locally on 7 September 2026 against the working tree based on commit `8072bcc`. Results below describe this preparation, not an already-published release. Local Gradle ran on JDK 26.0.1 with Android SDK Platform 37 and Build Tools 37.0.0; hosted CI is configured for Temurin 17 and has not been run for these unpushed changes.

| Check | Result |
| --- | --- |
| XML, resources, English/Portuguese parity, privacy-asset parity, project invariants | Passed |
| Repository publication candidates | Passed; APK/local plan removed from tracking, ignored local SDK configuration allowed |
| Python release/setup regression tests | 6 passed; includes local.properties, release tags, and signer verification |
| Kotlin unit tests | 60 passed, 0 failures/errors/skips; test task executed against the updated source |
| Android lint | Passed with 0 errors; 39 existing/advisory warnings remain, including dependency updates and unused resources |
| Debug APK and instrumentation APK | Built successfully |
| Release compilation and R8 shrinking | Passed |
| Android 16 / API 36 emulator | 10 instrumentation tests passed, including Room, navigation, automation controls, Keystore, and offline privacy dialog |
| Android 11 / API 30 emulator | 10 instrumentation tests passed, 0 failures/skips |
| Gradle wrapper integrity | Generated from Gradle 9.4.1; JAR matched official SHA-256 `55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c` |
| GitHub workflows | actionlint 1.7.12 passed; action commit references verified; shell syntax checks passed |
| Secret scan | Gitleaks 8.30.1: no findings in fetched history (`--log-opts=--all`, scanner reported 17 commits) or current publication candidates |
| Dependency notices | 93 unique resolved release coordinates reviewed against published POM licenses; generated embedded notices match runtime artifacts |
| APK privacy and notices | Verified both privacy languages, generated notices, and upstream license resources are present in the signed test APK |
| Signing pipeline | Passed with a disposable RSA key and independent certificate fingerprint; test key destroyed, no production key used |
| Source packaging | Exact-tag archive/checksums exercised in an isolated test repository; extracted source passes checks, preserves wrapper, and excludes APK/local plan |
| Documentation links | Local Markdown targets checked |

The initial privacy-dialog implementation failed its emulator regression test and was replaced with a Compose paragraph list; the passing result above is for the corrected implementation. The signature parser was also corrected for current Build Tools' `V2 Signer` output and tested against old/new formats and wrong/multiple certificates.

Gitleaks does not establish that every historical datum is non-personal, nor does it inspect every binary as source. No history was rewritten. Source packaging used a test-only license and binary fixture in an isolated repository to exercise packaging; it did not create a release tag, commit, license, or public artifact in the user's repository.

## Reproduce

```bash
python3 tools/validate_source.py
python3 tools/validate_repository.py
python3 -m unittest discover -s tools/tests -v
./build.sh
./gradlew :app:connectedDebugAndroidTest
tools/scan_secrets.sh
```

Use `./build.sh --rerun-tasks` when fresh execution is required. Optional source snapshot verification is `python3 tools/validate_source.py --checksums`; normal development does not enforce a stale delivery snapshot.

## Not yet established

- Real-device Doze, OEM battery behavior, long-route/promoted notifications, and the full journey acceptance matrix in [DEVICE_TESTING.md](DEVICE_TESTING.md).
- An upgrade from a previous public APK using the maintainer's production signing key.
- Hosted GitHub Actions execution, protected release-environment configuration, production signing credentials/backups, and public distribution.
- CP permission/support terms and production polling quotas.
- Private vulnerability reporting availability: GitHub returned HTTP 404 to the enable/status requests during setup.
- The maintainer-selected project license was not visible in the checked local/remote default branch. It was left untouched and must be present on the release commit.

These are tracked in [RELEASING.md](RELEASING.md) and [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md); none are implied by a passing local build.
