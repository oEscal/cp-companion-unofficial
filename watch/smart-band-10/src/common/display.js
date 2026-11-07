'use strict'

var countdown = require("./countdown")

var STRINGS = {
  en: {
    app: "CP Companion",
    waiting: "Waiting for sync",
    noJourney: "No journey",
    invalid: "Invalid update",
    disconnected: "Disconnected",
    connected: "Connected",
    noCountdown: "No countdown",
    origin: "Origin event",
    nextStop: "Next stop",
    destination: "Destination",
    upcoming: "Upcoming departure",
    inProgress: "Journey in progress",
    approaching: "Approaching destination",
    completed: "Completed",
    cancelled: "Cancelled",
    stopped: "Stopped",
    waitingState: "Waiting",
    outdated: "Data outdated",
    departure: "Departure",
    arrival: "Arrival",
    live: "Live",
    estimated: "Estimated",
    scheduled: "Scheduled",
    next: "Next",
    platform: "Platform",
    synced: "Synced",
    invalidConnection: "Invalid update"
  },
  pt: {
    app: "CP Companion",
    waiting: "A aguardar sincronização",
    noJourney: "Sem viagem",
    invalid: "Atualização inválida",
    disconnected: "Desligado",
    connected: "Ligado",
    noCountdown: "Sem contagem decrescente",
    origin: "Evento de origem",
    nextStop: "Próxima paragem",
    destination: "Destino",
    upcoming: "Partida próxima",
    inProgress: "Viagem em curso",
    approaching: "A aproximar-se do destino",
    completed: "Concluída",
    cancelled: "Cancelada",
    stopped: "Parada",
    waitingState: "A aguardar",
    outdated: "Dados desatualizados",
    departure: "Partida",
    arrival: "Chegada",
    live: "Em direto",
    estimated: "Estimado",
    scheduled: "Previsto",
    next: "Próxima",
    platform: "Linha",
    synced: "Sincronizado",
    invalidConnection: "Atualização inválida"
  }
}

function strings(language) {
  return STRINGS[language] || STRINGS.en
}

function statusLabel(status, labels) {
  var values = {
    upcoming_departure: labels.upcoming,
    in_progress: labels.inProgress,
    approaching_destination: labels.approaching,
    completed: labels.completed,
    cancelled: labels.cancelled,
    stopped: labels.stopped,
    waiting: labels.waitingState,
    outdated: labels.outdated
  }
  return values[status] || labels.waitingState
}

function timeSourceLabel(source, labels) {
  var values = {
    observed: labels.live,
    estimated: labels.estimated,
    scheduled: labels.scheduled,
    unknown: ""
  }
  return values[source] || ""
}

function countdownTargetLabel(target, labels) {
  if (target === "origin") return labels.origin
  if (target === "next_stop") return labels.nextStop
  if (target === "destination") return labels.destination
  return labels.noCountdown
}

function localTime(snapshot, prefix, labels) {
  var estimated = snapshot["estimated" + prefix + "Local"]
  var scheduled = snapshot["scheduled" + prefix + "Local"]
  var source = estimated && (!scheduled || estimated !== scheduled)
    ? labels.estimated
    : (scheduled ? labels.scheduled : "")
  var value = estimated || scheduled
  if (!value) return ""
  return (prefix === "Departure" ? labels.departure : labels.arrival) + ": " + value + (source ? " · " + source : "")
}

function clock(epochMs) {
  if (epochMs === null || epochMs === undefined) return "—"
  var date = new Date(epochMs)
  var hours = date.getHours() < 10 ? "0" + date.getHours() : String(date.getHours())
  var minutes = date.getMinutes() < 10 ? "0" + date.getMinutes() : String(date.getMinutes())
  return hours + ":" + minutes
}

function createTextModel(appState, nowEpochMs, language) {
  var labels = strings(language)
  if (!appState.snapshot) {
    return {
      title: appState.hasAcceptedMessage ? labels.noJourney : labels.waiting,
      train: "",
      route: "",
      status: appState.lastError ? labels.invalid : (appState.hasAcceptedMessage ? labels.noJourney : labels.waiting),
      countdownLabel: labels.noCountdown,
      countdown: "—",
      nextStop: "",
      departure: "",
      arrival: "",
      delay: "",
      platform: "",
      synced: "",
      connection: appState.connection === "connected" ? labels.connected : labels.disconnected
    }
  }

  var snapshot = appState.snapshot
  var stale = snapshot.dataStale ? " · " + labels.outdated : ""
  var delay = snapshot.delayMinutes === null || snapshot.delayMinutes === undefined
    ? ""
    : (snapshot.delayMinutes === 0
      ? "0 min"
      : (snapshot.delayMinutes > 0 ? "+" : "") + snapshot.delayMinutes + " min")
  return {
    title: snapshot.train,
    train: snapshot.train,
    route: snapshot.origin + " → " + snapshot.destination,
    status: statusLabel(snapshot.status, labels) + stale,
    countdownLabel: countdownTargetLabel(snapshot.countdownTarget, labels),
    countdown: countdown.formatCountdown(snapshot.countdownEpochMs, nowEpochMs),
    nextStop: snapshot.nextStop ? labels.next + ": " + snapshot.nextStop : "",
    departure: localTime(snapshot, "Departure", labels),
    arrival: localTime(snapshot, "Arrival", labels),
    delay: delay,
    platform: snapshot.platform ? labels.platform + " " + snapshot.platform : "",
    synced: labels.synced + " " + clock(snapshot.lastSyncEpochMs),
    connection: appState.connection === "connected" ? labels.connected : labels.disconnected
  }
}

