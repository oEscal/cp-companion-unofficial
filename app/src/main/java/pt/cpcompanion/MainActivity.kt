package pt.cpcompanion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID
import pt.cpcompanion.notifications.TrackingNotificationFactory
import pt.cpcompanion.ui.AppScreen
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
                if (sharedText != null) {
                    viewModel.navigate(AppScreen.Tickets)
                }
            }
            TrainTrackerApp(viewModel)
            sharedText?.let { text ->
                AlertDialog(
                    onDismissRequest = { sharedSmsText.value = null },
                    title = { Text(stringResource(R.string.confirm_shared_ticket_import)) },
                    text = { Text(stringResource(R.string.confirm_shared_ticket_import_body)) },
                    confirmButton = {
                        Button(
                            onClick = {
                                sharedSmsText.value = null
                                viewModel.importSmsText(text)
                            },
                        ) {
                            Text(stringResource(R.string.import_action))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { sharedSmsText.value = null }) {
                            Text(stringResource(R.string.cancel))
                        }
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: Intent) {
        ticketToOpen.value = (
            intent.getStringExtra(TrackingNotificationFactory.EXTRA_TICKET_ID)
            ?: intent.data
                ?.takeIf { it.scheme == "cpcompanion" && it.host == "trip" }
                ?.getQueryParameter("ticketId")
            )?.takeIf(::isValidTicketId)
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            sharedSmsText.value = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= MAX_SHARED_TEXT_CHARS }
        }
    }

    private fun isValidTicketId(value: String): Boolean =
        value.length <= MAX_TICKET_ID_CHARS && runCatching { UUID.fromString(value) }.isSuccess

    private companion object {
        const val MAX_TICKET_ID_CHARS = 64
        const val MAX_SHARED_TEXT_CHARS = 64 * 1024
    }
}
