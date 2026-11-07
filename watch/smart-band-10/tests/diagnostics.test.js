'use strict'

var test = require('node:test')
var assert = require('node:assert/strict')
var fs = require('node:fs')
var path = require('node:path')
var vm = require('node:vm')
var diagnostics = require('../src/diagnostics/session')

function session(extra) {
  var jobs = new Map()
  var next = 0
  var instance = diagnostics.create(Object.assign({
    enabled: true,
    now: function () { return 1000 },
    setTimeout: function (fn) { jobs.set(++next, fn); return next },
    clearTimeout: function (id) { jobs.delete(id) }
  }, extra))
  return { instance: instance, jobs: jobs }
}

test('passive observations count real deliveries and retain no payloads', function () {
  var d = session().instance
  d.record('open')
  d.record('message', 'PRIVATE JSON', { accepted: true, state: { secret: 'PRIVATE JOURNEY' } })
  d.record('message', null, { accepted: false, reason: 'outdated update' })
  d.record('error', { code: 1001, data: 'Mobile app not installed' })
  var report = d.snapshot()
  assert.equal(report.received, 2)
  assert.equal(report.accepted, 1)
  assert.equal(report.rejected, 1)
  assert.equal(report.opens, 1)
  assert.equal(report.errors, 1)
  assert.equal(report.connection, 'Error')
  assert.match(report.lastIssue, /1001.*Mobile app not installed/)
  assert.match(report.lastMessage, /outdated update/)
  assert.doesNotMatch(JSON.stringify(report), /PRIVATE/)
  report.received = 99
  assert.equal(d.snapshot().received, 2)
})

test('history and platform error strings are bounded', function () {
  var d = session().instance
  for (var i = 0; i < 50; i++) d.record('close', { code: 1006, data: 'x'.repeat(1000) })
  var report = d.snapshot()
  assert.equal(report.closes, 50)
  assert.equal(report.history.split('\n').length, 6)
  assert.ok(report.lastIssue.length < 120)
})

test('checks are explicit, read-only, coalesced and clean up timers', function () {
  var harness = session()
  var d = harness.instance
  var callbacks = {}
  var calls = []
  var connection = {
    getReadyState: function (options) { calls.push('ready'); callbacks.ready = options },
    diagnosis: function (options) { calls.push('diagnosis'); callbacks.diagnosis = options },
    send: function () { assert.fail('must not send') },
    close: function () { assert.fail('must not close') }
  }
  assert.deepEqual(calls, [])
  d.check(connection)
  d.check(connection)
  assert.deepEqual(calls, ['ready', 'diagnosis'])
  callbacks.ready.success({ status: 2 })
  assert.equal(d.snapshot().checking, true)
  callbacks.diagnosis.success({ status: 1001 })
  assert.equal(d.snapshot().ready, '2: Disconnected')
  assert.equal(d.snapshot().diagnosis, '1001: Phone app not found')
  assert.equal(d.snapshot().checking, false)
  assert.equal(d.snapshot().connection, 'No events yet')
  assert.equal(harness.jobs.size, 0)
  callbacks.diagnosis.fail('late duplicate', 999)
  assert.equal(d.snapshot().diagnosis, '1001: Phone app not found')
})

test('missing APIs, throws, both failure signatures and stalled callbacks are safe', function () {
  var harness = session()
  var d = harness.instance
  d.check({})
  assert.equal(d.snapshot().ready, 'Unavailable')
  assert.equal(d.snapshot().checking, false)
  d.check({
    getReadyState: function (options) { options.fail('offline', 1006) },
    diagnosis: function (options) { options.fail({ code: 204, data: 'timed out' }) }
  })
  assert.equal(d.snapshot().ready, 'Code 1006: offline')
  assert.equal(d.snapshot().diagnosis, 'Code 204: timed out')
  d.check({
    getReadyState: function () { throw new Error('not supported') },
    diagnosis: function () {}
  })
  assert.match(d.snapshot().ready, /not supported/)
  Array.from(harness.jobs.values()).forEach(function (run) { run() })
  assert.equal(d.snapshot().diagnosis, 'No callback (12s)')
  assert.equal(d.snapshot().checking, false)
  assert.equal(harness.jobs.size, 0)
})

