package pt.cpcompanion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import pt.cpcompanion.notifications.TrackingNotificationFactory
import pt.cpcompanion.ui.MainViewModel
import pt.cpcompanion.ui.TrainTrackerApp

class MainActivity : ComponentActivity() {
    private val ticketToOpen = MutableStateFlow<String?>(null)
    private val sharedSmsText = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeIntent(intent)
        val container = (application as TrainTrackerApplication).container
        setContent {
            val viewModel: MainViewModel = viewModel(
                factory = MainViewModel.factory(application, container),
            )
            val deepLinkedTicketId by ticketToOpen.collectAsStateWithLifecycle()
            val sharedText by sharedSmsText.collectAsStateWithLifecycle()
            LaunchedEffect(deepLinkedTicketId) {
                deepLinkedTicketId?.let { ticketId ->
                    viewModel.openTicket(ticketId)
                    ticketToOpen.value = null
                }
            }
            LaunchedEffect(sharedText) {
                sharedText?.let { text ->
                    viewModel.navigate(pt.cpcompanion.ui.AppScreen.Tickets)
                    viewModel.importSmsText(text)
                    sharedSmsText.value = null
                }
            }
            TrainTrackerApp(viewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: Intent) {
        ticketToOpen.value = intent.getStringExtra(TrackingNotificationFactory.EXTRA_TICKET_ID)
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            sharedSmsText.value = intent.getStringExtra(Intent.EXTRA_TEXT)
        }
    }
}
