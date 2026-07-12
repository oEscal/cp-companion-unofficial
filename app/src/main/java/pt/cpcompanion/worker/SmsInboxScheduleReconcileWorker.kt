package pt.cpcompanion.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import pt.cpcompanion.TrainTrackerApplication

/** Reconciles periodic SMS work off the application main thread. */
class SmsInboxScheduleReconcileWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        (applicationContext as TrainTrackerApplication).container.stores.awaitReady()
        SmsInboxSyncScheduler.syncWithSettings(applicationContext)
    }.fold(
        onSuccess = { Result.success() },
        onFailure = { Result.retry() },
    )
}