test('closing, reopening and disposal ignore old callbacks without touching connection', function () {
  var harness = session()
  var d = harness.instance
  var callbacks = []
  var connection = {
    getReadyState: function (options) { callbacks.push(options) },
    diagnosis: function (options) { callbacks.push(options) }
  }
  d.check(connection)
  d.cancelChecks()
  assert.equal(harness.jobs.size, 0)
  assert.equal(d.snapshot().ready, 'Check cancelled')
  d.check(connection)
  callbacks[0].success({ status: 1 })
  callbacks[1].success({ status: 0 })
  assert.equal(d.snapshot().ready, 'Checking...')
  callbacks[2].success({ status: 2 })
  callbacks[3].success({ status: 204 })
  assert.equal(d.snapshot().diagnosis, '204: Timeout')
  d.dispose()
  var before = d.snapshot()
  d.record('open')
  d.check(connection)
  assert.deepEqual(d.snapshot(), before)
  assert.equal(harness.jobs.size, 0)
})

test('disabled diagnostics do not observe, publish, schedule or call platform APIs', function () {
  var harness = session({ enabled: false, onChange: function () { assert.fail('disabled UI update') } })
  var d = harness.instance
  var before = d.snapshot()
  d.record('message', null, { accepted: true })
  d.check({ getReadyState: function () { assert.fail('disabled check') } })
  assert.deepEqual(d.snapshot(), before)
  assert.equal(harness.jobs.size, 0)
})

test('diagnostics UI failures cannot escape into the existing event receiver', function () {
  var d = session({ onChange: function () { throw new Error('UI gone') } }).instance
  assert.doesNotThrow(function () { d.record('open'); d.check({}) })
  assert.equal(d.snapshot().checking, false)
})

// Execute the actual page script with just the native APIs replaced. This
// catches integration regressions that testing the observer alone would miss.
function page(enabled) {
  var filename = path.join(__dirname, '../src/pages/index/index.ux')
  var source = fs.readFileSync(filename, 'utf8').split('<script>')[1].split('</script>')[0]
  var connection = {}
  var context = {
    require: function (name) {
      if (name === '@system.interconnect') return { instance: function () { return connection } }
      if (name === '@system.device') return { getInfo: function (options) { options.success({ language: 'en' }) } }
      if (name === '../../diagnostics/config') return { enabled: enabled, linkCheckEnabled: false }
      return require(path.resolve(path.dirname(filename), name))
    },
    setInterval: function () { return 1 }, clearInterval: function () {}
  }
  var definition = vm.runInNewContext(source.replace('export default', 'var definition =') + '\ndefinition', context)
  Object.assign(definition, definition.private)
  definition.onInit()
  return { page: definition, connection: connection }
}

test('actual page handles messages identically with diagnostics enabled or disabled', function () {
  var enabled = page(true)
  var disabled = page(false)
  var raw = fs.readFileSync(path.join(__dirname, '../fixtures/upcoming.json'), 'utf8')
  ;[enabled, disabled].forEach(function (harness) {
    harness.connection.onopen({})
    harness.connection.onmessage({ data: raw })
    harness.connection.onmessage({ data: raw })
    harness.connection.onmessage({ data: 'bad JSON' })
    harness.connection.onerror({ code: 1006, data: 'offline' })
  })
  assert.deepEqual(enabled.page.model, disabled.page.model)
  assert.deepEqual(enabled.page.view, disabled.page.view)
  assert.equal(enabled.page.diagnostics.snapshot().received, 3)
  assert.equal(enabled.page.diagnostics.snapshot().accepted, 1)
  assert.equal(enabled.page.diagnostics.snapshot().rejected, 2)
  var before = JSON.stringify(enabled.page.model)
  enabled.page.openDiagnostics()
  enabled.page.closeDiagnostics()
  assert.equal(JSON.stringify(enabled.page.model), before)
  assert.equal(enabled.page.diagnostics.snapshot().received, 3)
  disabled.page.openDiagnostics()
  assert.equal(disabled.page.diagnosticsOpen, false)
  assert.equal(enabled.page.diagnosticsLinkCheckEnabled, false)
  enabled.page.checkDiagnostics()
  assert.equal(enabled.page.diagnostics.snapshot().ready, 'Not checked')
  enabled.page.onDestroy()
  disabled.page.onDestroy()
})

test('messages continue updating the journey and panel while diagnostics is visible', function () {
  var harness = page(true)
  harness.page.openDiagnostics()
  var raw = fs.readFileSync(path.join(__dirname, '../fixtures/in-progress.json'), 'utf8')
  harness.connection.onmessage({ data: raw })
  assert.equal(harness.page.diagnosticsOpen, true)
  assert.equal(harness.page.diagnosticsReport.received, 1)
  assert.equal(harness.page.diagnosticsReport.accepted, 1)
  assert.equal(harness.page.model.snapshot.status, 'in_progress')
  var snapshot = harness.page.model.snapshot
  harness.page.closeDiagnostics()
  assert.equal(harness.page.model.snapshot, snapshot)
  assert.equal(harness.page.view.hasJourney, true)
  harness.page.onDestroy()
})
