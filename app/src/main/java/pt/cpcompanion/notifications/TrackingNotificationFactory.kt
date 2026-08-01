package pt.cpcompanion.notifications

import android.Manifest
import android.annotation.SuppressLint
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
        val platform = snapshot.platform
            ?.takeIf(String::isNotBlank)
            ?.let { context.getString(R.string.platform, it) }
        val carriage = snapshot.carriage
            ?.takeIf(String::isNotBlank)
            ?.let { context.getString(R.string.carriage_value, it) }
        val seat = snapshot.seat
            ?.takeIf(String::isNotBlank)
            ?.let { context.getString(R.string.seat_value, it) }
        val boardingDetails = listOfNotNull(platform, carriage, seat).joinToString(" · ")
        val (title, text) = when (snapshot.phase) {
            PassengerPhase.BOARDING_SOON ->
                context.getString(R.string.alert_train_almost_at, snapshot.trainNumber, snapshot.originName) to
                    listOfNotNull(
                        context.getString(R.string.alert_boarding_shortly),
                        boardingDetails.takeIf(String::isNotBlank),
                    ).joinToString(" · ")
            PassengerPhase.ON_BOARD ->
                context.getString(R.string.alert_train_reached_origin, snapshot.trainNumber, snapshot.originName) to
                    listOfNotNull(
                        context.getString(R.string.alert_board_now),
                        boardingDetails.takeIf(String::isNotBlank),
                    ).joinToString(" · ")
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
            notificationManager.notify(alertNotificationIdFor(snapshot.ticketId), notification)
        } catch (_: SecurityException) {
            // Permission or notification policy changed between the check and notify().
        }
    }

    private fun buildFallback(snapshot: TrackingSnapshot): Notification {
        val content = content(snapshot)
        val terminal = snapshot.phase.isTerminal()
        val eventEpochMillis = snapshot.expectedEventEpochMillis
            ?: snapshot.scheduledEventEpochMillis
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
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setWhen(eventEpochMillis ?: snapshot.lastSuccessfulFetchEpochMillis)
            .setShowWhen(eventEpochMillis != null)
            .apply {
                if (!terminal) {
                    setDeleteIntent(stopIntent(snapshot.ticketId))
                    addAction(R.drawable.ic_stop, context.getString(R.string.stop_and_disable), stopIntent(snapshot.ticketId))
                }
            }
            .build()
    }

    @RequiresApi(36)
    @SuppressLint("InlinedApi")
    private fun buildProgressNotification(snapshot: TrackingSnapshot): Notification {
        val content = content(snapshot)
        val progressMax = snapshot.progressMax.coerceAtLeast(2)
        val progress = snapshot.progress.coerceIn(0, progressMax)
        val pointPositions = visiblePointPositions(
            markers = snapshot.progressMarkers,
            progressMax = progressMax,
        )
        val useAllStopsWorkaround = pointPositions.size > MAX_RELIABLY_RENDERED_NATIVE_POINTS
        val styleProgress = if (useAllStopsWorkaround) progressMax else progress
        val progressPoints = pointPositions.mapIndexed { index, position ->
            Notification.ProgressStyle.Point(position)
                // Keep point IDs in a namespace separate from segment IDs. IDs are
                // used by System UI to correlate elements across notification updates.
                .setId(PROGRESS_POINT_ID_BASE + index)
                .setColor(
                    when {
                        position == progressMax && progress >= progressMax -> PROGRESS_DESTINATION_REACHED_COLOR
                        position == progressMax -> PROGRESS_DESTINATION_COLOR
                        position <= progress -> PROGRESS_COMPLETED_STOP_COLOR
                        else -> PROGRESS_REMAINING_STOP_COLOR
                    },
                )
        }.toMutableList().apply {
            if (useAllStopsWorkaround && progress in 1 until progressMax) {
                /*
                 * Several Android 16 System UI builds clip progress points after the
                 * tracker position when a route contains many points. Setting the style's
                 * progress to the maximum makes System UI lay out the complete point list.
                 * The actual train position is retained as this highlighted point and by
                 * the completed/remaining segment-color boundary below.
                 */
                add(
                    Notification.ProgressStyle.Point(progress)
                        .setId(PROGRESS_CURRENT_POSITION_POINT_ID)
                        .setColor(PROGRESS_CURRENT_POSITION_COLOR),
                )
            }
        }

        val style = Notification.ProgressStyle()
            .setStyledByProgress(false)
            .setProgress(styleProgress)
            .setProgressSegments(
                progressSegments(
                    progress = progress,
                    progressMax = progressMax,
                ),
            )
            .setProgressPoints(progressPoints)
            .apply {
                if (!useAllStopsWorkaround) {
                    setProgressTrackerIcon(
                        Icon.createWithResource(context, iconFor(snapshot.phase)),
                    )
                }
            }

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
            .setVisibility(Notification.VISIBILITY_PRIVATE)
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
                val shortCriticalText = StatusChipFormatter.format(snapshot)
                if (shortCriticalText != null) {
                    // Prefer the app-defined chip value and avoid giving System UI
                    // two competing status-chip representations. The notification
                    // itself still keeps its event timestamp visible below.
                    setShortCriticalText(shortCriticalText)
                }

                val eventEpochMillis = snapshot.expectedEventEpochMillis
                    ?: snapshot.scheduledEventEpochMillis
                if (eventEpochMillis != null && eventEpochMillis > System.currentTimeMillis()) {
                    setWhen(eventEpochMillis)
                    setShowWhen(true)
                } else {
                    setShowWhen(false)
                }
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
                    statusBarNotification.id == notificationIdFor(snapshot.ticketId)
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
    fun isTrackingNotificationPromoted(ticketId: String): Boolean {
        val manager =
            context.getSystemService(NotificationManager::class.java)

        return runCatching {
            val flags = manager.activeNotifications
                .firstOrNull { statusBarNotification ->
                    statusBarNotification.id == notificationIdFor(ticketId)
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
        val boardingPlatform = platform.takeIf { snapshot.phase == PassengerPhase.ON_BOARD }
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
                text = listOfNotNull(
                    "$expectedLabel $expectedOrScheduled$staleSuffix",
                    delay,
                    platform,
                    boarding.takeIf(String::isNotBlank),
                ).joinToString(" · "),
                subtext = platform,
                expanded = buildString {
                    append(snapshot.originName).append(" → ").append(snapshot.destinationName).append('\n')
                    append(context.getString(R.string.expected_line, expected ?: calculating))
                    scheduled?.let { append(" · ").append(context.getString(R.string.scheduled_inline, it)) }
                    append('\n').append(delay)
                    platform?.let { append(" · ").append(it) }
                    boarding.takeIf(String::isNotBlank)?.let { append('\n').append(it) }
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
                    boardingPlatform,
                    boarding.takeIf(String::isNotBlank),
                ).joinToString(" · "),
                subtext = snapshot.nextStopName?.let { context.getString(R.string.next_stop, it) },
                expanded = buildString {
                    append(context.getString(R.string.destination_line, snapshot.destinationName)).append('\n')
                    append(context.getString(R.string.expected_line, expected ?: calculating))
                    scheduled?.let { append(" · ").append(context.getString(R.string.scheduled_inline, it)) }
                    append('\n').append(delay)
                    boardingPlatform?.let { append(" · ").append(it) }
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
    @RequiresApi(36)
    private fun progressSegments(
        progress: Int,
        progressMax: Int,
    ): List<Notification.ProgressStyle.Segment> = buildList {
        /*
         * Explicit colors preserve the completed/remaining distinction even when
         * the long-route workaround reports full style progress to System UI.
         */
        if (progress > 0) {
            add(
                Notification.ProgressStyle.Segment(progress)
                    .setId(PROGRESS_COMPLETED_SEGMENT_ID)
                    .setColor(PROGRESS_COMPLETED_LINE_COLOR),
            )
        }
        if (progress < progressMax) {
            add(
                Notification.ProgressStyle.Segment(progressMax - progress)
                    .setId(PROGRESS_REMAINING_SEGMENT_ID)
                    .setColor(PROGRESS_REMAINING_LINE_COLOR),
            )
        }
    }


    /**
     * Returns origin, every intermediate calling point, and destination.
     *
     * Positions 1 and progressMax are reserved for the passenger origin and
     * destination. Intermediate positions are kept strictly increasing so no stop is
     * lost when the API returns equal or very closely spaced times.
     */
    private fun visiblePointPositions(
        markers: List<Int>,
        progressMax: Int,
    ): List<Int> {
        if (progressMax <= 2) return listOf(1, progressMax).distinct()

        val sortedMarkers = markers.sorted()
        val lastIntermediatePosition = progressMax - 1
        var previous = 1
        val normalizedMarkers = sortedMarkers.mapIndexed { index, rawPosition ->
            val remaining = sortedMarkers.lastIndex - index
            val position = rawPosition
                .coerceAtLeast(previous + 1)
                .coerceAtMost(lastIntermediatePosition - remaining)
            previous = position
            position
        }

        return buildList(normalizedMarkers.size + 2) {
            add(1)
            addAll(normalizedMarkers)
            add(progressMax)
        }
    }

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

    private fun TrackingSnapshot.shouldRequestPromotion(): Boolean =
        when (phase) {
            /*
             * Tracking is activated at T-60 minutes. Request promotion for the whole
             * active passenger journey so the status chip can appear immediately,
             * rather than only during the final 15 minutes before boarding.
             */
            PassengerPhase.PRE_TRIP,
            PassengerPhase.APPROACHING_ORIGIN,
            PassengerPhase.BOARDING_SOON,
            PassengerPhase.ON_BOARD,
            PassengerPhase.APPROACHING_DESTINATION -> true

            PassengerPhase.ARRIVED,
            PassengerPhase.CANCELLED,
            PassengerPhase.DATA_UNAVAILABLE,
            PassengerPhase.STOPPED -> false
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
        /** Legacy IDs retained for source compatibility. */
        const val NOTIFICATION_ID = 5140
        const val ALERT_NOTIFICATION_ID = 5141
        const val EXTRA_TICKET_ID = "ticket_id"
        private const val TRACKING_NOTIFICATION_NAMESPACE = 0x10000000
        private const val ALERT_NOTIFICATION_NAMESPACE = 0x20000000
        private const val NOTIFICATION_ID_HASH_MASK = 0x0fffffff
        private const val MAX_RELIABLY_RENDERED_NATIVE_POINTS = 4
        private const val PROGRESS_COMPLETED_SEGMENT_ID = 10_000
        private const val PROGRESS_REMAINING_SEGMENT_ID = 10_001
        private const val PROGRESS_POINT_ID_BASE = 20_000
        private const val PROGRESS_CURRENT_POSITION_POINT_ID = 30_000
        private val PROGRESS_COMPLETED_LINE_COLOR = Color.rgb(0, 80, 170)
        private val PROGRESS_REMAINING_LINE_COLOR = Color.rgb(120, 145, 175)
        private val PROGRESS_COMPLETED_STOP_COLOR = Color.rgb(0, 65, 145)
        private val PROGRESS_REMAINING_STOP_COLOR = Color.rgb(95, 120, 150)
        private val PROGRESS_CURRENT_POSITION_COLOR = Color.rgb(220, 75, 45)
        private val PROGRESS_DESTINATION_COLOR = Color.rgb(0, 80, 170)
        private val PROGRESS_DESTINATION_REACHED_COLOR = Color.rgb(0, 110, 75)

        fun notificationIdFor(ticketId: String): Int =
            TRACKING_NOTIFICATION_NAMESPACE or (ticketId.hashCode() and NOTIFICATION_ID_HASH_MASK)

        fun alertNotificationIdFor(ticketId: String): Int =
            ALERT_NOTIFICATION_NAMESPACE or
                ((ticketId.hashCode() xor 0x41E47) and NOTIFICATION_ID_HASH_MASK)

        private val FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
    }
}
