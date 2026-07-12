package pt.cpcompanion.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pt.cpcompanion.TrainTrackerApplication

/** Quiet fallback for devices where a CP SMS does not produce an accessible notification event. */
class SmsInboxSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) return Result.success()

        val container = (applicationContext as TrainTrackerApplication).container
        return runCatching {
            val settings = container.stores.smsImportSettings.value
            val result = withContext(Dispatchers.IO) {
                container.smsTicketImporter.importInbox(
                    stations = container.repository.cachedStations(),
                    stopAtMessageId = settings.lastInboxCheckpointMessageId
                        ?: settings.lastImportedInboxMessageId,
                )
            }
            result.tickets.forEach { ticket ->
                container.stores.upsertTicket(ticket)
                TicketReminderScheduler.schedule(applicationContext, ticket)
            }
            result.newestImportedInboxMessageId?.let { checkpoint ->
                container.stores.saveSmsImportSettings(
                    container.stores.smsImportSettings.value.copy(lastImportedInboxMessageId = checkpoint),
                )
            }
            result.newestScannedInboxMessageId?.let { checkpoint ->
                container.stores.saveSmsImportSettings(
                    container.stores.smsImportSettings.value.copy(
                        lastInboxCheckpointMessageId = checkpoint,
                    ),
                )
            }
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }
}
