package pt.cpcompanion.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import pt.cpcompanion.TrainTrackerApplication
import pt.cpcompanion.model.TicketAutomationState

class TicketAutomationReconcileWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val onlyTicketId = inputData.getString(KEY_TICKET_ID)
        val container = (applicationContext as TrainTrackerApplication).container
        container.stores.awaitReady()
        var retryNeeded = false

        container.stores.tickets.value
            .asSequence()
            .filter { onlyTicketId == null || it.id == onlyTicketId }
            .filter { it.automaticTrackingEnabled }
            .filter { it.automationState != TicketAutomationState.FAILED }
            .filter { it.scheduledDepartureEpochMillis == null || it.automationState == TicketAutomationState.NEEDS_VALIDATION }
            .toList()
            .forEach { ticket ->
                val outcome = TicketValidationCoordinator.validateAndSchedule(applicationContext, ticket)
                retryNeeded = retryNeeded || outcome.shouldRetry
            }

        return try {
            TicketAutomationReconciler.reconcileNow(applicationContext, onlyTicketId)
            if (retryNeeded) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_TICKET_ID = "ticket_id"
    }
}
