package pt.cpcompanion.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import pt.cpcompanion.TrainTrackerApplication
import pt.cpcompanion.model.Ticket
import pt.cpcompanion.model.TicketActivationMethod
import pt.cpcompanion.model.TicketAutomationState
import pt.cpcompanion.model.isClearlyPastForAutomation
import pt.cpcompanion.worker.UpcomingTripReminderWorker

/** Schedules the user-visible T-60 activation and a deferrable recovery path. */
object TicketActivationScheduler {
    const val LEAD_TIME_MS = 60L * 60L * 1000L
    private const val RECONCILE_AHEAD_MS = 15L * 60L * 1000L

    fun schedule(context: Context, ticket: Ticket, force: Boolean = false): Ticket? {
        val app = context.applicationContext as TrainTrackerApplication
        val stores = app.container.stores
        val now = System.currentTimeMillis()
        if (ticket.automationState == TicketAutomationState.COMPLETED && ticket.completedAtEpochMillis != null) {
            cancel(context, ticket.id)
            return ticket
        }
        if (ticket.isClearlyPastForAutomation(nowEpochMillis = now)) {
            cancel(context, ticket.id)
            return stores.updateTicket(ticket.id) {
                it.copy(
                    automaticTrackingEnabled = false,
                    automationState = TicketAutomationState.COMPLETED,
                    activationEpochMillis = null,
                    activationMethod = TicketActivationMethod.NONE,
                    schedulingFingerprint = null,
                    automationMessage = "Passenger segment already completed",
                    lastAutomationAttemptEpochMillis = now,
                    completedAtEpochMillis = it.completedAtEpochMillis ?: now,
                )
            }
        }
        if (!ticket.automaticTrackingEnabled) {
            cancel(context, ticket.id)
            return stores.updateTicket(ticket.id) {
                it.copy(
                    automaticTrackingEnabled = false,
                    automationState = TicketAutomationState.DISABLED,
                    activationEpochMillis = null,
                    activationMethod = TicketActivationMethod.NONE,
                    schedulingFingerprint = null,
                    automationMessage = "Automatic tracking disabled for this ticket",
                    lastAutomationAttemptEpochMillis = null,
                    completedAtEpochMillis = null,
                )
            }
        }
        val departure = ticket.scheduledDepartureEpochMillis ?: run {
            cancel(context, ticket.id)
            return stores.updateTicket(ticket.id) {
                it.copy(
                    automationState = TicketAutomationState.NEEDS_VALIDATION,
                    activationEpochMillis = null,
                    activationMethod = TicketActivationMethod.NONE,
                    schedulingFingerprint = null,
                    automationMessage = "Waiting for a validated passenger departure time",
                    lastAutomationAttemptEpochMillis = null,
                    completedAtEpochMillis = null,
                )
            }
        }
        val activationAt = departure - LEAD_TIME_MS
        val fingerprint = fingerprint(ticket, activationAt)
        val unchangedAndPresent = ticket.automationState in setOf(
            TicketAutomationState.STARTING,
            TicketAutomationState.TRACKING,
        ) || hasActivationAlarm(context, ticket.id)
        if (!force && ticket.schedulingFingerprint == fingerprint &&
            ticket.activationEpochMillis == activationAt &&
            ticket.automationState in setOf(
                TicketAutomationState.SCHEDULED,
                TicketAutomationState.BLOCKED_EXACT_ALARM_PERMISSION,
                TicketAutomationState.STARTING,
                TicketAutomationState.TRACKING,
            ) && unchangedAndPresent
        ) {
            if (ticket.automationState in setOf(
                    TicketAutomationState.SCHEDULED,
                    TicketAutomationState.BLOCKED_EXACT_ALARM_PERMISSION,
                )
            ) {
                enqueueActivationWork(context, ticket.id, (activationAt - now).coerceAtLeast(0L))
                enqueueReconciliationWork(
                    context,
                    ticket.id,
                    (activationAt - RECONCILE_AHEAD_MS - now).coerceAtLeast(0L),
                )
            }
            return ticket
        }

        cancel(context, ticket.id)
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val operation = activationPendingIntent(context, ticket.id, PendingIntent.FLAG_UPDATE_CURRENT)
            ?: return stores.updateAutomation(
                ticket.id,
                TicketAutomationState.FAILED,
                message = "Could not create the activation alarm",
            )
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (activationAt <= now) {
            val triggerAt = now + 1_000L
            if (exactAllowed) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            }
            enqueueActivationWork(context, ticket.id, 0L, ExistingWorkPolicy.REPLACE)
            return stores.updateAutomation(
                ticket.id,
                TicketAutomationState.STARTING,
                activationEpochMillis = activationAt,
                method = TicketActivationMethod.IMMEDIATE,
                fingerprint = fingerprint,
                message = if (departure <= now) {
                    "Ticket imported during the passenger journey; activation is pending now"
                } else {
                    "Inside the one-hour window; activation is pending now"
                },
            )
        }

