# Remaining work

There was no actionable `TODO.md` in the supplied archive. `PLAN.md` is a historical plan, while the implemented tracking scope is documented in `docs/IMPLEMENTATION_STATUS.md`.

## Required before a public signed release

- Run `./build.sh` with Android SDK platform 37 and execute connected tests on representative Android 11–17 devices.
- Verify exact/inexact T-60 activation under Doze, Battery Saver, reboot, application update, clock/time-zone changes, notification revocation, and OEM background restrictions.
- Verify Android Live Update promotion and the long-route progress visualization on several Android 16/17 System UI builds.
- Verify concurrent overlapping tickets, delayed arrivals beyond the scheduled destination time, cancellation/suppression, temporary missing ETD/ETA, and HTTP 429 recovery against live CP data.
- Configure the private release keystore through the four `CP_RELEASE_*` environment variables and retain the resulting signed artifact outside source control.

## Upstream-contract blockers

The following are deliberately not implemented because the supplied evidence does not establish a safe supported contract: authenticated CP account access, ticket purchase/payment, CP card data, card QR, and ticket QR/PDF retrieval. Implement these only after the endpoint contract, authorization flow, storage requirements, and provider permission are verified.

## Build convenience

`build.sh` securely bootstraps Gradle when `gradle-wrapper.jar` is absent. A maintainer may regenerate and commit the Gradle 9.4.1 wrapper JAR from a trusted Gradle installation for direct `./gradlew` use.
