'use strict'

// Temporary physical-band troubleshooting. Set enabled to false and rebuild
// to disable the entry point and event recording.
//
// Keep the link-check implementation in place, but leave its UI/API calls
// disabled while the current firmware/Gadgetbridge combination is not a
// reliable delivery diagnostic. Re-enable only after validating the target
// firmware and Gadgetbridge build.
module.exports = {
  enabled: true,
  linkCheckEnabled: false
}
