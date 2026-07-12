package pt.cpcompanion.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import pt.cpcompanion.model.PassengerPhase
import pt.cpcompanion.model.TrackingSnapshot

class StatusChipFormatterTest {
    private val now = 1_000_000L

    @Test
    fun approachingOrigin_compactsPlatformAndMinutes() {
        assertEquals(
            "P2·4m",
            StatusChipFormatter.format(snapshot(PassengerPhase.APPROACHING_ORIGIN, 4, platform = "2"), now),
        )
    }

    @Test
    fun boardingSoon_compactsCarriageAndSeat() {
        assertEquals(
            "C4·32A",
            StatusChipFormatter.format(
                snapshot(PassengerPhase.BOARDING_SOON, 4, carriage = "4", seat = "32A"),
                now,
            ),
        )
    }

    @Test
    fun onBoard_showsDestinationCountdown() {
        assertEquals(
            "42m",
            StatusChipFormatter.format(snapshot(PassengerPhase.ON_BOARD, 42), now),
        )
    }

    @Test
    fun approachingDestination_showsDestinationCountdown() {
        assertEquals(
            "7m",
            StatusChipFormatter.format(snapshot(PassengerPhase.APPROACHING_DESTINATION, 7), now),
        )
    }

    private fun snapshot(
        phase: PassengerPhase,
        minutes: Long,
        platform: String? = null,
        carriage: String? = null,
        seat: String? = null,
    ) = TrackingSnapshot(
        ticketId = "ticket",
        trainNumber = "514",
        serviceDate = "2026-07-11",
        phase = phase,
        originName = "Coimbra-B",
        destinationName = "Lisboa Santa Apolónia",
        expectedEventEpochMillis = now + minutes * 60_000L,
        platform = platform,
        carriage = carriage,
        seat = seat,
    )
}
