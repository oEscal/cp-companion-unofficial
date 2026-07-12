package pt.cpcompanion.automation

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import pt.cpcompanion.TrainTrackerApplication
import pt.cpcompanion.model.TicketActivationMethod
import pt.cpcompanion.model.TicketAutomationState
import pt.cpcompanion.model.hasScheduledPassengerSegmentEnded
import pt.cpcompanion.notifications.TrackingNotificationFactory
import pt.cpcompanion.tracking.TrackingSessionRegistry
import pt.cpcompanion.tracking.TrainTrackingService

object TicketActivationLauncher {
    enum class Result { STARTED, ALREADY_RUNNING, BLOCKED_PERMISSION, CONFLICT, FAILED }

    suspend fun activate(context: Context, ticketId: String, launchPath: String): Result {
        val app = context.applicationContext as TrainTrackerApplication
        val stores = app.container.stores
        stores.awaitReady()
        val ticket = stores.ticket(ticketId) ?: return Result.FAILED
        if (!ticket.automaticTrackingEnabled) return Result.FAILED
        if (ticket.hasScheduledPassengerSegmentEnded() && stores.activeTicketId.value != ticketId) {
            TicketActivationScheduler.cancel(context, ticketId)
            stores.updateTicket(ticketId) { current ->
                current.copy(
                    automaticTrackingEnabled = false,
                    automationState = TicketAutomationState.COMPLETED,
                    activationEpochMillis = null,
                    activationMethod = TicketActivationMethod.NONE,
                    schedulingFingerprint = null,
                    automationMessage = "Passenger segment already completed",
                    completedAtEpochMillis = current.completedAtEpochMillis ?: System.currentTimeMillis(),
                )
            }
            return Result.FAILED
        }
        if (!notificationsUsable(context)) {
            stores.updateAutomation(
                ticketId,
                TicketAutomationState.BLOCKED_NOTIFICATION_PERMISSION,
                message = "Notification permission or the tracking channel is disabled",
            )
            TicketAutomationReconciler.enqueue(context)
            return Result.BLOCKED_PERMISSION
        }

        if (!stores.claimActiveTicket(ticketId)) {
            stores.updateAutomation(
                ticketId,
                TicketAutomationState.CONFLICT_WITH_OTHER_TRIP,
                message = "Queued behind another active passenger journey",
            )
            TicketAutomationReconciler.enqueue(context)
            return Result.CONFLICT
        }
        if (!TrackingSessionRegistry.tryClaimLaunch(ticketId)) return Result.ALREADY_RUNNING

        stores.updateAutomation(
            ticketId,
            TicketAutomationState.STARTING,
            message = "Starting automatically via $launchPath",
            attemptedAtEpochMillis = System.currentTimeMillis(),
        )
        return try {
            TrainTrackingService.start(context, ticketId)
            Result.STARTED
        } catch (error: SecurityException) {
            TrackingSessionRegistry.markStopped(ticketId)
            stores.clearTracking()
            stores.updateAutomation(
                ticketId,
                TicketAutomationState.FAILED,
                message = "Foreground-service security policy blocked startup: ${error.message.orEmpty()}",
            )
            Result.FAILED
        } catch (error: RuntimeException) {
            TrackingSessionRegistry.markStopped(ticketId)
            stores.clearTracking()
            val category = if (error.javaClass.name.endsWith("ForegroundServiceStartNotAllowedException")) {
                "Android blocked background foreground-service startup"
            } else {
                "Automatic startup failed"
            }
            stores.updateAutomation(
                ticketId,
                TicketAutomationState.FAILED,
                message = "$category: ${error.javaClass.simpleName}",
            )
            Result.FAILED
        }

    }

    fun notificationsUsable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(TrackingNotificationFactory.CHANNEL_TRACKING)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }
}
