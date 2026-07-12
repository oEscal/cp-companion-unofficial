package pt.cpcompanion.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Enqueues lightweight repair after platform events that clear or invalidate alarms. */
class TicketAutomationSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TicketAutomationReconciler.enqueue(context)
    }
}
