package pt.cpcompanion.worker

import android.content.Context
import pt.cpcompanion.automation.TicketActivationScheduler
import pt.cpcompanion.model.Ticket

/** Compatibility facade retained for import/UI call sites. */
object TicketReminderScheduler {
    fun schedule(context: Context, ticket: Ticket) {
        TicketActivationScheduler.schedule(context, ticket)
    }

    fun cancel(context: Context, ticketId: String) {
        TicketActivationScheduler.cancel(context, ticketId)
    }
}
