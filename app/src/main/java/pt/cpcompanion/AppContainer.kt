package pt.cpcompanion

import android.content.Context
import pt.cpcompanion.data.AppStores
import pt.cpcompanion.data.TrainRepository
import pt.cpcompanion.domain.TripStateResolver
import pt.cpcompanion.network.CpApiClient
import pt.cpcompanion.network.CpRequestCoordinator
import pt.cpcompanion.notifications.TrackingNotificationFactory
import pt.cpcompanion.sms.SmsTicketImporter

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    // Keep components lazy so Application.onCreate can create channels and enqueue recovery work
    // without decrypting all user state or loading the Android Keystore on the main thread.
    val notifications by lazy { TrackingNotificationFactory(appContext) }
    val stores by lazy { AppStores(appContext) }
    val requestCoordinator by lazy { CpRequestCoordinator() }
    val api by lazy { CpApiClient(stores, requestCoordinator) }
    val repository by lazy { TrainRepository(appContext, api) }
    val resolver by lazy { TripStateResolver() }
    val smsTicketImporter by lazy { SmsTicketImporter(appContext) }
}
