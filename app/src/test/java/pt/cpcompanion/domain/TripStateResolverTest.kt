package pt.cpcompanion.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test
import pt.cpcompanion.model.PassengerPhase
import pt.cpcompanion.model.StationRef
import pt.cpcompanion.model.Ticket
import pt.cpcompanion.model.TrainStop
import pt.cpcompanion.model.TrainTrip

class TripStateResolverTest {
    private val resolver = TripStateResolver()

    @Test
    fun moreThanOneHourBeforeOrigin_remainsPreTrip() {
        val date = LocalDate.of(2026, 7, 11)
        val now = date.atTime(18, 30).atZone(ZoneId.of("Europe/Lisbon")).toInstant()
        val snapshot = resolver.resolve(ticket(date), trip(date), now)

        assertEquals(PassengerPhase.PRE_TRIP, snapshot.phase)
    }

    @Test
    fun fiveMinutesBeforeOrigin_entersBoardingSoonAndCarriesSeatData() {
        val date = LocalDate.of(2026, 7, 11)
        val now = date.atTime(20, 1).atZone(ZoneId.of("Europe/Lisbon")).toInstant()
        val snapshot = resolver.resolve(ticket(date), trip(date), now)

        assertEquals(PassengerPhase.BOARDING_SOON, snapshot.phase)
        assertEquals("4", snapshot.carriage)
        assertEquals("32A", snapshot.seat)
        assertEquals("7", snapshot.platform)
    }

    @Test
    fun intermediateBoardingStation_countsDownToArrivalButReminderUsesDeparture() {
        val date = LocalDate.of(2026, 7, 11)
        val ticket = ticket(date).copy(
            originStationCode = "94-35170",
            originName = "Bencanta",
        )
        val now = date.atTime(20, 7).atZone(ZoneId.of("Europe/Lisbon")).toInstant()
        val snapshot = resolver.resolve(ticket, trip(date), now)

        assertEquals(PassengerPhase.APPROACHING_ORIGIN, snapshot.phase)
        assertEquals(
            date.atTime(20, 13).atZone(ZoneId.of("Europe/Lisbon")).toInstant().toEpochMilli(),
            snapshot.expectedEventEpochMillis,
        )
        assertEquals(
            date.atTime(20, 10).atZone(ZoneId.of("Europe/Lisbon")).toInstant().toEpochMilli(),
            snapshot.scheduledOriginEpochMillis,
        )
    }

    @Test
    fun afterOriginBeforeDestination_entersOnBoard() {
        val date = LocalDate.of(2026, 7, 11)
        val now = date.atTime(20, 20).atZone(ZoneId.of("Europe/Lisbon")).toInstant()
        val snapshot = resolver.resolve(ticket(date), trip(date), now)

        assertEquals(PassengerPhase.ON_BOARD, snapshot.phase)
        assertEquals("Figueira da Foz", snapshot.destinationName)
        assertEquals(
            date.atTime(21, 8).atZone(ZoneId.of("Europe/Lisbon")).toInstant().toEpochMilli(),
            snapshot.expectedEventEpochMillis,
        )
    }

    @Test
    fun nearDestination_entersApproachingDestination() {
        val date = LocalDate.of(2026, 7, 11)
        val now = date.atTime(20, 58).atZone(ZoneId.of("Europe/Lisbon")).toInstant()
        val snapshot = resolver.resolve(ticket(date), trip(date), now)

        assertEquals(PassengerPhase.APPROACHING_DESTINATION, snapshot.phase)
    }

    @Test
    fun delayWithoutEta_derivesExpectedArrivalAndDoesNotArriveEarly() {
        val date = LocalDate.of(2026, 7, 11)
        val delayedTrip = trip(date).copy(
            overallDelayMinutes = 20,
            stops = trip(date).stops.map { it.copy(expectedArrival = null, expectedDeparture = null, delayMinutes = 20) },
        )
        val now = date.atTime(21, 10).atZone(ZoneId.of("Europe/Lisbon")).toInstant()
        val snapshot = resolver.resolve(ticket(date), delayedTrip, now)

        assertEquals(PassengerPhase.ON_BOARD, snapshot.phase)
        assertEquals(
            date.atTime(21, 25).atZone(ZoneId.of("Europe/Lisbon")).toInstant().toEpochMilli(),
            snapshot.expectedDestinationEpochMillis,
        )
    }

    @Test
    fun temporarilyMissingDelay_reusesRecentPreviousDelay() {
        val date = LocalDate.of(2026, 7, 11)
        val zone = ZoneId.of("Europe/Lisbon")
        val firstNow = date.atTime(20, 0).atZone(zone).toInstant()

        val delayedTrip = trip(date).copy(
            overallDelayMinutes = 12,
            stops = trip(date).stops.map {
                it.copy(
                    expectedArrival = null,
                    expectedDeparture = null,
                    delayMinutes = null,
                )
            },
            fetchedAtEpochMillis = firstNow.toEpochMilli(),
        )

        val previous = resolver.resolve(
            ticket = ticket(date),
            trip = delayedTrip,
            now = firstNow,
        )

        val missingDelayTrip = delayedTrip.copy(
            overallDelayMinutes = null,
            fetchedAtEpochMillis = firstNow.plusSeconds(30).toEpochMilli(),
        )

        val current = resolver.resolve(
            ticket = ticket(date),
            trip = missingDelayTrip,
            now = firstNow.plusSeconds(30),
            previousSnapshot = previous,
        )

        assertEquals(12, current.delayMinutes)

        assertEquals(
            date.atTime(20, 14)
                .atZone(zone)
                .toInstant()
                .toEpochMilli(),
            current.expectedOriginEpochMillis,
        )
    }

