package pt.cpcompanion.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import pt.cpcompanion.model.PassengerPhase
import pt.cpcompanion.model.Ticket
import pt.cpcompanion.model.TrackingSnapshot
import pt.cpcompanion.model.TrainStop
import pt.cpcompanion.model.TrainTrip

class TripResolutionException(message: String) : IllegalArgumentException(message)

class TripStateResolver {
    fun resolve(ticket: Ticket, trip: TrainTrip, now: Instant = Instant.now()): TrackingSnapshot {
        val originIndex = trip.stops.indexOfFirst { it.station.code == ticket.originStationCode }
        val destinationIndex = trip.stops.indexOfFirst { it.station.code == ticket.destinationStationCode }
        if (originIndex < 0) throw TripResolutionException("Ticket origin is not present in this train trip")
        if (destinationIndex < 0) throw TripResolutionException("Ticket destination is not present in this train trip")
        if (originIndex >= destinationIndex) throw TripResolutionException("Ticket destination must be after its origin")

        val resolved = resolveStops(trip)
        val origin = resolved[originIndex]
        val destination = resolved[destinationIndex]
        // Before boarding, users care about when the train reaches their station. Prefer
        // arrival; at a service origin, where arrival is absent, fall back to departure.
        val originEventExpected = origin.expectedArrival ?: origin.expectedDeparture
            ?: origin.scheduledArrival ?: origin.scheduledDeparture
        val originEventScheduled = origin.scheduledArrival ?: origin.scheduledDeparture
        // Ticket reminders remain anchored to the boarding departure when it exists.
        val originBoardingExpected = origin.expectedDeparture ?: origin.expectedArrival
            ?: origin.scheduledDeparture ?: origin.scheduledArrival
        val originBoardingScheduled = origin.scheduledDeparture ?: origin.scheduledArrival
        val destinationExpected = destination.expectedArrival ?: destination.expectedDeparture
            ?: destination.scheduledArrival ?: destination.scheduledDeparture
        val destinationScheduled = destination.scheduledArrival ?: destination.scheduledDeparture

        val lastReportedIndex = trip.lastReportedStationCode?.let { code ->
            trip.stops.indexOfFirst { it.station.code == code }.takeIf { it >= 0 }
        }
        val inferredIndex = resolved.indexOfLast { stop ->
            val event = stop.expectedDeparture ?: stop.expectedArrival ?: stop.scheduledDeparture ?: stop.scheduledArrival
            event?.toInstant()?.let { !it.isAfter(now) } == true
        }
        val currentIndex = maxOf(lastReportedIndex ?: -1, inferredIndex).coerceAtLeast(originIndex - 1)
        val totalLegs = (destinationIndex - originIndex).coerceAtLeast(1)
        val passedLegs = (currentIndex - originIndex).coerceIn(0, totalLegs)
        val progress = (passedLegs * 1000 / totalLegs).coerceIn(0, 1000)

        val durationToOrigin = originEventExpected?.let { Duration.between(now, it.toInstant()) }
        val minutesToDestination = destinationExpected?.let { Duration.between(now, it.toInstant()).toMinutes() }
        val cancelled = trip.stops.subList(originIndex, destinationIndex + 1).any { it.isCancelled }

        val phase = when {
            cancelled -> PassengerPhase.CANCELLED
            destinationExpected != null && now.isAfter(destinationExpected.toInstant().plusSeconds(120)) -> PassengerPhase.ARRIVED
            originEventExpected != null && durationToOrigin != null && !durationToOrigin.isNegative &&
                durationToOrigin <= Duration.ofMinutes(5) ->
                PassengerPhase.BOARDING_SOON
            originEventExpected != null && durationToOrigin != null && durationToOrigin > Duration.ofMinutes(60) ->
                PassengerPhase.PRE_TRIP
            originEventExpected != null && now.isBefore(originEventExpected.toInstant()) -> PassengerPhase.APPROACHING_ORIGIN
            destinationExpected != null && now.isBefore(destinationExpected.toInstant()) &&
                (minutesToDestination ?: Long.MAX_VALUE) <= 10 -> PassengerPhase.APPROACHING_DESTINATION
            destinationExpected != null && now.isBefore(destinationExpected.toInstant().plusSeconds(120)) -> PassengerPhase.ON_BOARD
            else -> PassengerPhase.ARRIVED
        }

        val nextStopIndex = (currentIndex + 1).coerceIn(originIndex, destinationIndex)
        val eventExpected = when (phase) {
            PassengerPhase.PRE_TRIP,
            PassengerPhase.APPROACHING_ORIGIN,
            PassengerPhase.BOARDING_SOON -> originEventExpected
            PassengerPhase.ON_BOARD,
            PassengerPhase.APPROACHING_DESTINATION -> destinationExpected
            else -> null
        }
        val eventScheduled = when (phase) {
            PassengerPhase.PRE_TRIP,
            PassengerPhase.APPROACHING_ORIGIN,
            PassengerPhase.BOARDING_SOON -> originEventScheduled
            PassengerPhase.ON_BOARD,
            PassengerPhase.APPROACHING_DESTINATION -> destinationScheduled
            else -> null
        }

        val delay = when (phase) {
            PassengerPhase.PRE_TRIP,
            PassengerPhase.APPROACHING_ORIGIN,
            PassengerPhase.BOARDING_SOON -> trip.stops[originIndex].delayMinutes ?: trip.overallDelayMinutes
            else -> trip.stops[destinationIndex].delayMinutes ?: trip.overallDelayMinutes
        }

        return TrackingSnapshot(
            ticketId = ticket.id,
            trainNumber = ticket.trainNumber,
            serviceDate = ticket.serviceDate,
            phase = phase,
            originName = ticket.originName ?: trip.stops[originIndex].station.name,
            destinationName = ticket.destinationName ?: trip.stops[destinationIndex].station.name,
            nextStopName = trip.stops.getOrNull(nextStopIndex)?.station?.name,
            lastReportedStationName = lastReportedIndex?.let { trip.stops.getOrNull(it)?.station?.name },
            scheduledEventEpochMillis = eventScheduled?.toInstant()?.toEpochMilli(),
            expectedEventEpochMillis = eventExpected?.toInstant()?.toEpochMilli(),
            scheduledOriginEpochMillis = originBoardingScheduled?.toInstant()?.toEpochMilli(),
            expectedOriginEpochMillis = originBoardingExpected?.toInstant()?.toEpochMilli(),
            scheduledDestinationEpochMillis = destinationScheduled?.toInstant()?.toEpochMilli(),
            expectedDestinationEpochMillis = destinationExpected?.toInstant()?.toEpochMilli(),
            delayMinutes = delay,
            platform = when (phase) {
                PassengerPhase.PRE_TRIP,
                PassengerPhase.APPROACHING_ORIGIN,
                PassengerPhase.BOARDING_SOON -> trip.stops[originIndex].platform
                else -> trip.stops[destinationIndex].platform
            },
            carriage = ticket.carriage,
            seat = ticket.seat,
            progress = progress,
            message = trip.messages.firstOrNull(),
            updatedAtEpochMillis = trip.fetchedAtEpochMillis,
        )
    }

