# Signed releases

The `Release APK` workflow runs after source/secret checks, unit tests, lint, builds, and API 30/36 emulator tests. A pushed version tag creates the GitHub release automatically: tags containing `-alpha.N`, `-beta.N`, or `-rc.N` are marked prereleases, while a plain `vX.Y.Z` tag is published as a normal release. A manual run can target an existing tag and choose draft or published output. CI debug/unsigned artifacts are not distribution releases.

## One-time maintainer setup

- Ensure the maintainer-selected license is present in the committed root. The license reported by the maintainer was not visible in the local or remote default-branch checkout during this preparation; it has not been created or modified here.
- Confirm rights to source/assets and complete the upstream clarification in [UPSTREAM.md](UPSTREAM.md). Enable/test private vulnerability reporting as described in [SECURITY.md](../SECURITY.md).
- Create a GitHub environment named `release`, restrict it to release tags, and enable required reviewers where the repository plan supports them. Never give signing secrets to pull-request workflows.
- Use an existing release key if previous signed versions exist. For the first release, create a private long-lived signing key using Android Studio's signing wizard or `keytool`; keep it outside the repository.
- Store two encrypted offline backups of the keystore and store passwords separately. Record alias, certificate SHA-256, validity, and recovery instructions in a private password manager. Test recovering a copy before the first public release. Losing/changing the key can prevent in-place updates.

Add these **environment secrets**, using the same private key for every update:

| Secret | Value |
| --- | --- |
| `CP_RELEASE_KEYSTORE_BASE64` | Base64 of the keystore, without line breaks |
| `CP_RELEASE_STORE_PASSWORD` | Keystore password |
| `CP_RELEASE_KEY_ALIAS` | Signing alias |
| `CP_RELEASE_KEY_PASSWORD` | Key password |

Set environment **variable** `CP_RELEASE_CERT_SHA256` to the certificate SHA-256, obtained independently with `keytool -list -v -keystore /PRIVATE/PATH/release.jks`. This public fingerprint is checked against the built APK before upload. Base64 is transport encoding, not encryption; treat it as a secret.

Do not use debug or disposable test keys for public releases. This repository does not generate or retain a production key for you.

## Prepare a version

1. Update `versionCode` monotonically and set `versionName` in `app/build.gradle.kts`; keep the HTTP user-agent and source validation invariants consistent. The initial prepared beta uses application version `0.7.0` and a tag such as `v0.7.0-beta.1`.
2. Update [RELEASE_NOTES.md](RELEASE_NOTES.md), resolved dependency notices, and validation evidence. Complete/record the [device and signed-upgrade runbook](DEVICE_TESTING.md). Keep unsupported scenarios marked pending.
3. Run `./build.sh`, `python3 -m unittest discover -s tools/tests -v`, device tests, and `tools/scan_secrets.sh`. Refresh optional source checksums if delivering that snapshot.
4. Commit the reviewed source including the project license. The release tool rejects dirty checkouts, mismatched tags, missing licenses, and tracked private/generated files.
5. Create and push an annotated tag `vVERSION` or `vVERSION-beta.N` pointing to that commit. The `Release APK` workflow starts automatically. With GitHub CLI, a manual rerun for an existing tag is `gh workflow run release.yml -f tag=v0.7.0-beta.1 -f draft=true`; do not dispatch it without the tag input.
6. Inspect the release APK, source archive, `SHA256SUMS`, `SIGNING_CERTIFICATE.txt`, and release notes. Verify the certificate and perform the same-key upgrade test. For a manual run, use `draft=true` while reviewing; a pushed stable tag publishes automatically after the protected release environment approves the job.

If a draft with the same tag already exists, the workflow fails rather than overwriting release assets. Inspect the existing draft and explicitly remove/recreate it only if that is intended.

## Local signed build

Set `ANDROID_HOME` (Build Tools 37.0.0 required), `CP_RELEASE_STORE_FILE` to an existing private keystore, the three password/alias variables, and `CP_RELEASE_CERT_SHA256`. Populate passwords through your password manager or shell prompts; do not put them in scripts or shell history.

```bash
tools/build_release.sh
python3 tools/prepare_release.py --check-ref refs/tags/v0.7.0-beta.1 --package
```

`build_release.sh` validates signatures and stages only verified artifacts under ignored `dist/`. If any signing variable is supplied, all four Gradle signing variables must be present; partial configuration fails instead of silently producing an unsigned release. The source archive is built from the exact tag, excluding local/generated files. SHA-256 values cover the APK, certificate report, and source archive.

## Google Play

The initial distribution target is a signed GitHub beta APK. No Play eligibility is claimed. This app requests READ_SMS and uses a special-use foreground service. Before a Play submission, assess the current [SMS permission policy](https://support.google.com/googleplay/android-developer/answer/10208820?hl=en), [foreground-service requirements](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use), privacy disclosures, and Data safety declarations. Remove inbox access from a future Play variant if no eligible exception applies; runtime opt-in alone is insufficient. Notification access also needs truthful prominent disclosure. Do not submit until those channel-specific requirements are resolved.
