'use strict'

// Passive, bounded, in-memory observations only. No dependency on journey
// state, no handler replacement, no transport send/connect/close calls.
function create(options) {
  options = options || {}
  var active = options.enabled === true
  var now = options.now || Date.now
  var schedule = options.setTimeout || setTimeout
  var cancel = options.clearTimeout || clearTimeout
  var started = now()
  var generation = 0
  var timers = []
  var history = []
  var state = {
    connection: 'No events yet', received: 0, accepted: 0, rejected: 0,
    opens: 0, closes: 0, errors: 0, lastMessage: 'None',
    lastIssue: 'None', ready: 'Not checked', diagnosis: 'Not checked',
    checking: false, history: 'No events yet'
  }

  function compact(value) {
    if (value === undefined || value === null) return ''
    if (typeof value !== 'string' && typeof value !== 'number') return ''
    var text = String(value).replace(/[\r\n\t]+/g, ' ')
    return text.length > 96 ? text.slice(0, 93) + '...' : text
  }

  function errorText(data, code) {
    var details = data && typeof data === 'object' ? data : {}
    var number = compact(code !== undefined ? code : details.code)
    var message = compact(details.data || details.message || data)
    return (number ? 'Code ' + number + ': ' : '') + (message || 'No details')
  }

  function snapshot() {
    var copy = {}
    Object.keys(state).forEach(function (key) { copy[key] = state[key] })
    return copy
  }

  function publish() {
    try {
      if (active && options.onChange) options.onChange(snapshot())
    } catch (ignored) {}
  }

  function add(event) {
    history.unshift('+' + Math.max(0, Math.floor((now() - started) / 1000)) + 's ' + event)
    history = history.slice(0, 6)
    state.history = history.join('\n')
  }

  // Observability must never throw into a production callback, including if
  // a platform returns unexpected values or the diagnostics UI is gone.
  function safe(fn) {
    return function () {
      if (!active) return
      try { return fn.apply(null, arguments) } catch (ignored) {}
    }
  }

  var record = safe(function (event, data, result) {
    if (event === 'message') {
      state.received++
      if (result && result.accepted === true) {
        state.accepted++
        state.lastMessage = 'Accepted'
      } else {
        state.rejected++
        state.lastMessage = 'Rejected: ' + compact(result && result.reason || 'No receiver result')
      }
      // Never retain the event/payload, journey, station names or raw JSON.
      add('Message: ' + state.lastMessage)
    } else if (event === 'open') {
      state.opens++
      state.connection = 'Open'
      add('Opened')
    } else if (event === 'close' || event === 'error') {
      if (event === 'close') state.closes++
      else state.errors++
      state.connection = event === 'close' ? 'Closed' : 'Error'
      state.lastIssue = errorText(data)
      add(event + ': ' + state.lastIssue)
    }
    publish()
  })

  function stopChecks() {
    generation++
    timers.forEach(function (timer) { cancel(timer) })
    timers = []
    if (state.ready === 'Checking...') state.ready = 'Check cancelled'
    if (state.diagnosis === 'Checking...') state.diagnosis = 'Check cancelled'
    state.checking = false
  }

  var check = safe(function (connection) {
    if (state.checking) return
    var token = ++generation
    var remaining = 2
    state.checking = true
    state.ready = 'Checking...'
    state.diagnosis = 'Checking...'
    publish()

    function query(method, field, describe) {
      var settled = false
      var timer
      var finish = safe(function (text) {
        if (settled || token !== generation) return
        settled = true
        if (timer !== undefined) cancel(timer)
        timers = timers.filter(function (pending) { return pending !== timer })
        state[field] = text
        remaining--
        state.checking = remaining > 0
        add(field + ': ' + text)
        publish()
      })
      // Some firmware lacks these APIs, or never invokes a callback.
      try {
        if (!connection || typeof connection[method] !== 'function') {
          finish('Unavailable')
          return
        }
        timer = schedule(function () { finish('No callback (12s)') }, 12000)
        timers.push(timer)
        connection[method]({
          timeout: 10000,
          success: safe(function (data) {
            var status = data && data.status
            finish(describe(status))
          }),
          fail: safe(function (data, code) { finish(errorText(data, code)) })
        })
      } catch (error) {
        finish('Unavailable: ' + compact(error && error.message))
      }
    }

    query('getReadyState', 'ready', function (status) {
      if (status === 1) return '1: Connected'
      if (status === 2) return '2: Disconnected'
      return 'Unknown status: ' + compact(status)
    })
    query('diagnosis', 'diagnosis', function (status) {
      var labels = { 0: 'OK', 204: 'Timeout', 1001: 'Phone app not found', 1000: 'Other error' }
      return compact(status) + ': ' + (labels[status] || 'Unknown status')
    })
  })

  return {
    record: record,
    check: check,
    snapshot: snapshot,
    cancelChecks: safe(function () { stopChecks(); publish() }),
    dispose: function () { active = false; stopChecks() }
  }
}

module.exports = { create: create }
