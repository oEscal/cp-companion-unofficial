package pt.cpcompanion.sms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.Normalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import pt.cpcompanion.MainActivity
import pt.cpcompanion.R
import pt.cpcompanion.TrainTrackerApplication
import pt.cpcompanion.notifications.TrackingNotificationFactory
import pt.cpcompanion.worker.TicketReminderScheduler

class CpSmsNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeNotifications?.forEach(::onNotificationPosted)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val notification = sbn.notification
        if (notification.category != null && notification.category != Notification.CATEGORY_MESSAGE) return
        val sender = extractSender(notification) ?: return
        if (!isCpSender(sender)) return
        val body = extractBody(notification) ?: return

        scope.launch {
            val container = (application as TrainTrackerApplication).container
            val result = container.smsTicketImporter.importNotificationText(
                text = body,
                stations = container.repository.cachedStations(),
                notificationKey = sbn.key,
                receivedAtEpochMillis = sbn.postTime,
            )
            if (result.tickets.isEmpty()) return@launch
            result.tickets.forEach { ticket ->
                container.stores.upsertTicket(ticket)
                TicketReminderScheduler.schedule(applicationContext, ticket)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun extractSender(notification: Notification): String? {
        val extras = notification.extras
        val lastMessage = extractMessagingMessages(notification).lastOrNull()
        @Suppress("DEPRECATION")
        val messageSender = if (Build.VERSION.SDK_INT >= 28) {
            lastMessage?.senderPerson?.name?.toString()
        } else {
            lastMessage?.sender?.toString()
        }
        return messageSender
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
    }

    private fun extractBody(notification: Notification): String? {
        val extras = notification.extras
        val messagingText = extractMessagingMessages(notification).lastOrNull()?.text?.toString()
        if (!messagingText.isNullOrBlank()) return messagingText
        val direct = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        if (!direct.isNullOrBlank()) return direct
        return extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString(" ") { it.toString() }
            ?.takeIf(String::isNotBlank)
    }

    private fun extractMessagingMessages(notification: Notification): List<Notification.MessagingStyle.Message> {
        val rawBundles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
        } else {
            @Suppress("DEPRECATION")
            notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        } ?: return emptyList()

        val bundles = rawBundles.mapNotNull { it as? Bundle }.toTypedArray()
        return Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)
    }

    private fun postImportNotification(count: Int, ticketId: String) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(TrackingNotificationFactory.EXTRA_TICKET_ID, ticketId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            ticketId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ticket)
            .setContentTitle(if (count == 1) "CP ticket imported" else "$count CP ticket legs imported")
            .setContentText("Open CP Companion to review the trip details and start tracking.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Ticket imports",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Confirmation after a CP ticket SMS is imported"
            },
        )
    }

    private fun isCpSender(value: String): Boolean {
        val normalized = normalize(value)
        return normalized == "cp" ||
            normalized == "cpinfo" ||
            normalized == "cpcomboios" ||
            normalized == "comboiosdeportugal" ||
            normalized.startsWith("cpcomboios")
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "")

    private companion object {
        const val CHANNEL_ID = "sms_ticket_imports"
        const val NOTIFICATION_ID = 4_201
    }
}