    fun unavailable(previous: TrackingSnapshot, failures: Int): TrackingSnapshot = previous.copy(
        phase = PassengerPhase.DATA_UNAVAILABLE,
        consecutiveFailures = failures,
        updatedAtEpochMillis = System.currentTimeMillis(),
    )

    private fun resolveStops(trip: TrainTrip): List<ResolvedStop> {
        val serviceDate = LocalDate.parse(trip.serviceDate)
        var previous: Instant? = null
        return trip.stops.map { stop ->
            val zone = zoneFor(stop.station.code)
            val scheduledArrival = resolve(stop.scheduledArrival, serviceDate, zone, previous)
            previous = maxInstant(previous, scheduledArrival?.toInstant())
            val scheduledDeparture = resolve(stop.scheduledDeparture, serviceDate, zone, previous)
            previous = maxInstant(previous, scheduledDeparture?.toInstant())
            val expectedArrival = alignExpected(stop.expectedArrival, scheduledArrival, serviceDate, zone)
            val expectedDeparture = alignExpected(stop.expectedDeparture, scheduledDeparture, serviceDate, zone)
            ResolvedStop(
                source = stop,
                scheduledArrival = scheduledArrival,
                scheduledDeparture = scheduledDeparture,
                expectedArrival = expectedArrival,
                expectedDeparture = expectedDeparture,
            )
        }
    }

    private fun resolve(
        text: String?,
        date: LocalDate,
        zone: ZoneId,
        previous: Instant?,
    ): ZonedDateTime? {
        val time = parseTime(text) ?: return null
        var candidate = LocalDateTime.of(date, time).atZone(zone)
        while (previous != null && candidate.toInstant().isBefore(previous.minus(Duration.ofHours(2)))) {
            candidate = candidate.plusDays(1)
        }
        return candidate
    }

    private fun alignExpected(
        text: String?,
        scheduled: ZonedDateTime?,
        date: LocalDate,
        zone: ZoneId,
    ): ZonedDateTime? {
        val time = parseTime(text) ?: return null
        var candidate = LocalDateTime.of(scheduled?.toLocalDate() ?: date, time).atZone(zone)
        if (scheduled != null) {
            while (candidate.toInstant().isBefore(scheduled.toInstant().minus(Duration.ofHours(12)))) candidate = candidate.plusDays(1)
            while (candidate.toInstant().isAfter(scheduled.toInstant().plus(Duration.ofHours(12)))) candidate = candidate.minusDays(1)
        }
        return candidate
    }

    private fun parseTime(value: String?): LocalTime? = value
        ?.trim()
        ?.takeIf { it.matches(Regex("\\d{1,2}:\\d{2}")) }
        ?.let { runCatching { LocalTime.parse(it.padStart(5, '0')) }.getOrNull() }

    private fun zoneFor(stationCode: String): ZoneId = when {
        stationCode.startsWith("71-") -> ZoneId.of("Europe/Madrid")
        else -> ZoneId.of("Europe/Lisbon")
    }

    private fun maxInstant(a: Instant?, b: Instant?): Instant? = when {
        a == null -> b
        b == null -> a
        a.isAfter(b) -> a
        else -> b
    }

    private data class ResolvedStop(
        val source: TrainStop,
        val scheduledArrival: ZonedDateTime?,
        val scheduledDeparture: ZonedDateTime?,
        val expectedArrival: ZonedDateTime?,
        val expectedDeparture: ZonedDateTime?,
    )
}
