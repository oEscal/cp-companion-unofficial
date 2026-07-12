package pt.cpcompanion

import android.content.Context
import pt.cpcompanion.data.AppStores
import pt.cpcompanion.data.TrainRepository
import pt.cpcompanion.domain.TripStateResolver
import pt.cpcompanion.network.CpApiClient
import pt.cpcompanion.notifications.TrackingNotificationFactory
import pt.cpcompanion.sms.SmsTicketImporter

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val stores = AppStores(appContext)
    val api = CpApiClient(stores)
    val repository = TrainRepository(appContext, api)
    val resolver = TripStateResolver()
    val notifications = TrackingNotificationFactory(appContext)
    val smsTicketImporter = SmsTicketImporter(appContext)
}
