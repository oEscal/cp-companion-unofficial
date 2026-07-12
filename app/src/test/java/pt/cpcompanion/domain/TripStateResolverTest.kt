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
