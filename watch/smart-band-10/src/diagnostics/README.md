# Temporary interconnect diagnostics

Everything in this folder is removable troubleshooting code. It has no imports
from journey state, receiver, or display modules. The production callbacks
still own the interconnect instance; diagnostics never replaces them.

## Use on the physical band

1. Install the newly built RPK yourself through Gadgetbridge. No Android CP
   Companion update is needed for a manual ADB broadcast.
2. Open CP Companion on the band, scroll to **Diagnostics**, and tap it.
3. Send the same ADB journey message. The screen updates while open; journey
   reception continues normally in the owning page.
4. Record **Real messages**, accepted/rejected counts, last link event, last
   error/close, and recent events. The **Check link** control is currently
   disabled by configuration, so no ready-state or diagnosis API calls are
   made.
5. **Back to journey** returns without reinitializing journey state. Send only
   real Gadgetbridge payloads during physical tests.

No automatic probes, reconnects, sends, payload storage, disk writes, or
background polling are added. The retained platform queries are
getReadyState and diagnosis; they are inactive while `linkCheckEnabled` is
false. Closing/hiding the screen cancels local timeout tracking and ignores
pending callbacks; it does not close the interconnect. Counters last for this
page instance; the six most recent events and bounded error details are held
only in memory. Diagnostics are English-only dev UI.

## Interpret results carefully

- Zero messages means no onmessage callback reached this page during this
  session, even if Bluetooth acknowledged Gadgetbridge's launch packet.
- Received + rejected means transport reached the app; the receiver's reason
  distinguishes invalid JSON/protocol from outdated sequence numbers.
- If re-enabled, ready state 1 means connected and 2 means disconnected. This
  is separate from the last observed open/close/error event and does not prove
  payload delivery.
- If re-enabled, diagnosis 0 means OK; 204 means timeout; 1001 means the
  platform reports the counterpart phone app missing; 1000 means another
  error. These native results alone do not prove a signing mismatch or explain
  Gadgetbridge's handshake.
- If re-enabled, Unavailable means the firmware does not expose the queried API
  (or threw). No callback (12s) is our local deadline, not a native diagnosis
  status code.
- The emulator validates UI/receiver behavior, not physical Bluetooth delivery.

API reference: https://iot.mi.com/vela/quickapp/en/features/network/interconnect.html

Local verification: observer and actual-page integration tests pass, including
reception while the panel is open and enabled/disabled journey equivalence.
The RPK builds successfully; the 212 × 520 emulator has been checked for panel
entry, scrolling, and return to the journey. The link-check API calls are not
part of the current physical-band test path.

## Re-enable checklist

The link-check implementation remains intentionally in this folder so it can
be re-tested without reconstructing it. After a Gadgetbridge release includes
the upstream two-way watch-app messaging fix:

1. Update the recorded Gadgetbridge version in the watch and project README.
2. Verify that two increasing-sequence payloads update the open app and that
   the diagnostics counters change before leaving the app.
3. Confirm the target firmware supports `getReadyState` and `diagnosis`.
4. Set `linkCheckEnabled: true` in `config.js`, rebuild, and run the tests.
5. Record the resulting ready state/diagnosis behavior and decide whether to
   keep or remove this temporary diagnostics folder.

## Disable or remove

Set `enabled: false` in `config.js` and rebuild to hide the entry point and
disable event collection and queries. Set `linkCheckEnabled: false` to keep
the diagnostics panel but disable only its platform link-check controls and
API calls. The module stays in the bundle until removed; disabled hooks are
no-ops.

For full removal:

1. In `src/pages/index/index.ux`, remove the diagnostics import tag, panel tag,
   Diagnostics button, both diagnostics require statements, four private
   diagnostics fields, and initialization block.
2. Remove `self.diagnostics.record(...)` calls and diagnostics cleanup lines
   from onHide/onDestroy, plus the marked temporary diagnostics controls block.
3. Remove the `if="{{ !diagnosticsOpen }}"` from the journey scroll. The local
   result variable in onmessage may be removed, preserving the receive call.
4. Delete this folder and `tests/diagnostics.test.js`; remove the temporary
   section in the watch README. Run `npm test`, `npm run validate`, and
   `npm run build`.

No manifest changes, routes, production state fields, signing changes, Android
changes, or protocol changes need reverting.
