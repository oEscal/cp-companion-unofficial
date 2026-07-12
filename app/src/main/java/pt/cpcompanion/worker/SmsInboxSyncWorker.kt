package pt.cpcompanion.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import pt.cpcompanion.TrainTrackerApplication
import pt.cpcompanion.sms.SmsImportCoordinator

/** Quiet fallback for the single app when the user explicitly opts into inbox import. */
class SmsInboxSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as TrainTrackerApplication).container
        container.stores.awaitReady()
        if (!container.stores.smsImportSettings.value.automaticSmsImportEnabled) return Result.success()
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) return Result.success()

        return runCatching {
            SmsImportCoordinator.scanInbox(
                context = applicationContext,
                stations = container.repository.cachedStations(),
                requireAutomaticOptIn = true,
            )
        }.fold(
            onSuccess = { Result.success() },
            onFailure = {
                SmsImportCoordinator.recordFailedScan(applicationContext)
                Result.retry()
            },
        )
    }
}
