package pt.cpcompanion.model

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketTimeTest {
    @Test
    fun futureTicketShowsEnabledAutomationSwitchByDefault() {
        val now = 1_700_000_000_000L
        val ticket = ticket(
            serviceDate = "2030-05-20",
            departure = now + 60_000L,
            arrival = now + 3_600_000L,
        )

        assertTrue(ticket.automaticTrackingEnabled)
        assertTrue(ticket.shouldShowAutomationSwitch(now, LocalDate.of(2029, 1, 1)))
        assertFalse(ticket.isPastPassengerSegment(now, LocalDate.of(2029, 1, 1)))
    }

    @Test
    fun disabledFutureTicketRemainsFutureAndKeepsSwitchAvailable() {
        val now = 1_700_000_000_000L
        val ticket = ticket(
            serviceDate = "2030-05-20",
            departure = now + 60_000L,
            arrival = now + 3_600_000L,
        ).copy(
            automaticTrackingEnabled = false,
            automationState = TicketAutomationState.DISABLED,
        )

        assertTrue(ticket.shouldShowAutomationSwitch(now, LocalDate.of(2029, 1, 1)))
        assertFalse(ticket.isPastPassengerSegment(now, LocalDate.of(2029, 1, 1)))
    }

    @Test
    fun departedTicketDoesNotShowSwitchEvenWhenJourneyHasNotFinished() {
        val now = 1_700_000_000_000L
        val ticket = ticket(
            serviceDate = "2023-11-14",
            departure = now - 60_000L,
            arrival = now + 3_600_000L,
        )

        assertFalse(ticket.shouldShowAutomationSwitch(now, LocalDate.of(2023, 11, 14)))
        assertFalse(ticket.isPastPassengerSegment(now, LocalDate.of(2023, 11, 14)))
    }

    @Test
    fun completedTicketIsPastAndHasNoSwitch() {
        val now = 1_700_000_000_000L
        val ticket = ticket(
            serviceDate = "2023-11-14",
            departure = now - 3_600_000L,
            arrival = now - 60_000L,
        )

        assertTrue(ticket.isPastPassengerSegment(now, LocalDate.of(2023, 11, 14)))
        assertFalse(ticket.shouldShowAutomationSwitch(now, LocalDate.of(2023, 11, 14)))
    }

    @Test
    fun recentScheduledArrivalIsNotProofOfCompletionForAutomation() {
        val now = 1_700_000_000_000L
        val ticket = ticket(
            serviceDate = "2023-11-14",
            departure = now - 3_600_000L,
            arrival = now - 60_000L,
        )

        assertTrue(ticket.isPastPassengerSegment(now, LocalDate.of(2023, 11, 14)))
        assertFalse(ticket.isClearlyPastForAutomation(now, LocalDate.of(2023, 11, 14)))
    }

    @Test
    fun passedScheduledArrivalPreventsNewAutomaticSession() {
        val now = 1_700_000_000_000L
        val ticket = ticket(
            serviceDate = "2023-11-14",
            departure = now - 3_600_000L,
            arrival = now - 60_000L,
        )

        assertTrue(ticket.hasScheduledPassengerSegmentEnded(now))
    }

    @Test
    fun oldScheduledArrivalDisablesHistoricalAutomation() {
        val now = 1_700_000_000_000L
        val ticket = ticket(
            serviceDate = "2023-11-14",
            departure = now - 8 * 3_600_000L,
            arrival = now - 7 * 3_600_000L,
        )

        assertTrue(ticket.isClearlyPastForAutomation(now, LocalDate.of(2023, 11, 14)))
    }

    private fun ticket(serviceDate: String, departure: Long, arrival: Long) = Ticket(
        id = "ticket",
        trainNumber = "123",
        serviceDate = serviceDate,
        originStationCode = "94-10000",
        destinationStationCode = "94-20000",
        scheduledDepartureEpochMillis = departure,
        scheduledArrivalEpochMillis = arrival,
    )
}
