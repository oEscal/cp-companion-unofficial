package pt.cpcompanion.tracking

import android.Manifest
import android.content.pm.PackageManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pt.cpcompanion.TrainTrackerApplication
import pt.cpcompanion.automation.TicketActivationLauncher
import pt.cpcompanion.automation.TicketActivationScheduler
import pt.cpcompanion.automation.TicketAutomationReconciler
import pt.cpcompanion.domain.TripResolutionException
import pt.cpcompanion.model.PassengerPhase
import pt.cpcompanion.model.Ticket
import pt.cpcompanion.model.TicketActivationMethod
import pt.cpcompanion.model.TicketAutomationState
import pt.cpcompanion.model.TrackingFailureCategory
import pt.cpcompanion.model.TrackingSnapshot
import pt.cpcompanion.network.ApiConfigurationException
import pt.cpcompanion.network.ApiCooldownException
import pt.cpcompanion.network.ApiHttpException
import pt.cpcompanion.notifications.TrackingNotificationFactory

class TrainTrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var trackingJob: Job? = null
    private var currentTicketId: String? = null
    private var lastNotificationSignature: String? = null

    private val container by lazy { (application as TrainTrackerApplication).container }

    override fun onCreate() {
        super.onCreate()
        container.notifications.createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val requested = intent.getStringExtra(EXTRA_TICKET_ID)
            val active = currentTicketId ?: container.stores.activeTicketId.value
            if (requested == null || requested == active) {
                stopTracking(userInitiated = true)
            } else {
                // A detached terminal notification can outlive the original service instance.
                stopSelfResult(startId)
            }
            return START_NOT_STICKY
        }
        val requestedTicketId = intent?.getStringExtra(EXTRA_TICKET_ID) ?: container.stores.activeTicketId.value
        if (requestedTicketId.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val active = container.stores.activeTicketId.value
        val ticketId = if (active != null && active != requestedTicketId) {
            container.stores.updateAutomation(
                requestedTicketId,
                TicketAutomationState.CONFLICT_WITH_OTHER_TRIP,
                message = "Queued behind another active passenger journey",
            )
            // Keep/recover the existing session; never stop it because a queued ticket also fired.
            active
        } else {
            requestedTicketId
        }

        if (!container.stores.claimActiveTicket(ticketId)) return START_STICKY
        container.stores.updateAutomation(
            ticketId,
            TicketAutomationState.TRACKING,
            message = "Automatic tracking is running",
            attemptedAtEpochMillis = System.currentTimeMillis(),
        )
        val placeholder = container.stores.tracking.value?.takeIf { it.ticketId == ticketId } ?: placeholder(ticketId)
        try {
            startInForeground(placeholder)
        } catch (error: SecurityException) {
            container.stores.updateAutomation(
                ticketId,
                TicketAutomationState.BLOCKED_NOTIFICATION_PERMISSION,
                message = "Android blocked the required foreground notification",
            )
            container.stores.clearTracking()
            currentTicketId = null
            stopSelf(startId)
            return START_NOT_STICKY
        }
        TrackingSessionRegistry.markStarted(ticketId)
        if (currentTicketId != ticketId || trackingJob?.isActive != true) {
            currentTicketId = ticketId
            startLoop(ticketId)
        } else {
            TrackingSessionRegistry.heartbeat(ticketId)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        TrackingSessionRegistry.markStopped(currentTicketId)
        trackingJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val ticketId = currentTicketId
        if (ticketId != null) {
            TicketActivationScheduler.cancel(this, ticketId)
            container.stores.updateTicket(ticketId) { ticket ->
                ticket.copy(
                    automaticTrackingEnabled = false,
                    automationState = TicketAutomationState.FAILED,
                    automationMessage = "Android ended the foreground service after its time budget; re-enable this ticket to retry",
                )
            }
            val current = container.stores.tracking.value ?: placeholder(ticketId)
            updateNotification(
                current.copy(
                    phase = PassengerPhase.STOPPED,
                    dataStale = true,
                    failureCategory = TrackingFailureCategory.FOREGROUND_SERVICE_BLOCKED,
                    message = "Android ended live tracking after its foreground-service time budget",
                    lastAttemptEpochMillis = System.currentTimeMillis(),
                ),
                force = true,
            )
        }
        stopTracking(userInitiated = false, removeNotification = false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startLoop(ticketId: String) {
        trackingJob?.cancel()
        trackingJob = scope.launch {
            container.stores.awaitReady()
            var failures = 0
            var firstFailureAt: Long? = null
            while (isActive) {
                var ticket = container.stores.ticket(ticketId) ?: run {
                    stopTracking(userInitiated = false)
                    return@launch
                }
                if (!ticket.automaticTrackingEnabled) {
                    stopTracking(userInitiated = false)
                    return@launch
                }
                try {
                    ticket = resolveStationsIfNeeded(ticket)
                    val previous = container.stores.tracking.value?.takeIf { it.ticketId == ticketId }
                    val previousPhase = previous?.phase
                    val trip = container.repository.trip(ticket.trainNumber, LocalDate.parse(ticket.serviceDate))
                    val resolved = container.resolver.resolve(ticket, trip)
                    var snapshot = if (trip.dataStale && previous != null) {
                        failures = maxOf(failures + 1, previous.consecutiveFailures + 1)
                        if (firstFailureAt == null) firstFailureAt = System.currentTimeMillis()
                        container.resolver.unavailable(
                            previous = previous,
                            failures = failures,
                            category = TrackingFailureCategory.RATE_LIMITED,
                            message = trip.cooldownUntilEpochMillis?.let { retryAt ->
                                "Rate limited; showing the last live data and retrying at ${formatTime(retryAt)}"
                            } ?: "Rate limited; showing the last live data",
                            retryAtEpochMillis = trip.cooldownUntilEpochMillis,
                        )
                    } else {
                        resolved
                    }
                    if (snapshot.dataStale) {
                        val staleAge = System.currentTimeMillis() - snapshot.lastSuccessfulFetchEpochMillis
                        if (staleAge >= MAX_STALE_PERIOD_MS) {
                            snapshot = snapshot.copy(
                                message = "Live data has been unavailable for ${staleAge / 60_000L} minutes; displayed times may be outdated",
                                serviceHeartbeatEpochMillis = System.currentTimeMillis(),
                            )
                        }
                    }
                    container.stores.saveTracking(snapshot)
                    TrackingSessionRegistry.heartbeat(ticketId)
                    if (!updateNotification(snapshot)) {
                        blockForNotificationPermission(ticketId)
                        return@launch
                    }
                    if (!snapshot.dataStale) {
                        failures = 0
                        firstFailureAt = null
                        if (snapshot.phase.isImportantAlertPhase() && snapshot.phase != previousPhase) {
                            try {
                                container.notifications.postImportantAlert(snapshot)
                            } catch (_: SecurityException) {
                                blockForNotificationPermission(ticketId)
                                return@launch
                            }
                        }
                    }
                    if (!snapshot.dataStale && snapshot.phase in setOf(PassengerPhase.ARRIVED, PassengerPhase.CANCELLED)) {
                        delay(3_000)
                        finishSession(snapshot)
                        return@launch
                    }
                    val staleDelay = snapshot.retryAtEpochMillis
                        ?.minus(System.currentTimeMillis())
                        ?.coerceAtLeast(5_000L)
                    delayWithHeartbeat(ticketId, staleDelay ?: successInterval(snapshot))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    failures += 1
                    val failureStartedAt = firstFailureAt ?: System.currentTimeMillis().also { firstFailureAt = it }
                    val classified = classify(error)
                    val previous = container.stores.tracking.value?.takeIf { it.ticketId == ticketId } ?: placeholder(ticketId)
                    val unavailable = container.resolver.unavailable(
                        previous = previous,
                        failures = failures,
                        category = classified.category,
                        message = classified.userMessage,
                        retryAtEpochMillis = classified.retryAtEpochMillis,
                    )
                    container.stores.saveTracking(unavailable)
                    TrackingSessionRegistry.heartbeat(ticketId)
                    if (!updateNotification(unavailable)) {
                        blockForNotificationPermission(ticketId)
                        return@launch
                    }

                    val staleTooLong =
                        System.currentTimeMillis() - failureStartedAt >= MAX_STALE_PERIOD_MS
                    if (!classified.transient || staleTooLong) {
                        failSession(ticketId, unavailable, classified.userMessage)
                        return@launch
                    }
                    delayWithHeartbeat(ticketId, classified.retryDelayMillis ?: retryDelay(failures))
                }
            }
        }
    }

    private suspend fun resolveStationsIfNeeded(ticket: Ticket): Ticket {
        if (ticket.originStationCode.isNotBlank() && ticket.destinationStationCode.isNotBlank()) return ticket
        var stations = container.repository.cachedStations()
        var resolved = container.smsTicketImporter.resolveTicketStations(ticket, stations)
        if (resolved.originStationCode.isBlank() || resolved.destinationStationCode.isBlank()) {
            stations = container.repository.refreshStations(forceRefresh = false)
            resolved = container.smsTicketImporter.resolveTicketStations(ticket, stations)
        }
        if (resolved.originStationCode.isBlank() || resolved.destinationStationCode.isBlank()) {
            throw TripResolutionException("The ticket stations could not be matched")
        }
        return container.stores.upsertTicket(resolved)
    }

    private fun startInForeground(snapshot: TrackingSnapshot) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(
            this,
            TrackingNotificationFactory.NOTIFICATION_ID,
            container.notifications.build(snapshot),
            type,
        )
        lastNotificationSignature = notificationSignature(snapshot)
    }

    
    private fun updateNotification(snapshot: TrackingSnapshot, force: Boolean = false): Boolean {
        val signature = notificationSignature(snapshot)
        if (!force && signature == lastNotificationSignature) return true
        if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        if (!TicketActivationLauncher.notificationsUsable(this)) return false
        return try {
            NotificationManagerCompat.from(this).notify(
                TrackingNotificationFactory.NOTIFICATION_ID,
                container.notifications.build(snapshot),
            )
            lastNotificationSignature = signature
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun blockForNotificationPermission(ticketId: String) {
        container.stores.updateAutomation(
            ticketId,
            TicketAutomationState.BLOCKED_NOTIFICATION_PERMISSION,
            message = "Notification permission or the tracking channel was disabled while tracking",
        )
        TrackingSessionRegistry.markStopped(ticketId)
        container.stores.clearTracking()
        currentTicketId = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        TicketAutomationReconciler.enqueue(this)
    }

    
    private fun finishSession(finalSnapshot: TrackingSnapshot) {
        updateNotification(finalSnapshot, force = true)
        val ticketId = finalSnapshot.ticketId
        TicketActivationScheduler.cancel(this, ticketId)
        container.stores.updateTicket(ticketId) { ticket ->
            ticket.copy(
                automaticTrackingEnabled = false,
                automationState = TicketAutomationState.COMPLETED,
                activationEpochMillis = null,
                activationMethod = TicketActivationMethod.NONE,
                schedulingFingerprint = null,
                automationMessage = if (finalSnapshot.phase == PassengerPhase.CANCELLED) {
                    "Tracking ended because the passenger endpoint was cancelled or suppressed"
                } else {
                    "Passenger destination reached"
                },
                lastAutomationAttemptEpochMillis = System.currentTimeMillis(),
                completedAtEpochMillis = System.currentTimeMillis(),
            )
        }
        TrackingSessionRegistry.markStopped(ticketId)
        container.stores.clearTracking()
        currentTicketId = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    
    private fun failSession(ticketId: String, snapshot: TrackingSnapshot, message: String) {
        TicketActivationScheduler.cancel(this, ticketId)
        container.stores.updateTicket(ticketId) { ticket ->
            ticket.copy(
                automaticTrackingEnabled = false,
                automationState = TicketAutomationState.FAILED,
                automationMessage = "$message. Re-enable automatic tracking to retry.",
            )
        }
        updateNotification(
            snapshot.copy(
                phase = PassengerPhase.STOPPED,
                message = message,
                dataStale = true,
                lastAttemptEpochMillis = System.currentTimeMillis(),
            ),
            force = true,
        )
        TrackingSessionRegistry.markStopped(ticketId)
        container.stores.clearTracking()
        currentTicketId = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
        TicketAutomationReconciler.enqueue(this)
    }

    
    private fun stopTracking(userInitiated: Boolean, removeNotification: Boolean = true) {
        trackingJob?.cancel()
        val current = container.stores.tracking.value
        val ticketId = currentTicketId ?: current?.ticketId ?: container.stores.activeTicketId.value
        if (userInitiated && ticketId != null) {
            TicketActivationScheduler.cancel(this, ticketId)
            // This is the deliberate per-ticket automation opt-out allowed by the product contract.
            container.stores.updateTicket(ticketId) { ticket ->
                ticket.copy(
                    automaticTrackingEnabled = false,
                    automationState = TicketAutomationState.DISABLED,
                    automationMessage = "Tracking stopped and automatic activation disabled for this ticket",
                    activationEpochMillis = null,
                    activationMethod = TicketActivationMethod.NONE,
                    schedulingFingerprint = null,
                    lastAutomationAttemptEpochMillis = System.currentTimeMillis(),
                    completedAtEpochMillis = null,
                )
            }
        }
        if (userInitiated && current != null) {
            val stopped = current.copy(
                phase = PassengerPhase.STOPPED,
                lastAttemptEpochMillis = System.currentTimeMillis(),
                updatedAtEpochMillis = current.lastSuccessfulFetchEpochMillis,
            )
            updateNotification(stopped, force = true)
        } else if (removeNotification) {
            NotificationManagerCompat.from(this).cancel(TrackingNotificationFactory.NOTIFICATION_ID)
        }
        TrackingSessionRegistry.markStopped(ticketId)
        container.stores.clearTracking()
        currentTicketId = null
        ServiceCompat.stopForeground(
            this,
            if (removeNotification) ServiceCompat.STOP_FOREGROUND_REMOVE else ServiceCompat.STOP_FOREGROUND_DETACH,
        )
        stopSelf()
        TicketAutomationReconciler.enqueue(this)
    }

    private fun placeholder(ticketId: String): TrackingSnapshot {
        val ticket = container.stores.ticket(ticketId)
        val now = System.currentTimeMillis()
        return TrackingSnapshot(
            ticketId = ticketId,
            trainNumber = ticket?.trainNumber ?: "—",
            serviceLabel = ticket?.serviceLabel,
            serviceDate = ticket?.serviceDate.orEmpty(),
            phase = PassengerPhase.DATA_UNAVAILABLE,
            originName = ticket?.originName ?: ticket?.originStationCode ?: "Origin",
            destinationName = ticket?.destinationName ?: ticket?.destinationStationCode ?: "Destination",
            carriage = ticket?.carriage,
            seat = ticket?.seat,
            message = "Starting live tracking",
            lastSuccessfulFetchEpochMillis = now,
            lastAttemptEpochMillis = now,
            updatedAtEpochMillis = now,
            dataStale = true,
            serviceHeartbeatEpochMillis = now,
        )
    }

    private suspend fun delayWithHeartbeat(ticketId: String, durationMillis: Long) {
        var remaining = durationMillis.coerceAtLeast(0L)
        while (remaining > 0L) {
            val step = minOf(remaining, HEARTBEAT_INTERVAL_MS)
            delay(step)
            remaining -= step
            TrackingSessionRegistry.heartbeat(ticketId)
        }
    }

    private fun successInterval(snapshot: TrackingSnapshot): Long {
        val now = System.currentTimeMillis()
        return when (snapshot.phase) {
            PassengerPhase.PRE_TRIP -> {
                val until = snapshot.expectedOriginEpochMillis?.minus(now) ?: Long.MAX_VALUE
                when {
                    until > Duration.ofMinutes(30).toMillis() -> 60_000L
                    until > Duration.ofMinutes(10).toMillis() -> 30_000L
                    else -> 15_000L
                }
            }
            PassengerPhase.APPROACHING_ORIGIN -> 15_000L
            PassengerPhase.BOARDING_SOON -> 10_000L
            PassengerPhase.ON_BOARD -> {
                val remaining = snapshot.expectedDestinationEpochMillis?.minus(now) ?: 0L
                if (remaining > Duration.ofMinutes(10).toMillis() && (snapshot.delayMinutes ?: 0) == 0) 25_000L else 12_000L
            }
            PassengerPhase.APPROACHING_DESTINATION -> 10_000L
            else -> 30_000L
        }
    }

    private fun classify(error: Throwable): ClassifiedFailure = when (error) {
        is ApiCooldownException -> ClassifiedFailure(
            true,
            TrackingFailureCategory.RATE_LIMITED,
            "Rate limited; retrying at ${formatTime(error.retryAtEpochMillis)}",
            (error.retryAtEpochMillis - System.currentTimeMillis()).coerceAtLeast(5_000L),
            error.retryAtEpochMillis,
        )
        is ApiHttpException -> when {
            error.statusCode == 429 -> ClassifiedFailure(
                true,
                TrackingFailureCategory.RATE_LIMITED,
                error.message ?: "CP rate limited live updates",
                error.retryAfterEpochMillis?.minus(System.currentTimeMillis())?.coerceAtLeast(5_000L),
                error.retryAfterEpochMillis,
            )
            error.statusCode == 404 -> ClassifiedFailure(
                false,
                TrackingFailureCategory.TRIP_NOT_FOUND,
                "CP could not find this train trip",
            )
            error.statusCode in setOf(401, 403) -> ClassifiedFailure(
                true,
                TrackingFailureCategory.AUTHORIZATION_OR_CONFIGURATION,
                "CP service authorization is temporarily unavailable",
            )
            error.statusCode in 400..499 -> ClassifiedFailure(
                false,
                TrackingFailureCategory.PERMANENT_INVALID_TICKET,
                "The ticket cannot be tracked with the current CP trip endpoint",
            )
            else -> ClassifiedFailure(true, TrackingFailureCategory.UNKNOWN_TRANSIENT, "CP service temporarily unavailable")
        }
        is TripResolutionException -> ClassifiedFailure(
            false,
            TrackingFailureCategory.TICKET_TRIP_MISMATCH,
            error.message ?: "Ticket and trip do not match",
        )
        is ApiConfigurationException -> ClassifiedFailure(
            true,
            TrackingFailureCategory.AUTHORIZATION_OR_CONFIGURATION,
            error.message ?: "CP configuration unavailable",
        )
        is SocketTimeoutException -> ClassifiedFailure(true, TrackingFailureCategory.TIMEOUT, "CP live update timed out")
        is UnknownHostException -> ClassifiedFailure(true, TrackingFailureCategory.NETWORK_UNAVAILABLE, "Network unavailable")
        is IOException -> ClassifiedFailure(true, TrackingFailureCategory.NETWORK_UNAVAILABLE, error.message ?: "Network unavailable")
        is IllegalArgumentException -> ClassifiedFailure(
            false,
            TrackingFailureCategory.PERMANENT_INVALID_TICKET,
            error.message ?: "Invalid ticket data",
        )
        else -> ClassifiedFailure(true, TrackingFailureCategory.UNKNOWN_TRANSIENT, error.message ?: "Live update failed")
    }

    private fun retryDelay(failures: Int): Long = when (failures) {
        1 -> 30_000L
        2 -> 60_000L
        3 -> 90_000L
        4 -> 120_000L
        else -> 180_000L
    }

    private fun notificationSignature(snapshot: TrackingSnapshot): String = listOf(
        snapshot.phase,
        snapshot.expectedEventEpochMillis,
        snapshot.delayMinutes,
        snapshot.platform,
        snapshot.nextStopName,
        snapshot.progress / 10,
        snapshot.dataStale,
        snapshot.retryAtEpochMillis,
        snapshot.message,
    ).joinToString("|")

    private fun PassengerPhase.isImportantAlertPhase(): Boolean = this in setOf(
        PassengerPhase.BOARDING_SOON,
        PassengerPhase.ON_BOARD,
        PassengerPhase.APPROACHING_DESTINATION,
        PassengerPhase.ARRIVED,
        PassengerPhase.CANCELLED,
    )

    private fun formatTime(epochMillis: Long): String = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        .format(Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault()))

    private data class ClassifiedFailure(
        val transient: Boolean,
        val category: TrackingFailureCategory,
        val userMessage: String,
        val retryDelayMillis: Long? = null,
        val retryAtEpochMillis: Long? = null,
    )

    companion object {
        const val ACTION_START = "pt.cpcompanion.action.START_TRACKING"
        const val ACTION_STOP = "pt.cpcompanion.action.STOP_TRACKING"
        const val EXTRA_TICKET_ID = "ticket_id"
        private const val MAX_STALE_PERIOD_MS = 20L * 60L * 1000L
        private const val HEARTBEAT_INTERVAL_MS = 60_000L

        fun start(context: Context, ticketId: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TrainTrackingService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_TICKET_ID, ticketId),
            )
        }

        fun stop(context: Context, ticketId: String? = null) {
            val intent = Intent(context, TrainTrackingService::class.java).setAction(ACTION_STOP)
            if (ticketId != null) intent.putExtra(EXTRA_TICKET_ID, ticketId)
            context.startService(intent)
        }
    }
}
