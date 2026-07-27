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
    fun boardingSoon_doesNotDropSeatWhenCarriageUsesTwoDigits() {
        assertEquals(
            "10·32A",
            StatusChipFormatter.format(
                snapshot(PassengerPhase.BOARDING_SOON, 4, carriage = "10", seat = "32A"),
                now,
            ),
        )
    }

    @Test
    fun boardingSoon_doesNotDropSeatWhenBothValuesNeedTheCompactFallback() {
        assertEquals(
            "24·107",
            StatusChipFormatter.format(
                snapshot(PassengerPhase.BOARDING_SOON, 4, carriage = "24", seat = "107"),
                now,
            ),
        )
    }

    @Test
    fun onBoardBeforeEffectiveDeparture_keepsCarriageAndSeatVisible() {
        val effectiveDeparture = now + 2 * 60_000L

        val snapshot = snapshot(
            phase = PassengerPhase.ON_BOARD,
            minutes = 42,
            carriage = "4",
            seat = "32A",
            expectedOriginEpochMillis = effectiveDeparture,
        )

        assertEquals(
            "C4·32A",
            StatusChipFormatter.format(snapshot, now),
        )
    }

    @Test
    fun onBoardWithinFiveMinutesAfterDeparture_keepsCarriageAndSeatVisible() {
        val effectiveDeparture = now - 2 * 60_000L

        val snapshot = snapshot(
            phase = PassengerPhase.ON_BOARD,
            minutes = 42,
            carriage = "4",
            seat = "32A",
            expectedOriginEpochMillis = effectiveDeparture,
        )

        assertEquals(
            "C4·32A",
            StatusChipFormatter.format(snapshot, now),
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
        expectedOriginEpochMillis: Long? = null,
    ) = TrackingSnapshot(
        ticketId = "ticket",
        trainNumber = "514",
        serviceDate = "2026-07-11",
        phase = phase,
        originName = "Coimbra-B",
        destinationName = "Lisboa Santa Apolónia",
        expectedEventEpochMillis = now + minutes * 60_000L,
        expectedOriginEpochMillis = expectedOriginEpochMillis,
        platform = platform,
        carriage = carriage,
        seat = seat,
    )
}
