package pt.cpcompanion.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import pt.cpcompanion.TrainTrackerApplication

object SmsInboxSyncScheduler {
    private const val UNIQUE_WORK = "cp-sms-inbox-sync"
    private const val RECONCILE_WORK = "cp-sms-inbox-schedule-reconcile"

    fun reconcileAsync(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            RECONCILE_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SmsInboxScheduleReconcileWorker>().build(),
        )
    }

    fun syncWithSettings(context: Context) {
        val enabled = (context.applicationContext as TrainTrackerApplication)
            .container.stores.smsImportSettings.value.automaticSmsImportEnabled
        if (enabled) ensureScheduled(context) else cancel(context)
    }

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<SmsInboxSyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
    }
}
