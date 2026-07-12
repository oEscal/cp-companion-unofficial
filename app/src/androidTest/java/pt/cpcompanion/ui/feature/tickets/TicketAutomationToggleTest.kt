package pt.cpcompanion.ui.feature.tickets

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import pt.cpcompanion.model.Ticket

class TicketAutomationToggleTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun futureTicketShowsEnabledSwitch() {
        val now = System.currentTimeMillis()
        val ticket = ticket(
            serviceDate = LocalDate.now(ZoneId.of("Europe/Lisbon")).plusDays(1).toString(),
            departure = now + 86_400_000L,
            arrival = now + 90_000_000L,
        )
        compose.setContent { TicketAutomationToggle(ticket, {}) }
        compose.onNodeWithTag("ticket-automation-switch-${ticket.id}").assertIsOn()
    }

    @Test
    fun pastTicketHasNoSwitch() {
        val now = System.currentTimeMillis()
        val ticket = ticket(
            serviceDate = LocalDate.now(ZoneId.of("Europe/Lisbon")).minusDays(1).toString(),
            departure = now - 90_000_000L,
            arrival = now - 86_400_000L,
        )
        compose.setContent { TicketAutomationToggle(ticket, {}) }
        compose.onAllNodesWithTag(
            "ticket-automation-switch-${ticket.id}",
        ).assertCountEquals(0)
    }

    @Test
    fun departedTicketHasNoSwitchEvenBeforeScheduledArrival() {
        val now = System.currentTimeMillis()
        val ticket = ticket(
            serviceDate = LocalDate.now(ZoneId.of("Europe/Lisbon")).toString(),
            departure = now - 10 * 60_000L,
            arrival = now + 50 * 60_000L,
        )
        compose.setContent { TicketAutomationToggle(ticket, {}) }
        compose.onAllNodesWithTag(
            "ticket-automation-switch-${ticket.id}",
        ).assertCountEquals(0)
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
