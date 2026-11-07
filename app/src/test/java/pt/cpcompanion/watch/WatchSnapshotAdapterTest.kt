package pt.cpcompanion.watch

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.cpcompanion.model.ExpectedTimeSource
import pt.cpcompanion.model.PassengerPhase
import pt.cpcompanion.model.TrackingSnapshot

class WatchSnapshotAdapterTest {
    private val adapter = WatchSnapshotAdapter()

    @Test
    fun defaultSettingsAreDisabledAndRequireARealMacAddress() {
        assertFalse(SmartBandSettings().enabled)
        assertFalse(SmartBandSettings.isValidDeviceAddress(null))
        assertFalse(SmartBandSettings.isValidDeviceAddress("AA:BB:CC:DD:EE"))
        assertTrue(SmartBandSettings.isValidDeviceAddress("aa:bb:cc:dd:ee:ff"))
        assertTrue(SmartBandSettings(enabled = true, deviceAddress = " aa:bb:cc:dd:ee:ff ").normalized().canSend)
    }

    @Test
    fun upcomingSnapshotUsesTheExistingOriginEventAndLabelsItsTimes() {
        val departure = epoch("2026-07-11T20:10:00")
        val arrivalAtOrigin = epoch("2026-07-11T20:13:00")
        val snapshot = snapshot(
            phase = PassengerPhase.APPROACHING_ORIGIN,
            scheduledEventEpochMillis = arrivalAtOrigin - 60_000L,
            expectedEventEpochMillis = arrivalAtOrigin,
            scheduledOriginEpochMillis = departure,
            expectedOriginEpochMillis = departure + 180_000L,
        )

        val journey = adapter.toJourney(snapshot)

        assertEquals(WatchProtocol.STATUS_UPCOMING, journey.status)
        assertEquals(WatchProtocol.COUNTDOWN_ORIGIN, journey.countdownTarget)
        assertEquals(arrivalAtOrigin, journey.countdownEpochMs)
        assertEquals("20:10", journey.scheduledDepartureLocal)
        assertEquals("20:13", journey.estimatedDepartureLocal)
        assertEquals(WatchJourneySnapshot.TIME_SOURCE_OBSERVED, journey.timeSource)
    }

    @Test
    fun inProgressSnapshotUsesAValidatedNextStopWhenItsTimeIsAvailable() {
        val nextStop = epoch("2026-07-11T20:45:00")
        val destination = epoch("2026-07-11T21:08:00")
        val journey = adapter.toJourney(
            snapshot(
                phase = PassengerPhase.ON_BOARD,
                nextStopName = "Pombal",
                nextStopExpectedEpochMillis = nextStop,
                nextStopTimeSource = ExpectedTimeSource.OBSERVED,
                scheduledEventEpochMillis = destination - 60_000L,
                expectedEventEpochMillis = destination,
            ),
        )

        assertEquals(WatchProtocol.STATUS_IN_PROGRESS, journey.status)
        assertEquals(WatchProtocol.COUNTDOWN_NEXT_STOP, journey.countdownTarget)
        assertEquals(nextStop, journey.countdownEpochMs)
        assertEquals("Pombal", journey.nextStop)
    }

    @Test
    fun serializedMessageContainsOnlyTheWatchContract() {
        val journey = adapter.toJourney(snapshot(PassengerPhase.PRE_TRIP))
        val encoded = WatchMessageCodec.encode(
            WatchMessage(
                version = WatchProtocol.VERSION,
                type = WatchProtocol.JOURNEY_SNAPSHOT,
                sequence = 12L,
                sentAtEpochMs = 13L,
                journey = journey,
            ),
        )

        assertTrue(encoded.contains("\"version\":1"))
        assertTrue(encoded.contains("\"type\":\"journey_snapshot\""))
        assertTrue(encoded.contains("\"countdownEpochMs\""))
        assertFalse(encoded.contains("ticket-secret-reference"))
    }

    @Test
    fun localTimeLabelsUseTheJourneyZoneAcrossTheLisbonDstTransition() {
        val journey = adapter.toJourney(
            snapshot(
                phase = PassengerPhase.PRE_TRIP,
                scheduledOriginEpochMillis = Instant.parse("2026-10-25T00:30:00Z").toEpochMilli(),
                expectedOriginEpochMillis = Instant.parse("2026-10-25T02:30:00Z").toEpochMilli(),
            ),
        )

        assertEquals("01:30", journey.scheduledDepartureLocal)
        assertEquals("02:30", journey.estimatedDepartureLocal)
        assertEquals("Europe/Lisbon", journey.timeZoneId)
    }

    private fun snapshot(
        phase: PassengerPhase,
        nextStopName: String? = "Lisboa Oriente",
        scheduledEventEpochMillis: Long? = null,
        expectedEventEpochMillis: Long? = null,
        scheduledOriginEpochMillis: Long? = epoch("2026-07-11T20:10:00"),
        expectedOriginEpochMillis: Long? = epoch("2026-07-11T20:10:00"),
        nextStopScheduledEpochMillis: Long? = null,
        nextStopExpectedEpochMillis: Long? = null,
        nextStopTimeSource: ExpectedTimeSource = ExpectedTimeSource.UNKNOWN,
    ) = TrackingSnapshot(
        ticketId = "ticket-secret-reference",
        trainNumber = "IC 123",
        serviceLabel = "Intercidades",
        serviceDate = "2026-07-11",
        phase = phase,
        originName = "Coimbra-B",
        destinationName = "Lisboa Oriente",
        nextStopName = nextStopName,
        scheduledEventEpochMillis = scheduledEventEpochMillis,
        expectedEventEpochMillis = expectedEventEpochMillis,
        scheduledOriginEpochMillis = scheduledOriginEpochMillis,
        expectedOriginEpochMillis = expectedOriginEpochMillis,
        scheduledDestinationEpochMillis = epoch("2026-07-11T21:08:00"),
        expectedDestinationEpochMillis = epoch("2026-07-11T21:08:00"),
        nextStopScheduledEpochMillis = nextStopScheduledEpochMillis,
        nextStopExpectedEpochMillis = nextStopExpectedEpochMillis,
        nextStopTimeSource = nextStopTimeSource,
        expectedTimeSource = ExpectedTimeSource.OBSERVED,
        lastSuccessfulFetchEpochMillis = epoch("2026-07-11T19:00:00"),
        eventTimeZoneId = "Europe/Lisbon",
    )

    private companion object {
        fun epoch(value: String): Long =
            LocalDateTime.parse(value).atZone(ZoneId.of("Europe/Lisbon")).toInstant().toEpochMilli()
    }
}