    @Test
    fun suppressedIntermediateStop_isDisruptionNotJourneyCancellation() {
        val date = LocalDate.of(2026, 7, 11)
        val stops = trip(date).stops.toMutableList().apply {
            this[1] = this[1].copy(suppressionCode = "SKIP", suppressionDesignation = "Stop suppressed")
        }
        val snapshot = resolver.resolve(
            ticket(date),
            trip(date).copy(stops = stops),
            date.atTime(20, 20).atZone(ZoneId.of("Europe/Lisbon")).toInstant(),
        )

        assertEquals(PassengerPhase.ON_BOARD, snapshot.phase)
    }

    @Test
    fun suppressedDestination_isTerminalForPassengerJourney() {
        val date = LocalDate.of(2026, 7, 11)
        val stops = trip(date).stops.toMutableList().apply {
            this[lastIndex] = this[lastIndex].copy(suppressionCode = "SKIP")
        }
        val snapshot = resolver.resolve(ticket(date), trip(date).copy(stops = stops), Instant.now())

        assertEquals(PassengerPhase.CANCELLED, snapshot.phase)
    }


    @Test
    fun scheduledOnlyArrivalInPast_doesNotCompleteWithoutObservedProgress() {
        val date = LocalDate.of(2026, 7, 11)
        val noLiveTimes = trip(date).copy(
            overallDelayMinutes = null,
            lastReportedStationCode = "94-35170",
            stops = trip(date).stops.map {
                it.copy(expectedArrival = null, expectedDeparture = null, delayMinutes = null)
            },
        )
        val now = date.atTime(21, 12).atZone(ZoneId.of("Europe/Lisbon")).toInstant()

        val snapshot = resolver.resolve(ticket(date), noLiveTimes, now)

        assertEquals(PassengerPhase.ON_BOARD, snapshot.phase)
    }

    @Test
    fun observedArrivalPastGrace_canCompleteWhenFresh() {
        val date = LocalDate.of(2026, 7, 11)
        val now = date.atTime(21, 29).atZone(ZoneId.of("Europe/Lisbon")).toInstant()

        val snapshot = resolver.resolve(ticket(date), trip(date), now)

        assertEquals(PassengerPhase.ARRIVED, snapshot.phase)
    }

    @Test
    fun unavailablePreservesPhaseAndSuccessfulTimestamp() {
        val date = LocalDate.of(2026, 7, 11)
        val now = date.atTime(20, 20).atZone(ZoneId.of("Europe/Lisbon")).toInstant()
        val live = resolver.resolve(ticket(date), trip(date), now)
        val unavailable = resolver.unavailable(live, failures = 2)

        assertEquals(live.phase, unavailable.phase)
        assertEquals(live.lastSuccessfulFetchEpochMillis, unavailable.lastSuccessfulFetchEpochMillis)
        assertEquals(2, unavailable.consecutiveFailures)
    }

    @Test(expected = TripResolutionException::class)
    fun reversedPassengerSegment_isRejected() {
        val date = LocalDate.of(2026, 7, 11)
        resolver.resolve(
            ticket(date).copy(originStationCode = "94-64113", destinationStationCode = "94-36004"),
            trip(date),
            Instant.now(),
        )
    }

    private fun ticket(date: LocalDate) = Ticket(
        id = "ticket",
        trainNumber = "16828",
        serviceDate = date.toString(),
        originStationCode = "94-36004",
        destinationStationCode = "94-64113",
        originName = "Coimbra-B",
        destinationName = "Figueira da Foz",
        carriage = "4",
        seat = "32A",
    )

    private fun trip(date: LocalDate) = TrainTrip(
        trainNumber = "16828",
        serviceDate = date.toString(),
        serviceName = "Urbano",
        status = "IN_TRANSIT",
        lastReportedStationCode = null,
        overallDelayMinutes = 3,
        stops = listOf(
            TrainStop(
                StationRef("94-36004", "Coimbra-B"),
                scheduledDeparture = "20:02",
                expectedDeparture = "20:05",
                platform = "7",
                delayMinutes = 3,
            ),
            TrainStop(
                StationRef("94-35170", "Bencanta"),
                scheduledArrival = "20:10",
                scheduledDeparture = "20:10",
                expectedArrival = "20:13",
                expectedDeparture = "20:13",
                delayMinutes = 3,
            ),
            TrainStop(
                StationRef("94-64113", "Figueira da Foz"),
                scheduledArrival = "21:05",
                expectedArrival = "21:08",
                platform = "2",
                delayMinutes = 3,
            ),
        ),
        fetchedAtEpochMillis = System.currentTimeMillis(),
    )
}
