package pt.cpcompanion.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        val resolvedTicket = runCatching {
            withContext(Dispatchers.IO) {
                var stations = container.repository.cachedStations()
                var resolved = container.smsTicketImporter.resolveTicketStations(ticket, stations)
                if (resolved.originStationCode.isBlank() || resolved.destinationStationCode.isBlank()) {
                    // A background worker can run before the UI has refreshed the
                    // station catalogue. Refresh once here instead of dropping the
                    // automatic tracking session.
                    stations = container.repository.refreshStations()
                    resolved = container.smsTicketImporter.resolveTicketStations(ticket, stations)
                }
                resolved
            }
        }.getOrElse { return Result.retry() }
        if (resolvedTicket.originStationCode.isBlank() || resolvedTicket.destinationStationCode.isBlank()) {
            val departure = ticket.scheduledDepartureEpochMillis
            return if (departure != null && departure > Instant.now().toEpochMilli()) Result.retry() else Result.success()
        }
        if (resolvedTicket != ticket) container.stores.upsertTicket(resolvedTicket)
        TrainTrackingService.start(applicationContext, resolvedTicket.id)
        return Result.success()
    }

    companion object {
        const val KEY_TICKET_ID = "ticket_id"
    }
}