// Compact presentation for the capsule screen. Keep times supplied by the phone
// in their journey time zone; do not derive them using the watch's local zone.
function createDisplayModel(appState, nowEpochMs, language) {
  var view = createTextModel(appState, nowEpochMs, language)
  var pt = language === "pt"
  var journey = appState.snapshot
  var status = journey ? journey.status : "waiting"
  var names = pt
    ? { upcoming_departure: "A partir", in_progress: "Em viagem", approaching_destination: "A chegar", completed: "Concluída", cancelled: "Cancelada", stopped: "Parada", waiting: "A aguardar", outdated: "Desatualizado" }
    : { upcoming_departure: "Departing", in_progress: "On board", approaching_destination: "Arriving", completed: "Arrived", cancelled: "Cancelled", stopped: "Stopped", waiting: "Waiting", outdated: "Outdated" }
  view.hasJourney = !!journey
  view.origin = journey ? journey.origin : ""
  view.destination = journey ? journey.destination : ""
  view.phase = names[status] || names.waiting
  view.phaseTone = status === "cancelled" ? "danger" : (status === "upcoming_departure" ? "blue" : "green")
  view.phaseIcon = status === "completed" ? "check" : (status === "cancelled" ? "alert" : (status === "upcoming_departure" ? "clock" : "train"))
  view.targetLabel = journey && journey.countdownTarget === "origin" ? (pt ? "PARTE EM" : "DEPARTS IN")
    : journey && journey.countdownTarget === "next_stop" ? (pt ? "PRÓXIMA EM" : "NEXT STOP IN")
    : journey && journey.countdownTarget === "destination" ? (pt ? "CHEGA EM" : "ARRIVES IN") : ""
  view.nextStation = journey && journey.nextStop ? journey.nextStop : ""
  view.hasDelay = !!journey && typeof journey.delayMinutes === "number"
  view.delayTone = !view.hasDelay || journey.delayMinutes <= 0 ? "green" : (journey.delayMinutes >= 10 ? "danger" : "blue")
  view.delayIcon = view.hasDelay && journey.delayMinutes <= 0 ? "check" : "clock"
  view.delayValue = view.hasDelay && journey.delayMinutes === 0 ? (pt ? "A horas" : "On time") : view.delay
  view.platformValue = journey && journey.platform ? (pt ? "L " : "P ") + journey.platform : ""
  view.departureTime = journey ? journey.estimatedDepartureLocal || journey.scheduledDepartureLocal || "—" : "—"
  view.arrivalTime = journey ? journey.estimatedArrivalLocal || journey.scheduledArrivalLocal || "—" : "—"
  function source(prefix) {
    if (!journey || !(journey["estimated" + prefix + "Local"] || journey["scheduled" + prefix + "Local"])) return "—"
    var estimated = journey["estimated" + prefix + "Local"]
    return estimated && (journey.timeSource === "estimated" || estimated !== journey["scheduled" + prefix + "Local"])
      ? (pt ? "Estimado" : "Estimated") : (pt ? "Horário" : "Scheduled")
  }
  view.departureSource = source("Departure")
  view.arrivalSource = source("Arrival")
  view.syncTime = journey ? clock(journey.lastSyncEpochMs) : ""
  view.linkIcon = appState.connection === "connected" ? "link" : "offline"
  view.linkTone = appState.connection === "connected" ? "green" : "muted"
  view.notice = appState.lastError ? (pt ? "Atualização inválida" : "Invalid update")
    : journey && journey.dataStale ? (pt ? "Dados antigos" : "Outdated data")
    : journey && appState.connection !== "connected" ? (pt ? "Sem ligação" : "Disconnected") : ""
  view.emptyTitle = appState.hasAcceptedMessage ? (pt ? "Sem viagem" : "No journey") : (pt ? "A aguardar" : "Waiting for sync")
  view.emptyHint = pt ? "Inicie uma viagem\nno telemóvel" : "Start a journey\non your phone"
  return view
}

module.exports = {
  createDisplayModel: createDisplayModel,
  strings: strings
}
