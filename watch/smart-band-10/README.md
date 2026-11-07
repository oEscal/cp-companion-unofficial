# CP Companion Smart Band 10 Quick App

This is the experimental Xiaomi Vela JS Quick App companion for CP Companion.
It displays a single active journey snapshot delivered through Gadgetbridge's
Watch App Messages API. It does not query CP services, select journeys, or
calculate train delays.

The installed Quick App identifier is `pt.cpcompanion.smartband10`. The Android
app sends it as Gadgetbridge's `appId` extra.

## Local source checks

Node.js is enough for the parser, state, countdown, and fixture tests:

```bash
npm test
npm run validate
```

The tests cover idle, upcoming, delay, next-stop, clear, duplicate,
out-of-order, invalid, unsupported-version, countdown-zero, and previous-session
transitions. They do not require a Vela emulator or a physical band.
`src/common/mock-transport.js` delivers fixture strings into the same receiver
used by the Vela page, so transport-independent state behavior can be tested
without Gadgetbridge.

GitHub Actions runs these tests and source validation, builds the RPK, and
uploads the generated package as the `smart-band-10-watch-build` artifact.

## Vela build and emulator

The `.rpk` build requires Xiaomi's AIoT IDE/Vela toolchain and its `aiot`
command. The repository does not vendor that toolchain. With it installed:

```bash
npm install
npm run build
cd ../..
/path/to/xiaomi-band-development/scripts/emu-deploy.sh --project watch/smart-band-10
node /path/to/xiaomi-band-development/scripts/emulator-grpc.js screenshot /tmp/smart-band-10.png
```

The build artifact is written to `dist/pt.cpcompanion.smartband10.debug.0.1.0.rpk`.
The source checks and `.rpk` build have been verified locally. Fixture tests
cover journey rendering, including long station names and scrolling; the
emulator can validate the packaged shell and diagnostics panel. Physical-band
message delivery remains subject to the Gadgetbridge behavior documented below.

The `scripts/` and `emulator-grpc.js` paths above belong to the separate
`xiaomi-band-development` reference repository; copy only the needed scripts
into a local tool directory if desired. Do not add that repository as a
dependency or submodule.

The Smart Band 10 profile is 212 × 520 physical pixels with a Vela design width
of 106. The page does not require touch interaction to show the primary data.

## Dashboard

The capsule layout uses the Android app's green, blue, and red status palette,
with a train header, station timeline, countdown card, delay/platform chips,
and icon-led departure/arrival times. Each time retains a Scheduled/Estimated
label. Top and bottom padding protects content from the rounded bezel, and
the page scrolls vertically when station names or extra status messages need
more space. English and Portuguese labels are supported.

## Message reception

The page registers `@system.interconnect`'s `onmessage` callback and accepts
only version-1 JSON messages from the Android contract. Node fixtures exercise
the same parser, state, and display modules without adding synthetic controls
to the installed watch app. Emulator rendering and physical Bluetooth
delivery are separate validation steps.

## Device setup

### Temporary on-watch diagnostics

The diagnostic build adds a **Diagnostics** button. It shows real
received/accepted/rejected message counts, link events and error codes. The
**Check link** implementation is retained for future firmware testing, but is
currently disabled by `src/diagnostics/config.js` (`linkCheckEnabled: false`),
so the watch makes no ready-state or diagnosis API calls. Journey reception
continues with the panel open. Install the rebuilt RPK on the physical band to
use it for the ADB test.

This is isolated under `src/diagnostics/`, with `config.js` enable flags,
bounded in-memory observations and a full removal checklist in
[`src/diagnostics/README.md`](src/diagnostics/README.md). No payloads are saved.

### Gadgetbridge configuration

In Gadgetbridge, enable the global Intent API and, for the paired band, enable
Developer settings → Intent API → Allow messages to watch apps. Configure the
band's Bluetooth MAC address in CP Companion's Automation settings and enable
Smart Band synchronization. A broadcast only means that Android issued the
intent; CP Companion cannot claim that the band received or displayed it.

No direct Bluetooth connection, official Xiaomi companion signing key, or CP
Companion application-ID change is part of this integration.

### Current Gadgetbridge delivery behavior

Gadgetbridge 2026.11.0 can deliver the Watch App Messages broadcast, but the
Smart Band 10 currently drains messages only after the watch app reports its
launched session. If the app is already open, a new broadcast may wake the
watch without updating the page; leaving the app and opening it again can
deliver the queued messages together. This is a Gadgetbridge/watch session
behavior, not a parser or display-state decision in this app.

As of 2026-11-06, the follow-up Gadgetbridge [PR #7250](https://codeberg.org/Freeyourgadget/Gadgetbridge/pulls/7250)
is the relevant upstream work and is not yet part of the documented
2026.11.0 baseline. Until a release contains the fix, leave the watch app to
the watch face before sending a new physical test payload, then reopen it.

When that PR, or an equivalent upstream change, is released:

1. Update Gadgetbridge and record the installed version here.
2. Send two payloads with increasing `sequence` values while the watch app
   remains open.
3. Confirm that the second snapshot appears without leaving/reopening the
   app and that the diagnostics counter changes live.
4. Only if the target firmware exposes the APIs reliably, set
   `linkCheckEnabled: true` in `src/diagnostics/config.js` and repeat the
   ready-state/diagnosis checks.
5. Update this section and `src/diagnostics/README.md` with the tested
   Gadgetbridge version and result.
