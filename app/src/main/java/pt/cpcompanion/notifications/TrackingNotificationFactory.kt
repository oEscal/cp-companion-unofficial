package pt.cpcompanion.notifications

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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
import androidx.core.app.NotificationManagerCompat
import pt.cpcompanion.MainActivity
import pt.cpcompanion.R
import pt.cpcompanion.domain.StatusChipFormatter
import pt.cpcompanion.model.ExpectedTimeSource
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
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.alerts_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.alerts_channel_description)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 180, 100, 180)
            },
        )
    }

    fun build(snapshot: TrackingSnapshot): Notification =
    if (
        Build.VERSION.SDK_INT >= 36 &&
        snapshot.supportsProgressStyle()
    ) {
        buildProgressNotification(snapshot)
    } else {
        buildFallback(snapshot)
    }

    fun postImportantAlert(snapshot: TrackingSnapshot) {
        val platform = snapshot.platform?.let { " · ${context.getString(R.string.platform, it)}" }.orEmpty()
        val carriage = snapshot.carriage?.let { " · ${context.getString(R.string.carriage_value, it)}" }.orEmpty()
        val seat = snapshot.seat?.let { " · ${context.getString(R.string.seat_value, it)}" }.orEmpty()
        val (title, text) = when (snapshot.phase) {
            PassengerPhase.BOARDING_SOON ->
                context.getString(R.string.alert_train_almost_at, snapshot.trainNumber, snapshot.originName) to
                    "${context.getString(R.string.alert_boarding_shortly)}$platform"
            PassengerPhase.ON_BOARD ->
                context.getString(R.string.alert_train_reached_origin, snapshot.trainNumber, snapshot.originName) to
                    "${context.getString(R.string.alert_board_now)}$carriage$seat"
            PassengerPhase.APPROACHING_DESTINATION ->
                context.getString(R.string.alert_approaching_destination, snapshot.destinationName) to
                    context.getString(R.string.alert_arrives_soon, snapshot.trainNumber)
            PassengerPhase.ARRIVED ->
                context.getString(R.string.notification_arrived, snapshot.destinationName) to
                    context.getString(R.string.alert_train_arrived, snapshot.trainNumber)
            PassengerPhase.CANCELLED ->
                context.getString(R.string.notification_train_disrupted, snapshot.trainNumber) to
                    context.getString(R.string.alert_check_trip)
            else -> return
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(iconFor(snapshot.phase))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent(snapshot.ticketId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(longArrayOf(0, 180, 100, 180))
            .build()
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notificationManager = NotificationManagerCompat.from(context)
        if (!notificationManager.areNotificationsEnabled()) return

        try {
            notificationManager.notify(ALERT_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission or notification policy changed between the check and notify().
        }
    }

    private fun buildFallback(snapshot: TrackingSnapshot): Notification {
        val content = content(snapshot)
        val terminal = snapshot.phase.isTerminal()
        return NotificationCompat.Builder(context, CHANNEL_TRACKING)
            .setSmallIcon(iconFor(snapshot.phase))
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSubText(content.subtext)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.expanded))
            .setContentIntent(openIntent(snapshot.ticketId))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(!terminal)
            .setAutoCancel(terminal)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(snapshot.expectedEventEpochMillis ?: snapshot.lastSuccessfulFetchEpochMillis)
            .setShowWhen(snapshot.expectedEventEpochMillis != null)
            .apply {
                if (!terminal) {
                    setDeleteIntent(stopIntent(snapshot.ticketId))
                    addAction(R.drawable.ic_stop, context.getString(R.string.stop_and_disable), stopIntent(snapshot.ticketId))
                }
            }
            .build()
    }

    @RequiresApi(36)
    private fun buildProgressNotification(snapshot: TrackingSnapshot): Notification {
        val content = content(snapshot)
        val progressMax = snapshot.progressMax.coerceAtLeast(2)
        val progress = snapshot.progress.coerceIn(0, progressMax)
        val pointPositions = buildSet {
            add(1)
            snapshot.progressMarkers.forEach { marker -> add(marker.coerceIn(1, progressMax - 1)) }
            add(progressMax)
        }
        val style = Notification.ProgressStyle()
            .setStyledByProgress(true)
            .setProgress(progress)
            .setProgressTrackerIcon(Icon.createWithResource(context, iconFor(snapshot.phase)))
            .setProgressSegments(
                listOf(Notification.ProgressStyle.Segment(progressMax).setColor(Color.rgb(0, 108, 76))),
            )
            .setProgressPoints(
                pointPositions.sorted().map { position ->
                    val color = when (position) {
                        1 -> Color.rgb(0, 108, 76)
                        progressMax -> Color.rgb(0, 80, 170)
                        else -> Color.rgb(70, 100, 90)
                    }
                    Notification.ProgressStyle.Point(position).setColor(color)
                },
            )

        /*
        * Always express the app's promotion request when the journey is eligible.
        *
        * canPostPromotedNotifications() reports the current Android/user setting,
        * but it should not decide whether the request is included in the
        * notification itself.
        */
        val requestPromotion = snapshot.shouldRequestPromotion()

        val extras = Bundle().apply {
            putBoolean(
                Notification.EXTRA_REQUEST_PROMOTED_ONGOING,
                requestPromotion,
            )
        }
        return Notification.Builder(context, CHANNEL_TRACKING)
            .setSmallIcon(iconFor(snapshot.phase))
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSubText(content.subtext)
            .setStyle(style)
            .setContentIntent(openIntent(snapshot.ticketId))
            .setOnlyAlertOnce(true)
            .setOngoing(!snapshot.phase.isTerminal())
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setColorized(false)
            .addExtras(extras)
            .apply {
                if (!snapshot.phase.isTerminal()) {
                    setDeleteIntent(stopIntent(snapshot.ticketId))
                    addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(context, R.drawable.ic_stop),
                            context.getString(R.string.stop_and_disable),
                            stopIntent(snapshot.ticketId),
                        ).build(),
                    )
                }
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
            context.getString(R.string.promotion_allowed)
        } else {
            context.getString(R.string.promotion_unavailable)
        }
    }

    /**
     * Whether this journey phase should currently request Live Update promotion.
     */
    fun promotionRequested(
        snapshot: TrackingSnapshot,
    ): Boolean =
        Build.VERSION.SDK_INT >= 36 &&
            snapshot.shouldRequestPromotion()

    /**
     * Whether Android currently allows this app to publish promoted notifications.
     */
    fun canPostPromotion(): Boolean =
        Build.VERSION.SDK_INT >= 36 &&
            context.getSystemService(NotificationManager::class.java)
                .canPostPromotedNotifications()

    /**
     * Included in the service notification signature so that the notification is
     * reposted when promotion eligibility or Android's permission state changes.
     */
    fun promotionStateKey(
        snapshot: TrackingSnapshot,
    ): String {
        if (Build.VERSION.SDK_INT < 36) {
            return "unsupported"
        }

        val manager =
            context.getSystemService(NotificationManager::class.java)

        val requested = snapshot.shouldRequestPromotion()
        val allowed = manager.canPostPromotedNotifications()

        val promoted = runCatching {
            val flags = manager.activeNotifications
                .firstOrNull { statusBarNotification ->
                    statusBarNotification.id == NOTIFICATION_ID
                }
                ?.notification
                ?.flags
                ?: 0

            flags and Notification.FLAG_PROMOTED_ONGOING != 0
        }.getOrDefault(false)

        return "$requested|$allowed|$promoted"
    }

    /**
     * Checks whether the current tracking notification has actually been promoted
     * by Android, rather than merely requesting promotion.
     */
    @RequiresApi(36)
    fun isTrackingNotificationPromoted(): Boolean {
        val manager =
            context.getSystemService(NotificationManager::class.java)

        return runCatching {
            val flags = manager.activeNotifications
                .firstOrNull { statusBarNotification ->
                    statusBarNotification.id == NOTIFICATION_ID
                }
                ?.notification
                ?.flags
                ?: 0

            flags and Notification.FLAG_PROMOTED_ONGOING != 0
        }.getOrDefault(false)
    }

    private fun content(snapshot: TrackingSnapshot): NotificationContent {
        val expected = snapshot.expectedEventEpochMillis?.let { formatTime(it, snapshot.eventTimeZoneId) }
        val scheduled = snapshot.scheduledEventEpochMillis?.let { formatTime(it, snapshot.eventTimeZoneId) }
        val expectedLabel = context.getString(
            when (snapshot.expectedTimeSource) {
                ExpectedTimeSource.OBSERVED -> R.string.expected
                ExpectedTimeSource.CALCULATED_FROM_STOP_DELAY,
                ExpectedTimeSource.CALCULATED_FROM_TRAIN_DELAY -> R.string.estimated
                ExpectedTimeSource.SCHEDULED_ONLY -> R.string.scheduled
                ExpectedTimeSource.UNKNOWN -> R.string.expected
            },
        )
        val calculating = context.getString(R.string.calculating)
        val staleSuffix = if (snapshot.dataStale) " · ${context.getString(R.string.live_data_stale)}" else ""
        val delay = when {
            snapshot.delayMinutes == null -> context.getString(R.string.delay_not_reported)
            snapshot.delayMinutes > 0 -> context.getString(R.string.minutes_late, snapshot.delayMinutes)
            else -> context.getString(R.string.on_time)
        }
        val platform = snapshot.platform?.takeIf(String::isNotBlank)?.let { context.getString(R.string.platform, it) }
        val boarding = listOfNotNull(
            snapshot.carriage?.takeIf(String::isNotBlank)?.let { context.getString(R.string.carriage_value, it) },
            snapshot.seat?.takeIf(String::isNotBlank)?.let { context.getString(R.string.seat_value, it) },
        ).joinToString(" · ")
        val expectedOrScheduled = expected ?: scheduled ?: calculating

        return when (snapshot.phase) {
            PassengerPhase.BOARDING_SOON -> NotificationContent(
                title = context.getString(R.string.notification_train_arriving, snapshot.trainNumber),
                text = listOfNotNull(platform, boarding.takeIf(String::isNotBlank)).joinToString(" · ").ifBlank { snapshot.originName },
                subtext = snapshot.originName,
                expanded = listOfNotNull(
                    context.getString(R.string.expected_at, expectedLabel, expected ?: calculating) + staleSuffix,
                    scheduled?.let { context.getString(R.string.scheduled_time, it) },
                    delay,
                    platform,
                    boarding.takeIf(String::isNotBlank),
                    snapshot.message.takeIf { snapshot.dataStale },
                ).joinToString("\n"),
            )
            PassengerPhase.APPROACHING_ORIGIN, PassengerPhase.PRE_TRIP -> NotificationContent(
                title = context.getString(
                    R.string.notification_train_approaching,
                    snapshot.trainNumber,
                    snapshot.originName,
                ),
                text = "$expectedLabel $expectedOrScheduled · $delay$staleSuffix",
                subtext = platform,
                expanded = buildString {
                    append(snapshot.originName).append(" → ").append(snapshot.destinationName).append('\n')
                    append(context.getString(R.string.expected_line, expected ?: calculating))
                    scheduled?.let { append(" · ").append(context.getString(R.string.scheduled_inline, it)) }
                    append('\n').append(delay)
                    platform?.let { append(" · ").append(it) }
                    snapshot.message?.takeIf { snapshot.dataStale }?.let { append('\n').append(it) }
                },
            )
            PassengerPhase.ON_BOARD, PassengerPhase.APPROACHING_DESTINATION -> NotificationContent(
                title = context.getString(
                    R.string.notification_train_to,
                    snapshot.trainNumber,
                    snapshot.destinationName,
                ),
                text = listOfNotNull(
                    "$expectedLabel $expectedOrScheduled$staleSuffix",
                    delay,
                    boarding.takeIf(String::isNotBlank),
                ).joinToString(" · "),
                subtext = snapshot.nextStopName?.let { context.getString(R.string.next_stop, it) },
                expanded = buildString {
                    append(context.getString(R.string.destination_line, snapshot.destinationName)).append('\n')
                    append(context.getString(R.string.expected_line, expected ?: calculating))
                    scheduled?.let { append(" · ").append(context.getString(R.string.scheduled_inline, it)) }
                    append('\n').append(delay)
                    boarding.takeIf(String::isNotBlank)?.let { append('\n').append(it) }
                    snapshot.nextStopName?.let { append('\n').append(context.getString(R.string.next_stop_line, it)) }
                    snapshot.message?.takeIf { snapshot.dataStale }?.let { append('\n').append(it) }
                },
            )
            PassengerPhase.CANCELLED -> NotificationContent(
                title = context.getString(R.string.notification_train_disrupted, snapshot.trainNumber),
                text = context.getString(R.string.passenger_endpoint_unavailable),
                subtext = snapshot.destinationName,
                expanded = snapshot.message ?: context.getString(R.string.open_trip_latest),
            )
            PassengerPhase.DATA_UNAVAILABLE -> NotificationContent(
                title = context.getString(R.string.notification_tracking_train, snapshot.trainNumber),
                text = context.getString(R.string.live_data_unavailable),
                subtext = context.getString(
                    R.string.last_live_data,
                    formatTime(snapshot.lastSuccessfulFetchEpochMillis, snapshot.eventTimeZoneId),
                ),
                expanded = context.getString(R.string.retaining_last_trip),
            )
            PassengerPhase.ARRIVED -> NotificationContent(
                title = context.getString(R.string.notification_arrived, snapshot.destinationName),
                text = context.getString(R.string.tracking_completed, snapshot.trainNumber),
                subtext = null,
                expanded = context.getString(R.string.active_session_ended),
            )
            PassengerPhase.STOPPED -> NotificationContent(
                title = context.getString(R.string.notification_tracking_stopped),
                text = context.getString(R.string.train_value, snapshot.trainNumber),
                subtext = null,
                expanded = context.getString(R.string.open_app_another_session),
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

    private fun formatTime(epochMillis: Long, zoneId: String): String = FORMATTER.format(
        Instant.ofEpochMilli(epochMillis).atZone(runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.of("Europe/Lisbon"))),
    )

    private fun PassengerPhase.isTerminal() = this in setOf(
        PassengerPhase.ARRIVED,
        PassengerPhase.CANCELLED,
        PassengerPhase.STOPPED,
    )

    private fun TrackingSnapshot.supportsProgressStyle(): Boolean =
        when (phase) {
            PassengerPhase.PRE_TRIP,
            PassengerPhase.APPROACHING_ORIGIN,
            PassengerPhase.BOARDING_SOON,
            PassengerPhase.ON_BOARD,
            PassengerPhase.APPROACHING_DESTINATION,
            PassengerPhase.ARRIVED -> true

            PassengerPhase.CANCELLED,
            PassengerPhase.DATA_UNAVAILABLE,
            PassengerPhase.STOPPED -> false
        }

    private fun TrackingSnapshot.shouldRequestPromotion(): Boolean {
        val now = System.currentTimeMillis()

        return when (phase) {
            PassengerPhase.BOARDING_SOON,
            PassengerPhase.ON_BOARD,
            PassengerPhase.APPROACHING_DESTINATION -> true

            PassengerPhase.APPROACHING_ORIGIN -> {
                val expectedArrival = expectedEventEpochMillis
                expectedArrival != null &&
                    expectedArrival - now in
                        0L..(15L * 60L * 1000L)
            }

            else -> false
        }
    }

    private data class NotificationContent(
        val title: String,
        val text: String,
        val subtext: String?,
        val expanded: String,
    )

    companion object {
        const val CHANNEL_TRACKING = "trip_tracking"
        const val CHANNEL_ALERTS = "trip_alerts_v2"
        const val NOTIFICATION_ID = 5140
        const val ALERT_NOTIFICATION_ID = 5141
        const val EXTRA_TICKET_ID = "ticket_id"
        private val FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
    }
}
