'use strict'

/** Test-only transport that delivers the same UTF-8 payloads as interconnect. */
function MockTransport(receiver) {
  this.receiver = receiver
}

MockTransport.prototype.send = function (payload) {
  return this.receiver(payload)
}

module.exports = MockTransport
