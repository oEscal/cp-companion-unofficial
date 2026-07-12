package pt.cpcompanion.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SmsInboxSyncScheduler {
    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<SmsInboxSyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "cp-sms-inbox-sync",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
