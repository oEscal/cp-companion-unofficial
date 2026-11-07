'use strict'

var test = require("node:test")
var assert = require("node:assert/strict")
var fs = require("node:fs")
var path = require("node:path")
var protocol = require("../src/common/protocol")
var stateStore = require("../src/common/state")
var countdown = require("../src/common/countdown")
var display = require("../src/common/display")
var receiver = require("../src/common/receiver")
var MockTransport = require("../src/common/mock-transport")

function fixture(name) {
  return fs.readFileSync(path.join(__dirname, "..", "fixtures", name), "utf8")
}

test("T01 no journey starts in a clear waiting state", function () {
  var state = stateStore.createState()
  var view = display.createDisplayModel(state, 0, "en")
  assert.equal(view.title, "Waiting for sync")
  assert.equal(view.countdown, "—")
})

test("T02 and T03 upcoming and delay snapshots are accepted", function () {
  var state = stateStore.createState()
  var upcoming = protocol.parseMessage(fixture("upcoming.json"))
  assert.equal(upcoming.ok, true)
  assert.equal(stateStore.applyMessage(state, upcoming.value).accepted, true)
  assert.equal(state.snapshot.status, "upcoming_departure")

  var delayed = protocol.parseMessage(fixture("in-progress.json"))
  assert.equal(delayed.ok, true)
  assert.equal(stateStore.applyMessage(state, delayed.value).accepted, true)
  assert.equal(state.snapshot.dataStale, false)
  assert.equal(state.snapshot.delayMinutes, 10)
})

test("T15 mock transport feeds the same receiver used by the page", function () {
  var state = stateStore.createState()
  var transport = new MockTransport(receiver.createReceiver(state))
  var result = transport.send(fixture("upcoming.json"))
  assert.equal(result.accepted, true)
  assert.equal(state.snapshot.train, "IC 000")
})

test("successive snapshots and a clear message use the normal receiver", function () {
  var state = stateStore.createState()
  var transport = new MockTransport(receiver.createReceiver(state))
  var upcoming = JSON.parse(fixture("upcoming.json"))
  upcoming.sequence = 200
  assert.equal(transport.send(JSON.stringify(upcoming)).accepted, true)
  assert.equal(state.snapshot.status, "upcoming_departure")
  var inProgress = JSON.parse(fixture("in-progress.json"))
  inProgress.sequence = 201
  assert.equal(transport.send(JSON.stringify(inProgress)).accepted, true)
  assert.equal(state.snapshot.status, "in_progress")
  var cleared = JSON.parse(fixture("cleared.json"))
  cleared.sequence = 202
  assert.equal(transport.send(JSON.stringify(cleared)).accepted, true)
  assert.equal(state.snapshot, null)
})

test("T05 next stop is rendered as the countdown target", function () {
  var state = stateStore.createState()
  var message = protocol.parseMessage(fixture("in-progress.json"))
  stateStore.applyMessage(state, message.value)
  var view = display.createDisplayModel(state, 1793997000000, "en")
  assert.equal(view.countdownLabel, "Next stop")
  assert.equal(view.nextStop, "Next: Pombal")
  assert.equal(view.countdown, "10m")
})

test("T10 clear removes the active journey", function () {
  var state = stateStore.createState()
  stateStore.applyMessage(state, protocol.parseMessage(fixture("upcoming.json")).value)
  var result = stateStore.applyMessage(state, protocol.parseMessage(fixture("cleared.json")).value)
  assert.equal(result.accepted, true)
  assert.equal(state.snapshot, null)
  assert.equal(display.createDisplayModel(state, 0, "en").title, "No journey")
})

test("T11 and T12 duplicates and out-of-order updates are rejected", function () {
  var state = stateStore.createState()
  var first = protocol.parseMessage(fixture("upcoming.json")).value
  assert.equal(stateStore.applyMessage(state, first).accepted, true)
  assert.equal(stateStore.applyMessage(state, first).accepted, false)
  var older = JSON.stringify(Object.assign({}, first, { sequence: 99, sentAtEpochMs: 99 }))
  assert.equal(stateStore.applyMessage(state, JSON.parse(older)).accepted, false)
})

test("T13 and T14 invalid JSON and unsupported versions are rejected safely", function () {
  assert.equal(protocol.parseMessage("not json").ok, false)
  assert.equal(protocol.parseMessage(fixture("invalid.json")).reason, "unsupported protocol version")
})

test("T21 countdown never displays negative time", function () {
  assert.equal(countdown.formatCountdown(1000, 2000), "Now")
  assert.equal(countdown.formatCountdown(60 * 60 * 1000 + 1, 0), "1h 1m")
})

test("T25 a late clear for the previous journey cannot clear the current one", function () {
  var state = stateStore.createState()
  var first = protocol.parseMessage(fixture("upcoming.json")).value
  stateStore.applyMessage(state, first)
  var second = JSON.parse(JSON.stringify(first))
  second.sequence = 101
  second.journey = Object.assign({}, second.journey, { id: "session-new" })
  stateStore.applyMessage(state, second)
  var oldClear = JSON.parse(fixture("cleared.json"))
  oldClear.sequence = 102
  assert.equal(stateStore.applyMessage(state, oldClear).accepted, false)
  assert.equal(state.journeyId, "session-new")
})

test("dashboard retains full station names and phone-supplied local times", function () {
  var state = stateStore.createState()
  var message = JSON.parse(fixture("in-progress.json"))
  message.sequence = 300
  message.journey.origin = "Vila Nova de Gaia-Devesas"
  message.journey.destination = "Vila Real de Santo António"
  message.journey.nextStop = "Vila Franca de Xira"
  receiver.createReceiver(state)(JSON.stringify(message))
  state.snapshot.estimatedArrivalLocal = "23:47"
  var view = display.createDisplayModel(state, Date.now(), "en")
  assert.equal(view.origin, "Vila Nova de Gaia-Devesas")
  assert.equal(view.destination, "Vila Real de Santo António")
  assert.equal(view.nextStation, "Vila Franca de Xira")
  assert.equal(view.arrivalTime, "23:47")
  assert.equal(view.arrivalSource, "Estimated")
  assert.equal(view.delayTone, "danger")
})

test("dashboard distinguishes unknown values, stale data and disconnection", function () {
  var state = stateStore.createState()
  var message = JSON.parse(fixture("upcoming.json"))
  message.sequence = 300
  receiver.createReceiver(state)(JSON.stringify(message))
  state.snapshot.delayMinutes = null
  state.snapshot.platform = null
  state.snapshot.estimatedArrivalLocal = null
  state.snapshot.scheduledArrivalLocal = null
  stateStore.setConnection(state, "disconnected")
  var view = display.createDisplayModel(state, Date.now(), "en")
  assert.equal(view.hasDelay, false)
  assert.equal(view.platformValue, "")
  assert.equal(view.arrivalTime, "—")
  assert.equal(view.arrivalSource, "—")
  assert.equal(view.linkIcon, "offline")
  assert.equal(view.notice, "Disconnected")
  state.snapshot.dataStale = true
  assert.equal(display.createDisplayModel(state, Date.now(), "pt").notice, "Dados antigos")
})
