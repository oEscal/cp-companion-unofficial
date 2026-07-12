package pt.cpcompanion

import android.app.Application
import pt.cpcompanion.automation.TicketAutomationReconciler
import pt.cpcompanion.worker.SmsInboxSyncScheduler

class TrainTrackerApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container.notifications.createChannels()
        SmsInboxSyncScheduler.reconcileAsync(this)
        TicketAutomationReconciler.enqueue(this)
    }
}