        val method: TicketActivationMethod
        val state: TicketAutomationState
        val message: String
        if (exactAllowed) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, activationAt, operation)
            method = TicketActivationMethod.EXACT_ALARM
            state = TicketAutomationState.SCHEDULED
            message = "Automatic tracking scheduled"
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, activationAt, operation)
            method = TicketActivationMethod.INEXACT_ALARM
            state = TicketAutomationState.BLOCKED_EXACT_ALARM_PERMISSION
            message = "Exact alarm access is disabled; a best-effort alarm and recovery job are scheduled"
        }

        enqueueActivationWork(context, ticket.id, activationAt - now, ExistingWorkPolicy.REPLACE)
        enqueueReconciliationWork(
            context,
            ticket.id,
            (activationAt - RECONCILE_AHEAD_MS - now).coerceAtLeast(0L),
            ExistingWorkPolicy.REPLACE,
        )
        return stores.updateAutomation(
            ticket.id,
            state,
            activationEpochMillis = activationAt,
            method = method,
            fingerprint = fingerprint,
            message = message,
            clearAttemptedAtEpochMillis = true,
        )
    }

    fun cancel(context: Context, ticketId: String) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        activationPendingIntent(context, ticketId, PendingIntent.FLAG_NO_CREATE)?.let(alarmManager::cancel)
        WorkManager.getInstance(context).cancelUniqueWork(activationWorkName(ticketId))
        WorkManager.getInstance(context).cancelUniqueWork(reconcileWorkName(ticketId))
    }

    fun hasActivationAlarm(context: Context, ticketId: String): Boolean =
        activationPendingIntent(context, ticketId, PendingIntent.FLAG_NO_CREATE) != null

    fun requestCode(ticketId: String): Int = ticketId.hashCode() and 0x7fffffff

    private fun activationPendingIntent(context: Context, ticketId: String, existenceFlag: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            requestCode(ticketId),
            Intent(context, TicketActivationReceiver::class.java)
                .setAction(TicketActivationReceiver.ACTION_ACTIVATE)
                .setData("cpcompanion://activation/$ticketId".toUri())
                .putExtra(TicketActivationReceiver.EXTRA_TICKET_ID, ticketId),
            existenceFlag or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun enqueueActivationWork(
        context: Context,
        ticketId: String,
        delayMs: Long,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
    ) {
        val request = OneTimeWorkRequestBuilder<UpcomingTripReminderWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(UpcomingTripReminderWorker.KEY_TICKET_ID, ticketId).build())
            .addTag(TAG_ACTIVATION)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            activationWorkName(ticketId),
            policy,
            request,
        )
    }

    private fun enqueueReconciliationWork(
        context: Context,
        ticketId: String,
        delayMs: Long,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
    ) {
        val request = OneTimeWorkRequestBuilder<TicketAutomationReconcileWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(TicketAutomationReconcileWorker.KEY_TICKET_ID, ticketId).build())
            .addTag(TAG_RECONCILIATION)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            reconcileWorkName(ticketId),
            policy,
            request,
        )
    }

    private fun fingerprint(ticket: Ticket, activationAt: Long): String =
        "${ticket.id}|${ticket.serviceDate}|${ticket.scheduledDepartureEpochMillis}|$activationAt|${ticket.automaticTrackingEnabled}"

    private fun activationWorkName(ticketId: String) = "ticket-activation-$ticketId"
    private fun reconcileWorkName(ticketId: String) = "ticket-reconcile-$ticketId"

    private const val TAG_ACTIVATION = "ticket-activation"
    private const val TAG_RECONCILIATION = "ticket-reconciliation"
}
