package pt.cpcompanion.sms

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pt.cpcompanion.TrainTrackerApplication
import pt.cpcompanion.automation.TicketAutomationReconciler
import pt.cpcompanion.automation.TicketValidationCoordinator
import pt.cpcompanion.model.Station

/** Serializes manual and WorkManager inbox scans and atomically advances the shared cursor. */
object SmsImportCoordinator {
    private val mutex = Mutex()

    suspend fun scanInbox(
        context: Context,
        stations: List<Station>,
        requireAutomaticOptIn: Boolean,
    ): SmsInboxScanOutcome = mutex.withLock {
        val container = (context.applicationContext as TrainTrackerApplication).container
        container.stores.awaitReady()
        val settingsAtStart = container.stores.smsImportSettings.value
        if (requireAutomaticOptIn && !settingsAtStart.automaticSmsImportEnabled) {
            return@withLock SmsInboxScanOutcome(skipped = true)
        }

        val result = container.smsTicketImporter.importInbox(
            stations = stations,
            stopAtMessageId = settingsAtStart.lastInboxCheckpointMessageId
                ?: settingsAtStart.lastImportedInboxMessageId,
            stopAtReceivedAtEpochMillis = settingsAtStart.lastInboxCheckpointReceivedAtEpochMillis,
        )
        var changedCount = 0
        result.tickets.forEach { ticket ->
            val upsert = container.stores.upsertImportedTicket(ticket)
            if (upsert.changed) changedCount += 1
            if (upsert.requiresValidation) {
                val validation = TicketValidationCoordinator.validateAndSchedule(context, upsert.ticket)
                if (validation.shouldRetry) TicketAutomationReconciler.enqueue(context)
            }
        }
        container.stores.updateSmsImportSettings { latest ->
            latest.copy(
                lastScanAtEpochMillis = System.currentTimeMillis(),
                lastImportedInboxMessageId = result.newestImportedInboxMessageId
                    ?: latest.lastImportedInboxMessageId,
                lastInboxCheckpointMessageId = result.newestScannedInboxMessageId
                    ?: latest.lastInboxCheckpointMessageId,
                lastInboxCheckpointReceivedAtEpochMillis = result.newestScannedInboxReceivedAtEpochMillis
                    ?: latest.lastInboxCheckpointReceivedAtEpochMillis,
            )
        }
        SmsInboxScanOutcome(result = result, changedCount = changedCount)
    }

    suspend fun recordFailedScan(context: Context) = mutex.withLock {
        val container = (context.applicationContext as TrainTrackerApplication).container
        container.stores.awaitReady()
        container.stores.updateSmsImportSettings { latest ->
            latest.copy(lastScanAtEpochMillis = System.currentTimeMillis())
        }
    }
}

data class SmsInboxScanOutcome(
    val result: SmsImportResult? = null,
    val changedCount: Int = 0,
    val skipped: Boolean = false,
)
