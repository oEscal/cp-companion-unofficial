package pt.cpcompanion.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import pt.cpcompanion.model.ExpectedTimeSource
import pt.cpcompanion.model.PassengerPhase
import pt.cpcompanion.model.Ticket
import pt.cpcompanion.model.TrackingFailureCategory
import pt.cpcompanion.model.TrackingSnapshot
import pt.cpcompanion.model.TrainStop
import pt.cpcompanion.model.TrainTrip

class TripResolutionException(message: String) : IllegalArgumentException(message)

class TripStateResolver {
    fun resolve(
        ticket: Ticket,
        trip: TrainTrip,
        now: Instant = Instant.now(),
        previousSnapshot: TrackingSnapshot? = null,
    ): TrackingSnapshot {
        if (ticket.trainNumber != trip.trainNumber) {
            throw TripResolutionException("Ticket and trip train numbers do not match")
        }
        val previousDelayAgeMillis = previousSnapshot
            ?.takeIf {
                it.ticketId == ticket.id &&
                    it.trainNumber == ticket.trainNumber &&
                    it.serviceDate == ticket.serviceDate
            }
            ?.let {
                now.toEpochMilli() - it.lastSuccessfulFetchEpochMillis
            }

        val retainedDelayMinutes = previousSnapshot
            ?.delayMinutes
            ?.takeIf {
                previousDelayAgeMillis != null &&
                    previousDelayAgeMillis in 0L..RETAINED_DELAY_MAX_AGE.toMillis()
            }

        val resolved = resolveStops(
            trip = trip,
            retainedDelayMinutes = retainedDelayMinutes,
        )
        val originIndex = resolved.indexOfFirst { it.source.station.code == ticket.originStationCode }
        val destinationIndex = resolved.indexOfFirst { it.source.station.code == ticket.destinationStationCode }
        if (originIndex < 0 || destinationIndex < 0) {
            throw TripResolutionException("The passenger stations are not present in this train trip")
        }
        if (originIndex >= destinationIndex) {
            throw TripResolutionException("The passenger destination must follow the origin")
        }

        val origin = resolved[originIndex]
        val destination = resolved[destinationIndex]
        val originBoardingScheduled = origin.scheduledDeparture ?: origin.scheduledArrival
        val originBoardingExpected = origin.expectedDeparture ?: origin.expectedArrival
        // At an intermediate station passengers need the arrival countdown, but activation is based on departure.
        val originEventScheduled = origin.scheduledArrival ?: origin.scheduledDeparture
        val originEventExpected = origin.expectedArrival ?: origin.expectedDeparture
        val destinationScheduled = destination.scheduledArrival ?: destination.scheduledDeparture
        val destinationExpected = destination.expectedArrival ?: destination.expectedDeparture

        val lastReportedIndex = trip.lastReportedStationCode?.let { code ->
            resolved.indexOfLast { it.source.station.code == code }.takeIf { it >= 0 }
        }
        val inferredIndex = resolved.indexOfLast { stop ->
            val event = stop.expectedDeparture ?: stop.expectedArrival ?: stop.scheduledDeparture ?: stop.scheduledArrival
            event?.toInstant()?.let { !it.isAfter(now) } == true
        }
        val currentIndex = maxOf(lastReportedIndex ?: -1, inferredIndex).coerceAtLeast(originIndex - 1)
        val durationToOrigin = originEventExpected?.let { Duration.between(now, it.toInstant()) }
        val minutesToDestination = destinationExpected?.let { Duration.between(now, it.toInstant()).toMinutes() }

        val serviceCancelled = trip.status.isTerminalCancellation()
        val originSuppressed = origin.source.isSuppressed
        val destinationSuppressed = destination.source.isSuppressed
        val intermediateSuppressed = resolved.subList(originIndex + 1, destinationIndex)
            .filter { it.source.isSuppressed }
        val cancelled = serviceCancelled || originSuppressed || destinationSuppressed
        val statusArrived = trip.status.isTerminalArrival()
        val destinationPassed = lastReportedIndex != null && lastReportedIndex >= destinationIndex
        val destinationTimeSource = destination.arrivalSource.ifUnknown(destination.departureSource)
        val reliableExpectedArrivalPassed = !trip.dataStale &&
            destinationTimeSource in setOf(
                ExpectedTimeSource.OBSERVED,
                ExpectedTimeSource.CALCULATED_FROM_STOP_DELAY,
                ExpectedTimeSource.CALCULATED_FROM_TRAIN_DELAY,
            ) && destinationExpected?.let {
                now.isAfter(it.toInstant().plus(ARRIVAL_TIME_FALLBACK_GRACE))
            } == true
        val passengerHasDeparted = currentIndex >= originIndex ||
            originBoardingExpected?.let { !now.isBefore(it.toInstant()) } == true

        val phase = when {
            cancelled -> PassengerPhase.CANCELLED
            statusArrived || destinationPassed || reliableExpectedArrivalPassed -> PassengerPhase.ARRIVED
            originEventExpected != null && durationToOrigin != null && !durationToOrigin.isNegative &&
                durationToOrigin <= Duration.ofMinutes(5) -> PassengerPhase.BOARDING_SOON
            originEventExpected != null && durationToOrigin != null && durationToOrigin > Duration.ofMinutes(60) ->
                PassengerPhase.PRE_TRIP
            originEventExpected != null && now.isBefore(originEventExpected.toInstant()) ->
                PassengerPhase.APPROACHING_ORIGIN
            destinationExpected != null && now.isBefore(destinationExpected.toInstant()) &&
                (minutesToDestination ?: Long.MAX_VALUE) <= 10 -> PassengerPhase.APPROACHING_DESTINATION
            passengerHasDeparted -> PassengerPhase.ON_BOARD
            else -> PassengerPhase.APPROACHING_ORIGIN
        }

        val nextStopIndex = (currentIndex + 1).coerceIn(originIndex, destinationIndex)
        val event = when (phase) {
            PassengerPhase.PRE_TRIP,
            PassengerPhase.APPROACHING_ORIGIN,
            PassengerPhase.BOARDING_SOON -> EventTime(originEventScheduled, originEventExpected, origin.arrivalSource.ifUnknown(origin.departureSource))
            PassengerPhase.ON_BOARD,
            PassengerPhase.APPROACHING_DESTINATION -> EventTime(destinationScheduled, destinationExpected, destination.arrivalSource.ifUnknown(destination.departureSource))
            else -> EventTime(null, null, ExpectedTimeSource.UNKNOWN)
        }

        val delay = when (phase) {
            PassengerPhase.PRE_TRIP,
            PassengerPhase.APPROACHING_ORIGIN,
            PassengerPhase.BOARDING_SOON ->
                origin.source.delayMinutes
                    ?: trip.overallDelayMinutes
                    ?: retainedDelayMinutes

            else ->
                destination.source.delayMinutes
                    ?: trip.overallDelayMinutes
                    ?: retainedDelayMinutes
        }
        // The progress line represents the passenger's complete route, not a rolling one-hour
        // time window. This keeps every calling point visible before and during the journey.
        val originInstant = (originBoardingExpected ?: originBoardingScheduled)?.toInstant()
        val destinationInstant = (destinationExpected ?: destinationScheduled)?.toInstant()
        val progressStart = originInstant
        val progressEnd = destinationInstant
        val markerStops = resolved.subList(originIndex + 1, destinationIndex)

        val disruptionMessage = when {
            serviceCancelled -> trip.messages.firstOrNull() ?: "The train service is cancelled"
            originSuppressed -> "The passenger origin stop is suppressed"
            destinationSuppressed -> "The passenger destination stop is suppressed"
            intermediateSuppressed.isNotEmpty() -> {
                val names = intermediateSuppressed.joinToString { it.source.station.name }
                "The train will skip: $names"
            }
            trip.hasDisruptions -> trip.messages.firstOrNull() ?: "CP reports a service disruption"
            else -> trip.messages.firstOrNull()
        }
        val staleMessage = trip.cooldownUntilEpochMillis?.let {
            "Rate limited; showing the last live data and retrying at ${formatTime(it)}"
        }

        return TrackingSnapshot(
            ticketId = ticket.id,
            trainNumber = ticket.trainNumber,
            serviceLabel = trip.serviceName ?: ticket.serviceLabel,
            serviceDate = ticket.serviceDate,
            phase = phase,
            originName = ticket.originName ?: origin.source.station.name,
            destinationName = ticket.destinationName ?: destination.source.station.name,
            nextStopName = trip.stops.getOrNull(nextStopIndex)?.station?.name,
            lastReportedStationName = lastReportedIndex?.let { trip.stops.getOrNull(it)?.station?.name },
            scheduledEventEpochMillis = event.scheduled?.toInstant()?.toEpochMilli(),
            expectedEventEpochMillis = event.expected?.toInstant()?.toEpochMilli(),
            scheduledOriginEpochMillis = originBoardingScheduled?.toInstant()?.toEpochMilli(),
            expectedOriginEpochMillis = originBoardingExpected?.toInstant()?.toEpochMilli(),
            expectedOriginArrivalEpochMillis = originEventExpected?.toInstant()?.toEpochMilli(),
            scheduledDestinationEpochMillis = destinationScheduled?.toInstant()?.toEpochMilli(),
            expectedDestinationEpochMillis = destinationExpected?.toInstant()?.toEpochMilli(),
            delayMinutes = delay,
            platform = when (phase) {
                PassengerPhase.PRE_TRIP,
                PassengerPhase.APPROACHING_ORIGIN,
                PassengerPhase.BOARDING_SOON -> origin.source.platform
                else -> destination.source.platform
            },
            carriage = ticket.carriage,
            seat = ticket.seat,
            progress = timeProgress(progressStart, progressEnd, now),
            progressMarkers = markerPositions(markerStops, progressStart, progressEnd),
            message = disruptionMessage ?: staleMessage,
            lastSuccessfulFetchEpochMillis = trip.fetchedAtEpochMillis,
            lastAttemptEpochMillis = if (trip.dataStale) System.currentTimeMillis() else trip.fetchedAtEpochMillis,
            updatedAtEpochMillis = trip.fetchedAtEpochMillis,
            consecutiveFailures = 0,
            dataStale = trip.dataStale,
            expectedTimeSource = event.source,
            failureCategory = if (trip.dataStale) TrackingFailureCategory.RATE_LIMITED else TrackingFailureCategory.NONE,
            retryAtEpochMillis = trip.cooldownUntilEpochMillis,
            currentStopIndex = currentIndex.takeIf { it >= 0 },
            passengerOriginIndex = originIndex,
            passengerDestinationIndex = destinationIndex,
            eventTimeZoneId = when (phase) {
                PassengerPhase.ON_BOARD,
                PassengerPhase.APPROACHING_DESTINATION,
                PassengerPhase.ARRIVED -> destinationExpected?.zone?.id
                    ?: destinationScheduled?.zone?.id
                    ?: StationTimeZoneResolver.resolve(destination.source.station.code).id
                else -> originEventExpected?.zone?.id
                    ?: originEventScheduled?.zone?.id
                    ?: StationTimeZoneResolver.resolve(origin.source.station.code).id
            },
            serviceHeartbeatEpochMillis = System.currentTimeMillis(),
        )
    }

