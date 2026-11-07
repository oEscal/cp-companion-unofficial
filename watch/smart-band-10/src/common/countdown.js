'use strict'

function formatCountdown(targetEpochMs, nowEpochMs) {
  if (targetEpochMs === null || targetEpochMs === undefined) return "—"

  var remaining = targetEpochMs - nowEpochMs
  if (remaining <= 0) return "Now"

  var minutes = Math.ceil(remaining / 60000)
  if (minutes < 60) return minutes + "m"

  var hours = Math.floor(minutes / 60)
  var remainder = minutes % 60
  return hours + "h" + (remainder > 0 ? " " + remainder + "m" : "")
}

module.exports = {
  formatCountdown: formatCountdown
}
