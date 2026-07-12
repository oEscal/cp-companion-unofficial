package pt.cpcompanion.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import pt.cpcompanion.MainActivity
import pt.cpcompanion.R
import pt.cpcompanion.domain.StatusChipFormatter
import pt.cpcompanion.model.PassengerPhase
import pt.cpcompanion.model.TrackingSnapshot
import pt.cpcompanion.tracking.TrainTrackingService
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class TrackingNotificationFactory(private val context: Context) {
    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TRACKING, context.getString(R.string.tracking_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.tracking_channel_description)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.alerts_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.alerts_channel_description)
            },
        )
    }

    fun build(snapshot: TrackingSnapshot): Notification = if (
        Build.VERSION.SDK_INT >= 36 &&
        snapshot.phase.supportsLiveUpdate() &&
        context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
    ) {
        buildProgressNotification(snapshot)
    } else {
        buildFallback(snapshot)
    }

    private fun buildFallback(snapshot: TrackingSnapshot): Notification {
        val content = content(snapshot)
        return NotificationCompat.Builder(context, CHANNEL_TRACKING)
            .setSmallIcon(iconFor(snapshot.phase))
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSubText(content.subtext)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.expanded))
            .setContentIntent(openIntent(snapshot.ticketId))
            .setDeleteIntent(stopIntent(snapshot.ticketId))
            .addAction(R.drawable.ic_stop, "Stop tracking", stopIntent(snapshot.ticketId))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(!snapshot.phase.isTerminal())
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(snapshot.expectedEventEpochMillis ?: snapshot.updatedAtEpochMillis)
            .setShowWhen(snapshot.expectedEventEpochMillis != null)
            .build()
    }

    @RequiresApi(36)
    private fun buildProgressNotification(snapshot: TrackingSnapshot): Notification {
        val content = content(snapshot)
        val progress = snapshot.progress.coerceIn(0, snapshot.progressMax)
        val style = Notification.ProgressStyle()
            .setStyledByProgress(true)
            .setProgress(progress)
            .setProgressTrackerIcon(Icon.createWithResource(context, iconFor(snapshot.phase)))
            .setProgressSegments(
                listOf(Notification.ProgressStyle.Segment(snapshot.progressMax).setColor(Color.rgb(0, 108, 76))),
            )
            .setProgressPoints(
                listOf(
                    Notification.ProgressStyle.Point(0).setColor(Color.rgb(0, 108, 76)),
                    Notification.ProgressStyle.Point(snapshot.progressMax).setColor(Color.rgb(0, 80, 170)),
                ),
            )

        val extras = Bundle().apply {
            putBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, true)
        }
        return Notification.Builder(context, CHANNEL_TRACKING)
            .setSmallIcon(iconFor(snapshot.phase))
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSubText(content.subtext)
            .setStyle(style)
            .setContentIntent(openIntent(snapshot.ticketId))
            .setDeleteIntent(stopIntent(snapshot.ticketId))
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_stop),
                    "Stop tracking",
                    stopIntent(snapshot.ticketId),
                ).build(),
            )
            .setOnlyAlertOnce(true)
            .setOngoing(!snapshot.phase.isTerminal())
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setColorized(false)
            .setExtras(extras)
            .apply {
                snapshot.expectedEventEpochMillis?.takeIf { it > System.currentTimeMillis() }?.let {
                    setWhen(it)
                    setShowWhen(true)
                }
                StatusChipFormatter.format(snapshot)?.let(::setShortCriticalText)
            }
            .build()
    }

    @RequiresApi(36)
    fun promotionStatus(): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        return if (manager.canPostPromotedNotifications()) {
            "Live Update promotion is allowed"
        } else {
            "Live Update promotion is disabled or unavailable; standard ongoing notifications remain active"
        }
    }

    private fun content(snapshot: TrackingSnapshot): NotificationContent {
        val expected = snapshot.expectedEventEpochMillis?.let(::formatTime)
        val scheduled = snapshot.scheduledEventEpochMillis?.let(::formatTime)
        val delay = when {
            snapshot.delayMinutes == null -> "Delay not reported"
            snapshot.delayMinutes > 0 -> "${snapshot.delayMinutes} min late"
            else -> "On time"
        }
        val platform = snapshot.platform?.takeIf(String::isNotBlank)?.let { "Platform $it" }
        val boarding = listOfNotNull(
            snapshot.carriage?.takeIf(String::isNotBlank)?.let { "Carriage $it" },
            snapshot.seat?.takeIf(String::isNotBlank)?.let { "Seat $it" },
        ).joinToString(" · ")

        return when (snapshot.phase) {
            PassengerPhase.BOARDING_SOON -> NotificationContent(
                title = "Train ${snapshot.trainNumber} arriving soon",
                text = listOfNotNull(platform, boarding.takeIf(String::isNotBlank)).joinToString(" · ").ifBlank { snapshot.originName },
                subtext = snapshot.originName,
                expanded = listOfNotNull(
                    "Expected at ${expected ?: "calculating"}",
                    scheduled?.let { "Scheduled $it" },
                    delay,
                    platform,
                    boarding.takeIf(String::isNotBlank),
                ).joinToString("\n"),
            )
            PassengerPhase.APPROACHING_ORIGIN, PassengerPhase.PRE_TRIP -> NotificationContent(
                title = "Train ${snapshot.trainNumber} approaching ${snapshot.originName}",
                text = "Expected ${expected ?: scheduled ?: "calculating"} · $delay",
                subtext = platform,
                expanded = "${snapshot.originName} → ${snapshot.destinationName}\n" +
                    "Expected ${expected ?: "calculating"}" +
                    (scheduled?.let { " · scheduled $it" } ?: "") + "\n$delay" +
                    (platform?.let { " · $it" } ?: ""),
            )
            PassengerPhase.ON_BOARD, PassengerPhase.APPROACHING_DESTINATION -> NotificationContent(
                title = "Train ${snapshot.trainNumber} to ${snapshot.destinationName}",
                text = "Expected ${expected ?: scheduled ?: "calculating"} · $delay",
                subtext = snapshot.nextStopName?.let { "Next: $it" },
                expanded = "Destination: ${snapshot.destinationName}\n" +
                    "Expected ${expected ?: "calculating"}" +
                    (scheduled?.let { " · scheduled $it" } ?: "") + "\n$delay" +
                    (snapshot.nextStopName?.let { "\nNext stop: $it" } ?: ""),
            )
            PassengerPhase.CANCELLED -> NotificationContent(
                title = "Train ${snapshot.trainNumber} disrupted",
                text = "The selected passenger segment includes a cancelled stop",
                subtext = snapshot.destinationName,
                expanded = snapshot.message ?: "Open the trip for the latest service information.",
            )
            PassengerPhase.DATA_UNAVAILABLE -> NotificationContent(
                title = "Tracking train ${snapshot.trainNumber}",
                text = "Live data temporarily unavailable",
                subtext = "Last update ${formatTime(snapshot.updatedAtEpochMillis)}",
                expanded = "The last valid trip information is being retained. Retrying automatically.",
            )
            PassengerPhase.ARRIVED -> NotificationContent(
                title = "Arrived at ${snapshot.destinationName}",
                text = "Train ${snapshot.trainNumber} tracking completed",
                subtext = null,
                expanded = "The active tracking session has ended.",
            )
            PassengerPhase.STOPPED -> NotificationContent(
                title = "Tracking stopped",
                text = "Train ${snapshot.trainNumber}",
                subtext = null,
                expanded = "Open the app to start another tracking session.",
            )
        }
    }

    private fun iconFor(phase: PassengerPhase): Int = when (phase) {
        PassengerPhase.BOARDING_SOON -> R.drawable.ic_seat
        else -> R.drawable.ic_train
    }

    private fun openIntent(ticketId: String): PendingIntent = PendingIntent.getActivity(
        context,
        ticketId.hashCode(),
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_TICKET_ID, ticketId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopIntent(ticketId: String): PendingIntent = PendingIntent.getService(
        context,
        ticketId.hashCode() xor 0x50A,
        Intent(context, TrainTrackingService::class.java)
            .setAction(TrainTrackingService.ACTION_STOP)
            .putExtra(TrainTrackingService.EXTRA_TICKET_ID, ticketId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun formatTime(epochMillis: Long): String = FORMATTER.format(
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()),
    )

    private fun PassengerPhase.isTerminal() = this in setOf(
        PassengerPhase.ARRIVED,
        PassengerPhase.CANCELLED,
        PassengerPhase.STOPPED,
    )

    private fun PassengerPhase.supportsLiveUpdate() = this in setOf(
        PassengerPhase.PRE_TRIP,
        PassengerPhase.APPROACHING_ORIGIN,
        PassengerPhase.BOARDING_SOON,
        PassengerPhase.ON_BOARD,
        PassengerPhase.APPROACHING_DESTINATION,
    )

    private data class NotificationContent(
        val title: String,
        val text: String,
        val subtext: String?,
        val expanded: String,
    )

    companion object {
        const val CHANNEL_TRACKING = "trip_tracking"
        const val CHANNEL_ALERTS = "trip_alerts"
        const val NOTIFICATION_ID = 5140
        const val EXTRA_TICKET_ID = "ticket_id"
        private val FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
    }
}
