package pt.cpcompanion.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import pt.cpcompanion.TrainTrackerApplication
import pt.cpcompanion.tracking.TrainTrackingService

class UpcomingTripReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val ticketId = inputData.getString(KEY_TICKET_ID) ?: return Result.failure()
        val container = (applicationContext as TrainTrackerApplication).container
        val ticket = container.stores.ticket(ticketId) ?: return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.success()
        }
        if (ticket.originStationCode.isBlank() || ticket.destinationStationCode.isBlank()) return Result.success()
        TrainTrackingService.start(applicationContext, ticketId)
        return Result.success()
    }

    companion object {
        const val KEY_TICKET_ID = "ticket_id"
    }
}
