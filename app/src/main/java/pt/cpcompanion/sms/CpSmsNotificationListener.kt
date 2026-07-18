package pt.cpcompanion.sms

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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
import pt.cpcompanion.automation.TicketAutomationReconciler
import pt.cpcompanion.automation.TicketValidationCoordinator

class CpSmsNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processingFingerprints = LinkedHashSet<String>()

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
        val body = extractBody(notification) ?: return
        val sender = extractSender(notification).orEmpty()
        if (!isCpSender(sender) && !hasStrongCpTicketMarkers(body)) return

        scope.launch {
            val container = (application as TrainTrackerApplication).container
            container.stores.awaitReady()
            val fingerprint = container.stores.notificationFingerprint(sbn.packageName, sbn.key, body)
            synchronized(processingFingerprints) {
                if (container.stores.wasNotificationImportProcessed(fingerprint) ||
                    !processingFingerprints.add(fingerprint)
                ) return@launch
            }
            try {
                val result = container.smsTicketImporter.importNotificationText(
                    text = body,
                    stations = container.repository.cachedStations(),
                    notificationKey = sbn.key,
                    receivedAtEpochMillis = sbn.postTime,
                )
                if (result.tickets.isEmpty()) return@launch
                var changedCount = 0
                var firstChangedTicketId: String? = null
                result.tickets.forEach { ticket ->
                    val upsert = container.stores.upsertImportedTicket(ticket)
                    if (upsert.changed) {
                        changedCount += 1
                        if (firstChangedTicketId == null) firstChangedTicketId = upsert.ticket.id
                    }
                    if (upsert.requiresValidation) {
                        val outcome = TicketValidationCoordinator.validateAndSchedule(applicationContext, upsert.ticket)
                        if (outcome.shouldRetry) TicketAutomationReconciler.enqueue(applicationContext)
                    }
                }
                container.stores.markNotificationImportProcessed(fingerprint)
                firstChangedTicketId?.let { postImportNotification(changedCount, it) }
            } finally {
                synchronized(processingFingerprints) { processingFingerprints.remove(fingerprint) }
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
        val messageSender = lastMessage?.senderPerson?.name?.toString()
        return messageSender
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
    }

    private fun extractBody(notification: Notification): String? {
        val extras = notification.extras
        val messagingText = extractMessagingMessages(notification).lastOrNull()?.text?.toString()
        sanitizeBody(messagingText)?.let { return it }

        val direct = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        sanitizeBody(direct)?.let { return it }

        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.take(MAX_NOTIFICATION_TEXT_LINES)
            ?.joinToString(" ") { it.toString().take(MAX_NOTIFICATION_LINE_CHARS) }
        return sanitizeBody(lines)
    }

    private fun sanitizeBody(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_NOTIFICATION_BODY_CHARS }

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
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notificationManager = NotificationManagerCompat.from(this)
        if (!notificationManager.areNotificationsEnabled()) return
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
            .setContentTitle(resources.getQuantityString(R.plurals.notification_import_count, count, count))
            .setContentText(getString(R.string.notification_sms_imported_body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission or notification policy changed between the check and notify().
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.ticket_import_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.ticket_import_channel_description)
            },
        )
    }


    private fun hasStrongCpTicketMarkers(body: String): Boolean {
        val value = normalize(body)
        val hasTicket = value.contains("bilhete") || value.contains("reserva") || value.contains("ticket")
        val hasJourney = value.contains("comboio") && (value.contains("ida") || value.contains("volta") || value.contains("partida"))
        return hasTicket && hasJourney
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
        const val MAX_NOTIFICATION_BODY_CHARS = 64 * 1024
        const val MAX_NOTIFICATION_TEXT_LINES = 128
        const val MAX_NOTIFICATION_LINE_CHARS = 1_024
    }
}