    fun unavailable(
        previous: TrackingSnapshot,
        failures: Int,
        category: TrackingFailureCategory = TrackingFailureCategory.UNKNOWN_TRANSIENT,
        message: String? = null,
        retryAtEpochMillis: Long? = null,
    ): TrackingSnapshot = previous.copy(
        // Preserve the passenger phase so recovery does not re-fire phase alerts.
        consecutiveFailures = failures,
        dataStale = true,
        lastAttemptEpochMillis = System.currentTimeMillis(),
        failureCategory = category,
        message = message ?: previous.message,
        retryAtEpochMillis = retryAtEpochMillis,
        serviceHeartbeatEpochMillis = System.currentTimeMillis(),
    )

    private fun timeProgress(start: Instant?, end: Instant?, now: Instant): Int {
        if (start == null || end == null || !end.isAfter(start)) return 0
        val duration = Duration.between(start, end).toMillis()
        val elapsed = Duration.between(start, now).toMillis().coerceIn(0L, duration)
        return (elapsed * 1000L / duration).toInt()
    }

    private fun markerPositions(stops: List<ResolvedStop>, start: Instant?, end: Instant?): List<Int> {
        if (start == null || end == null || !end.isAfter(start) || stops.isEmpty()) return emptyList()
        val duration = Duration.between(start, end).toMillis()
        var previous = 0
        return stops.mapIndexed { index, stop ->
            val event = (stop.expectedArrival ?: stop.expectedDeparture
                ?: stop.scheduledArrival ?: stop.scheduledDeparture)?.toInstant()
            val timeBased = event
                ?.takeIf { it.isAfter(start) && it.isBefore(end) }
                ?.let { (Duration.between(start, it).toMillis() * 1000L / duration).toInt() }
            val proportional = ((index + 1L) * 1000L / (stops.size + 1L)).toInt()
            val remaining = stops.size - index - 1
            val position = (timeBased ?: proportional)
                .coerceAtLeast(previous + 1)
                .coerceAtMost(999 - remaining)
            previous = position
            position
        }
    }

