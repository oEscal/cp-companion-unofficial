package pt.cpcompanion.automation

import android.content.Context
import java.time.LocalDate
import pt.cpcompanion.TrainTrackerApplication
import pt.cpcompanion.domain.TripResolutionException
import pt.cpcompanion.model.Ticket
import pt.cpcompanion.model.TicketAutomationState

/** Resolves imported station names, validates the passenger segment, and persists stable schedule data. */
object TicketAutomationValidator {
    suspend fun validate(context: Context, source: Ticket): Ticket {
        val container = (context.applicationContext as TrainTrackerApplication).container
        var ticket = source
        if (ticket.originStationCode.isBlank() || ticket.destinationStationCode.isBlank()) {
            var stations = container.repository.cachedStations()
            ticket = container.smsTicketImporter.resolveTicketStations(ticket, stations)
            if (ticket.originStationCode.isBlank() || ticket.destinationStationCode.isBlank()) {
                stations = container.repository.refreshStations(forceRefresh = false)
                ticket = container.smsTicketImporter.resolveTicketStations(ticket, stations)
            }
        }
        if (ticket.originStationCode.isBlank() || ticket.destinationStationCode.isBlank()) {
            throw TripResolutionException("The passenger stations could not be matched")
        }

        val trip = container.repository.trip(ticket.trainNumber, LocalDate.parse(ticket.serviceDate))
        val snapshot = container.resolver.resolve(ticket, trip)
        val validated = ticket.copy(
            serviceLabel = trip.serviceName ?: ticket.serviceLabel,
            originName = snapshot.originName,
            destinationName = snapshot.destinationName,
            scheduledDepartureEpochMillis = snapshot.scheduledOriginEpochMillis,
            scheduledArrivalEpochMillis = snapshot.scheduledDestinationEpochMillis,
            automationState = TicketAutomationState.SCHEDULED,
            automationMessage = "Validated and ready for automatic tracking",
            validationFailureCount = 0,
            lastAutomationAttemptEpochMillis = System.currentTimeMillis(),
        )
        return container.stores.upsertTicket(validated)
    }
}
