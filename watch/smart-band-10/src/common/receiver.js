'use strict'

var protocol = require("./protocol")
var stateStore = require("./state")

function createReceiver(state, onUpdate) {
  return function receive(raw) {
    var parsed = protocol.parseMessage(raw)
    if (!parsed.ok) {
      state.lastError = parsed.reason
      if (onUpdate) onUpdate({ accepted: false, reason: parsed.reason })
      return { accepted: false, reason: parsed.reason }
    }
    var result = stateStore.applyMessage(state, parsed.value)
    if (onUpdate) onUpdate(result)
    return result
  }
}

module.exports = {
  createReceiver: createReceiver
}
