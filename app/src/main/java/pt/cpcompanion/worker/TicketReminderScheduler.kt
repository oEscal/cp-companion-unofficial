package pt.cpcompanion.worker

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import pt.cpcompanion.model.Ticket

object TicketReminderScheduler {
    private const val LEAD_TIME_MS = 60 * 60 * 1000L

    fun schedule(context: Context, ticket: Ticket) {
        val departure = ticket.scheduledDepartureEpochMillis ?: return
        val now = System.currentTimeMillis()
        if (departure <= now) return
        val delay = (departure - LEAD_TIME_MS - now).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<UpcomingTripReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(UpcomingTripReminderWorker.KEY_TICKET_ID, ticket.id).build())
            .addTag("ticket-reminder")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "ticket-reminder-${ticket.id}",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel(context: Context, ticketId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("ticket-reminder-$ticketId")
    }
}
