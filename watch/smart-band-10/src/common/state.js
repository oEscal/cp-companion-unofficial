'use strict'

function createState() {
  return {
    connection: "disconnected",
    snapshot: null,
    journeyId: null,
    sequence: -1,
    hasAcceptedMessage: false,
    lastError: null
  }
}

function setConnection(state, connection) {
  state.connection = connection
  return state
}

function applyMessage(state, message) {
  if (message.sequence <= state.sequence) {
    state.lastError = "outdated update"
    return { accepted: false, reason: "outdated update" }
  }

  if (message.type === "journey_cleared") {
    // A clear for an older journey must not clear a newer active journey even
    // if it arrives with a larger sequence after a transport reordering.
    if (state.journeyId !== null && message.journeyId !== state.journeyId) {
      state.lastError = "clear belongs to another journey"
      return { accepted: false, reason: "clear belongs to another journey" }
    }
    state.snapshot = null
    state.journeyId = null
    state.sequence = message.sequence
    state.hasAcceptedMessage = true
    state.lastError = null
    return { accepted: true, state: state }
  }

  var journey = message.journey
  if (state.journeyId !== null && journey.id !== state.journeyId && message.sequence <= state.sequence) {
    state.lastError = "message belongs to an older journey"
    return { accepted: false, reason: "message belongs to an older journey" }
  }
  state.snapshot = journey
  state.journeyId = journey.id
  state.sequence = message.sequence
  state.hasAcceptedMessage = true
  state.lastError = null
  state.connection = "connected"
  return { accepted: true, state: state }
}

module.exports = {
  createState: createState,
  setConnection: setConnection,
  applyMessage: applyMessage
}
