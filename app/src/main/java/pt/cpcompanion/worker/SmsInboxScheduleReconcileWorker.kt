package pt.cpcompanion.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import pt.cpcompanion.TrainTrackerApplication

/** Reconciles periodic SMS work off the application main thread. */
class SmsInboxScheduleReconcileWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        (applicationContext as TrainTrackerApplication).container.stores.awaitReady()
        SmsInboxSyncScheduler.syncWithSettings(applicationContext)
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        Result.retry()
    }
}
