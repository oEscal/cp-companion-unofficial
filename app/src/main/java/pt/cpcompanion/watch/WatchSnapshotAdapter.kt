package pt.cpcompanion.watch

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import pt.cpcompanion.model.ExpectedTimeSource
import pt.cpcompanion.model.PassengerPhase
import pt.cpcompanion.model.TrackingSnapshot

/** Converts already-resolved tracking state into the intentionally smaller watch contract. */
class WatchSnapshotAdapter {
    private val localTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun toJourney(snapshot: TrackingSnapshot): WatchJourneySnapshot {
        val beforeDeparture = snapshot.phase in setOf(
            PassengerPhase.PRE_TRIP,
            PassengerPhase.APPROACHING_ORIGIN,
            PassengerPhase.BOARDING_SOON,
        )
        val inJourney = snapshot.phase in setOf(
            PassengerPhase.ON_BOARD,
            PassengerPhase.APPROACHING_DESTINATION,
        )
        val useNextStop = inJourney &&
            snapshot.nextStopName != null &&
            snapshot.nextStopName != snapshot.destinationName &&
            (snapshot.nextStopExpectedEpochMillis != null || snapshot.nextStopScheduledEpochMillis != null)

        val countdownTarget = when {
            beforeDeparture -> WatchProtocol.COUNTDOWN_ORIGIN
            useNextStop -> WatchProtocol.COUNTDOWN_NEXT_STOP
            inJourney -> WatchProtocol.COUNTDOWN_DESTINATION
            else -> null
        }
        val countdownEpochMillis = when {
            beforeDeparture -> snapshot.expectedEventEpochMillis ?: snapshot.scheduledEventEpochMillis
            useNextStop -> snapshot.nextStopExpectedEpochMillis ?: snapshot.nextStopScheduledEpochMillis
            inJourney -> snapshot.expectedEventEpochMillis ?: snapshot.scheduledEventEpochMillis
            else -> null
        }
        val countdownSource = when {
            beforeDeparture -> snapshot.expectedTimeSource
            useNextStop -> snapshot.nextStopTimeSource
            inJourney -> snapshot.expectedTimeSource
            else -> ExpectedTimeSource.UNKNOWN
        }
        val timeZoneId = safeTimeZone(snapshot.eventTimeZoneId)

        return WatchJourneySnapshot(
            id = stableJourneyId(snapshot.ticketId),
            train = snapshot.serviceLabel?.takeIf(String::isNotBlank) ?: snapshot.trainNumber,
            origin = snapshot.originName,
            destination = snapshot.destinationName,
            status = status(snapshot),
            nextStop = snapshot.nextStopName,
            countdownTarget = countdownTarget,
            countdownEpochMs = countdownEpochMillis,
            scheduledDepartureEpochMs = snapshot.scheduledOriginEpochMillis,
            estimatedDepartureEpochMs = snapshot.expectedOriginEpochMillis,
            scheduledArrivalEpochMs = snapshot.scheduledDestinationEpochMillis,
            estimatedArrivalEpochMs = snapshot.expectedDestinationEpochMillis,
            scheduledDepartureLocal = formatLocal(snapshot.scheduledOriginEpochMillis, timeZoneId),
            estimatedDepartureLocal = formatLocal(snapshot.expectedOriginEpochMillis, timeZoneId),
            scheduledArrivalLocal = formatLocal(snapshot.scheduledDestinationEpochMillis, timeZoneId),
            estimatedArrivalLocal = formatLocal(snapshot.expectedDestinationEpochMillis, timeZoneId),
            delayMinutes = snapshot.delayMinutes,
            platform = snapshot.platform?.trim()?.takeIf(String::isNotBlank),
            timeZoneId = timeZoneId,
            timeSource = timeSource(countdownSource),
            lastSyncEpochMs = snapshot.lastSuccessfulFetchEpochMillis,
            dataStale = snapshot.dataStale || snapshot.phase == PassengerPhase.DATA_UNAVAILABLE,
        )
    }

    fun stableJourneyId(ticketId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(ticketId.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "session-${digest.take(12)}"
    }

    private fun status(snapshot: TrackingSnapshot): String = when (snapshot.phase) {
        PassengerPhase.PRE_TRIP,
        PassengerPhase.APPROACHING_ORIGIN,
        PassengerPhase.BOARDING_SOON -> WatchProtocol.STATUS_UPCOMING
        PassengerPhase.ON_BOARD -> WatchProtocol.STATUS_IN_PROGRESS
        PassengerPhase.APPROACHING_DESTINATION -> WatchProtocol.STATUS_APPROACHING
        PassengerPhase.ARRIVED -> WatchProtocol.STATUS_COMPLETED
        PassengerPhase.CANCELLED -> WatchProtocol.STATUS_CANCELLED
        PassengerPhase.DATA_UNAVAILABLE -> WatchProtocol.STATUS_WAITING
        PassengerPhase.STOPPED -> WatchProtocol.STATUS_STOPPED
    }

    private fun timeSource(source: ExpectedTimeSource): String = when (source) {
        ExpectedTimeSource.OBSERVED -> WatchJourneySnapshot.TIME_SOURCE_OBSERVED
        ExpectedTimeSource.CALCULATED_FROM_STOP_DELAY,
        ExpectedTimeSource.CALCULATED_FROM_TRAIN_DELAY -> WatchJourneySnapshot.TIME_SOURCE_ESTIMATED
        ExpectedTimeSource.SCHEDULED_ONLY -> WatchJourneySnapshot.TIME_SOURCE_SCHEDULED
        ExpectedTimeSource.UNKNOWN -> WatchJourneySnapshot.TIME_SOURCE_UNKNOWN
    }

    private fun formatLocal(epochMillis: Long?, timeZoneId: String): String? {
        if (epochMillis == null) return null
        return runCatching {
            Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneId.of(timeZoneId))
                .format(localTimeFormatter)
        }.getOrNull()
    }

    private fun safeTimeZone(value: String): String =
        runCatching { ZoneId.of(value).id }.getOrElse { WatchJourneySnapshot.DEFAULT_TIME_ZONE }
}