    private fun resolveStops(
        trip: TrainTrip,
        retainedDelayMinutes: Int?,
    ): List<ResolvedStop> {
        val serviceDate = LocalDate.parse(trip.serviceDate)
        var previous: Instant? = null
        return trip.stops.map { stop ->
            val zone = StationTimeZoneResolver.resolve(stop.station.code)
            val scheduledArrival = resolve(stop.scheduledArrival, serviceDate, zone, previous)
            previous = maxInstant(previous, scheduledArrival?.toInstant())
            val scheduledDeparture = resolve(stop.scheduledDeparture, serviceDate, zone, previous)
            previous = maxInstant(previous, scheduledDeparture?.toInstant())
            val arrival = expected(
                stop.expectedArrival,
                scheduledArrival,
                stop.delayMinutes,
                trip.overallDelayMinutes ?: retainedDelayMinutes,
                serviceDate,
                zone,
            )
            val departure = expected(
                stop.expectedDeparture,
                scheduledDeparture,
                stop.delayMinutes,
                trip.overallDelayMinutes ?: retainedDelayMinutes,
                serviceDate,
                zone,
            )
            ResolvedStop(
                source = stop,
                scheduledArrival = scheduledArrival,
                scheduledDeparture = scheduledDeparture,
                expectedArrival = arrival.time,
                expectedDeparture = departure.time,
                arrivalSource = arrival.source,
                departureSource = departure.source,
            )
        }
    }

