package pt.cpcompanion.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TicketActivationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ACTIVATE) return
        val ticketId = intent.getStringExtra(EXTRA_TICKET_ID) ?: return
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                TicketActivationLauncher.activate(context, ticketId, "exact/inexact alarm")
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    companion object {
        const val ACTION_ACTIVATE = "pt.cpcompanion.action.ACTIVATE_TICKET"
        const val EXTRA_TICKET_ID = "ticket_id"
    }
}
