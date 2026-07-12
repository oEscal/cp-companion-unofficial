package pt.cpcompanion.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import pt.cpcompanion.TrainTrackerApplication
import pt.cpcompanion.automation.TicketActivationLauncher
import pt.cpcompanion.automation.TicketValidationCoordinator

/** Deferrable fallback for the alarm-based T-60 activation path. */
class UpcomingTripReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val ticketId = inputData.getString(KEY_TICKET_ID) ?: return Result.failure()
        val container = (applicationContext as TrainTrackerApplication).container
        container.stores.awaitReady()
        val source = container.stores.ticket(ticketId) ?: return Result.success()
        if (!source.automaticTrackingEnabled) return Result.success()

        val ticket = if (source.scheduledDepartureEpochMillis == null ||
            source.originStationCode.isBlank() || source.destinationStationCode.isBlank()
        ) {
            val outcome = TicketValidationCoordinator.validateAndSchedule(applicationContext, source)
            if (!outcome.validated) return if (outcome.shouldRetry) Result.retry() else Result.success()
            outcome.ticket
        } else source

        return when (TicketActivationLauncher.activate(applicationContext, ticket.id, "WorkManager fallback")) {
            TicketActivationLauncher.Result.STARTED,
            TicketActivationLauncher.Result.ALREADY_RUNNING,
            TicketActivationLauncher.Result.CONFLICT -> Result.success()
            TicketActivationLauncher.Result.BLOCKED_PERMISSION,
            TicketActivationLauncher.Result.FAILED -> Result.retry()
        }
    }

    companion object {
        const val KEY_TICKET_ID = "ticket_id"
    }
}