    private fun expected(
        text: String?,
        scheduled: ZonedDateTime?,
        stopDelay: Int?,
        overallDelay: Int?,
        date: LocalDate,
        zone: ZoneId,
    ): ResolvedExpected {
        /*
        * Priority 1: use the ETA/ETD explicitly supplied by CP.
        */
        alignExpected(text, scheduled, date, zone)?.let { observed ->
            return ResolvedExpected(
                observed,
                ExpectedTimeSource.OBSERVED,
            )
        }

        if (scheduled == null) {
            return ResolvedExpected(
                null,
                ExpectedTimeSource.UNKNOWN,
            )
        }

        /*
        * Priority 2: use a meaningful stop-specific delay.
        *
        * CP can occasionally return zero for a stop while the train still has
        * a positive overall delay. In that case the overall delay is a better
        * fallback than reverting to the scheduled time.
        */
        if (
            stopDelay != null &&
            (stopDelay != 0 || overallDelay == null)
        ) {
            return ResolvedExpected(
                scheduled.plusMinutes(stopDelay.toLong()),
                ExpectedTimeSource.CALCULATED_FROM_STOP_DELAY,
            )
        }

        /*
        * Priority 3: calculate ETA/ETD from the train's overall delay.
        */
        if (overallDelay != null) {
            return ResolvedExpected(
                scheduled.plusMinutes(overallDelay.toLong()),
                ExpectedTimeSource.CALCULATED_FROM_TRAIN_DELAY,
            )
        }

        /*
        * Priority 4: no live delay information is available.
        */
        return ResolvedExpected(
            scheduled,
            ExpectedTimeSource.SCHEDULED_ONLY,
        )
    }

    private fun resolve(text: String?, date: LocalDate, zone: ZoneId, previous: Instant?): ZonedDateTime? {
        val time = parseTime(text) ?: return null
        var candidate = LocalDateTime.of(date, time).atZone(zone)
        while (previous != null && candidate.toInstant().isBefore(previous.minus(Duration.ofHours(2)))) {
            candidate = candidate.plusDays(1)
        }
        return candidate
    }

    private fun alignExpected(text: String?, scheduled: ZonedDateTime?, date: LocalDate, zone: ZoneId): ZonedDateTime? {
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

    private fun maxInstant(a: Instant?, b: Instant?): Instant? = when {
        a == null -> b
        b == null -> a
        a.isAfter(b) -> a
        else -> b
    }

    private fun String?.isTerminalCancellation(): Boolean {
        val value = this?.lowercase().orEmpty()
        return listOf("cancel", "cancelado", "cancelada", "suprimido", "suppressed").any(value::contains)
    }

    private fun String?.isTerminalArrival(): Boolean {
        val value = this?.lowercase().orEmpty()
        return listOf("arrived", "completed", "terminado", "finalizado").any(value::contains)
    }

    private fun formatTime(epochMillis: Long): String = DateTimeFormatter.ofPattern("HH:mm")
        .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

    private fun ExpectedTimeSource.ifUnknown(other: ExpectedTimeSource): ExpectedTimeSource =
        if (this == ExpectedTimeSource.UNKNOWN) other else this

    private companion object {
        val ARRIVAL_TIME_FALLBACK_GRACE: Duration = Duration.ofMinutes(20)
        val RETAINED_DELAY_MAX_AGE: Duration = Duration.ofMinutes(5)
    }

    private data class EventTime(
        val scheduled: ZonedDateTime?,
        val expected: ZonedDateTime?,
        val source: ExpectedTimeSource,
    )

    private data class ResolvedExpected(
        val time: ZonedDateTime?,
        val source: ExpectedTimeSource,
    )

    private data class ResolvedStop(
        val source: TrainStop,
        val scheduledArrival: ZonedDateTime?,
        val scheduledDeparture: ZonedDateTime?,
        val expectedArrival: ZonedDateTime?,
        val expectedDeparture: ZonedDateTime?,
        val arrivalSource: ExpectedTimeSource,
        val departureSource: ExpectedTimeSource,
    )
}
