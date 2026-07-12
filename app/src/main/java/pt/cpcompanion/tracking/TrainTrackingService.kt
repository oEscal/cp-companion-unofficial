package pt.cpcompanion.tracking

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
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
import pt.cpcompanion.model.PassengerPhase
import pt.cpcompanion.model.TrackingSnapshot
import pt.cpcompanion.notifications.TrackingNotificationFactory

class TrainTrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var trackingJob: Job? = null

    private val container by lazy { (application as TrainTrackerApplication).container }

    override fun onCreate() {
        super.onCreate()
        container.notifications.createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking(userInitiated = true)
            return START_NOT_STICKY
        }
        val ticketId = intent?.getStringExtra(EXTRA_TICKET_ID) ?: container.stores.activeTicketId.value
        if (ticketId.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        container.stores.setActiveTicket(ticketId)
        val placeholder = container.stores.tracking.value?.takeIf { it.ticketId == ticketId }
            ?: placeholder(ticketId)
        startInForeground(placeholder)
        startLoop(ticketId)
        return START_STICKY
    }

    override fun onDestroy() {
        trackingJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopTracking(userInitiated = false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startLoop(ticketId: String) {
        trackingJob?.cancel()
        trackingJob = scope.launch {
            var failures = 0
            while (isActive) {
                val ticket = container.stores.ticket(ticketId) ?: run {
                    stopTracking(userInitiated = false)
                    return@launch
                }
                try {
                    val trip = container.repository.trip(ticket.trainNumber, LocalDate.parse(ticket.serviceDate))
                    val snapshot = container.resolver.resolve(ticket, trip)
                    failures = 0
                    container.stores.saveTracking(snapshot)
                    updateNotification(snapshot)
                    if (snapshot.phase in setOf(PassengerPhase.ARRIVED, PassengerPhase.CANCELLED)) {
                        delay(3_000)
                        finishSession(snapshot)
                        return@launch
                    }
                    delay(SUCCESS_INTERVAL_MS)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failures += 1
                    val previous = container.stores.tracking.value ?: placeholder(ticketId)
                    val unavailable = container.resolver.unavailable(previous, failures)
                    container.stores.saveTracking(unavailable)
                    updateNotification(unavailable)
                    delay(retryDelay(failures))
                }
            }
        }
    }

    private fun startInForeground(snapshot: TrackingSnapshot) {
        ServiceCompat.startForeground(
            this,
            TrackingNotificationFactory.NOTIFICATION_ID,
            container.notifications.build(snapshot),
            if (android.os.Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification(snapshot: TrackingSnapshot) {
        NotificationManagerCompat.from(this).notify(
            TrackingNotificationFactory.NOTIFICATION_ID,
            container.notifications.build(snapshot),
        )
    }

    @SuppressLint("MissingPermission")
    private fun finishSession(finalSnapshot: TrackingSnapshot) {
        updateNotification(finalSnapshot)
        container.stores.clearTracking()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    @SuppressLint("MissingPermission")
    private fun stopTracking(userInitiated: Boolean) {
        trackingJob?.cancel()
        val current = container.stores.tracking.value
        if (userInitiated && current != null) {
            val stopped = current.copy(phase = PassengerPhase.STOPPED, updatedAtEpochMillis = System.currentTimeMillis())
            NotificationManagerCompat.from(this).notify(
                TrackingNotificationFactory.NOTIFICATION_ID,
                container.notifications.build(stopped),
            )
        } else {
            NotificationManagerCompat.from(this).cancel(TrackingNotificationFactory.NOTIFICATION_ID)
        }
        container.stores.clearTracking()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun placeholder(ticketId: String): TrackingSnapshot {
        val ticket = container.stores.ticket(ticketId)
        return TrackingSnapshot(
            ticketId = ticketId,
            trainNumber = ticket?.trainNumber ?: "—",
            serviceDate = ticket?.serviceDate.orEmpty(),
            phase = PassengerPhase.DATA_UNAVAILABLE,
            originName = ticket?.originName ?: ticket?.originStationCode ?: "Origin",
            destinationName = ticket?.destinationName ?: ticket?.destinationStationCode ?: "Destination",
            carriage = ticket?.carriage,
            seat = ticket?.seat,
            message = "Starting live tracking",
        )
    }

    private fun retryDelay(failures: Int): Long = when (failures) {
        1 -> 30_000L
        2 -> 60_000L
        3 -> 90_000L
        else -> 120_000L
    }

    companion object {
        const val ACTION_START = "pt.cpcompanion.action.START_TRACKING"
        const val ACTION_STOP = "pt.cpcompanion.action.STOP_TRACKING"
        const val EXTRA_TICKET_ID = "ticket_id"
        private const val SUCCESS_INTERVAL_MS = 30_000L

        fun start(context: Context, ticketId: String) {
            val app = context.applicationContext as TrainTrackerApplication
            app.container.stores.setActiveTicket(ticketId)
            ContextCompat.startForegroundService(
                context,
                Intent(context, TrainTrackingService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_TICKET_ID, ticketId),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TrainTrackingService::class.java).setAction(ACTION_STOP))
        }
    }
}

