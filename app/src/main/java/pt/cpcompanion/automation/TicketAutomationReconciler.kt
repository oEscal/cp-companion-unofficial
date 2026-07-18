package pt.cpcompanion.automation

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import pt.cpcompanion.TrainTrackerApplication
import pt.cpcompanion.model.TicketActivationMethod
import pt.cpcompanion.model.TicketAutomationState
import pt.cpcompanion.model.isClearlyPastForAutomation
import pt.cpcompanion.tracking.TrackingSessionRegistry

object TicketAutomationReconciler {
    fun enqueue(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<TicketAutomationReconcileWorker>().build(),
        )
    }

    suspend fun reconcileNow(context: Context, onlyTicketId: String? = null) {
        val app = context.applicationContext as TrainTrackerApplication
        val stores = app.container.stores
        stores.awaitReady()
        val now = System.currentTimeMillis()
        val activeIds = stores.activeTicketIds.value
        val tickets = stores.tickets.value
            .asSequence()
            .filter { onlyTicketId == null || it.id == onlyTicketId }
            .sortedBy { it.scheduledDepartureEpochMillis ?: Long.MAX_VALUE }
            .toList()

        tickets.forEach { ticket ->
            if (ticket.automationState == TicketAutomationState.COMPLETED && ticket.completedAtEpochMillis != null) {
                TicketActivationScheduler.cancel(context, ticket.id)
                return@forEach
            }
            if (ticket.id in activeIds) {
                // An already-running delayed journey remains eligible even after its scheduled arrival.
                if (!TrackingSessionRegistry.isRunning(ticket.id)) {
                    TicketActivationLauncher.activate(context, ticket.id, "active-session recovery")
                }
                return@forEach
            }
            if (ticket.isClearlyPastForAutomation(nowEpochMillis = now)) {
                stores.updateTicket(ticket.id) {
                    it.copy(
                        automaticTrackingEnabled = false,
                        automationState = TicketAutomationState.COMPLETED,
                        activationEpochMillis = null,
                        activationMethod = TicketActivationMethod.NONE,
                        schedulingFingerprint = null,
                        automationMessage = "Passenger segment completed",
                        lastAutomationAttemptEpochMillis = now,
                        completedAtEpochMillis = it.completedAtEpochMillis ?: now,
                    )
                }
                TicketActivationScheduler.cancel(context, ticket.id)
                return@forEach
            }
            if (!ticket.automaticTrackingEnabled) {
                TicketActivationScheduler.cancel(context, ticket.id)
                return@forEach
            }
            if (ticket.scheduledDepartureEpochMillis == null) {
                if (ticket.automationState == TicketAutomationState.FAILED) {
                    TicketActivationScheduler.cancel(context, ticket.id)
                    return@forEach
                }
                stores.updateTicket(ticket.id) {
                    it.copy(
                        automationState = TicketAutomationState.NEEDS_VALIDATION,
                        activationEpochMillis = null,
                        activationMethod = TicketActivationMethod.NONE,
                        schedulingFingerprint = null,
                        automationMessage = "Passenger segment still needs validation",
                        lastAutomationAttemptEpochMillis = null,
                        completedAtEpochMillis = null,
                    )
                }
                return@forEach
            }
            val activationAt = ticket.scheduledDepartureEpochMillis - TicketActivationScheduler.LEAD_TIME_MS
            if (activationAt <= now) {
                TicketActivationLauncher.activate(context, ticket.id, "reconciliation")
            } else {
                TicketActivationScheduler.schedule(context, ticket)
            }
        }
    }

    private const val UNIQUE_WORK = "ticket-automation-global-reconcile"
}
