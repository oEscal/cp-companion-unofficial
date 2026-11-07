'use strict'

var VERSION = 1
var JOURNEY_SNAPSHOT = "journey_snapshot"
var JOURNEY_CLEARED = "journey_cleared"

var STATUSES = {
  waiting: true,
  upcoming_departure: true,
  in_progress: true,
  approaching_destination: true,
  completed: true,
  cancelled: true,
  outdated: true,
  stopped: true
}

var COUNTDOWN_TARGETS = {
  origin: true,
  next_stop: true,
  destination: true
}

var TIME_SOURCES = {
  observed: true,
  estimated: true,
  scheduled: true,
  unknown: true
}

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value)
}

function isInteger(value) {
  return typeof value === "number" && isFinite(value) && Math.floor(value) === value
}

function isEpoch(value, required) {
  if (value === undefined && !required) return true
  return value === null || (isInteger(value) && value >= 0)
}

function isNonEmptyString(value) {
  return typeof value === "string" && value.trim().length > 0
}

function validateJourney(journey) {
  if (!isObject(journey)) return "journey must be an object"
  var requiredStrings = ["id", "train", "origin", "destination", "status", "timeZoneId"]
  for (var i = 0; i < requiredStrings.length; i += 1) {
    if (!isNonEmptyString(journey[requiredStrings[i]])) {
      return requiredStrings[i] + " must be a non-empty string"
    }
  }
  if (!STATUSES[journey.status]) return "unsupported journey status"
  if (journey.nextStop !== undefined && journey.nextStop !== null && !isNonEmptyString(journey.nextStop)) {
    return "nextStop must be null or a non-empty string"
  }
  if (journey.countdownTarget !== undefined && journey.countdownTarget !== null && !COUNTDOWN_TARGETS[journey.countdownTarget]) {
    return "unsupported countdown target"
  }
  if (!isEpoch(journey.countdownEpochMs, false)) return "countdownEpochMs is invalid"
  if (journey.countdownEpochMs !== undefined && journey.countdownEpochMs !== null && journey.countdownTarget == null) {
    return "countdown target is required when a countdown is supplied"
  }
  var epochFields = [
    "scheduledDepartureEpochMs",
    "estimatedDepartureEpochMs",
    "scheduledArrivalEpochMs",
    "estimatedArrivalEpochMs",
    "lastSyncEpochMs"
  ]
  for (var e = 0; e < epochFields.length; e += 1) {
    if (!isEpoch(journey[epochFields[e]], epochFields[e] === "lastSyncEpochMs")) {
      return epochFields[e] + " is invalid"
    }
  }
  if (journey.delayMinutes !== undefined && journey.delayMinutes !== null &&
      !isInteger(journey.delayMinutes)) {
    return "delayMinutes is invalid"
  }
  if (journey.platform !== undefined && journey.platform !== null && !isNonEmptyString(journey.platform)) {
    return "platform must be null or a non-empty string"
  }
  if (journey.timeSource !== undefined && !TIME_SOURCES[journey.timeSource]) {
    return "unsupported time source"
  }
  if (typeof journey.dataStale !== "boolean") return "dataStale must be boolean"
  return null
}

function parseMessage(raw) {
  var text = raw
  if (isObject(raw) && raw.data !== undefined) text = raw.data
  if (typeof text !== "string") return { ok: false, reason: "message is not UTF-8 JSON text" }

  var message
  try {
    message = JSON.parse(text)
  } catch (error) {
    return { ok: false, reason: "invalid JSON" }
  }
  if (!isObject(message)) return { ok: false, reason: "message must be an object" }
  if (message.version !== VERSION) return { ok: false, reason: "unsupported protocol version" }
  if (message.type !== JOURNEY_SNAPSHOT && message.type !== JOURNEY_CLEARED) {
    return { ok: false, reason: "unsupported message type" }
  }
  if (!isInteger(message.sequence) || message.sequence < 0) {
    return { ok: false, reason: "sequence must be a non-negative integer" }
  }
  if (!isInteger(message.sentAtEpochMs) || message.sentAtEpochMs < 0) {
    return { ok: false, reason: "sentAtEpochMs must be a non-negative integer" }
  }

  if (message.type === JOURNEY_SNAPSHOT) {
    var journeyError = validateJourney(message.journey)
    if (journeyError) return { ok: false, reason: journeyError }
    return { ok: true, value: message }
  }

  if (!isNonEmptyString(message.journeyId)) {
    return { ok: false, reason: "journeyId must be a non-empty string" }
  }
  return { ok: true, value: message }
}

module.exports = {
  VERSION: VERSION,
  JOURNEY_SNAPSHOT: JOURNEY_SNAPSHOT,
  JOURNEY_CLEARED: JOURNEY_CLEARED,
  parseMessage: parseMessage,
  validateJourney: validateJourney
}
