package pt.cpcompanion.watch

import android.content.Context
import android.content.Intent

interface WatchTransport {
    /** Returns true when the Android broadcast was issued, not when the watch displayed it. */
    fun send(settings: SmartBandSettings, payload: String): Boolean
}

object GadgetbridgeIntentFactory {
    const val ACTION_SEND_WATCH_APP_MESSAGE =
        "nodomain.freeyourgadget.gadgetbridge.command.SEND_WATCH_APP_MESSAGE"
    const val GADGETBRIDGE_PACKAGE = "nodomain.freeyourgadget.gadgetbridge"
    const val WATCH_APP_ID = "pt.cpcompanion.smartband10"

    fun create(settings: SmartBandSettings, payload: String): Intent? {
        val normalized = settings.normalized()
        if (!normalized.canSend) return null
        return Intent(ACTION_SEND_WATCH_APP_MESSAGE)
            .setPackage(GADGETBRIDGE_PACKAGE)
            .putExtra("device", normalized.deviceAddress)
            .putExtra("appId", WATCH_APP_ID)
            .putExtra("payload", payload)
    }
}

class GadgetbridgeTransport(private val context: Context) : WatchTransport {
    override fun send(settings: SmartBandSettings, payload: String): Boolean {
        val intent = GadgetbridgeIntentFactory.create(settings, payload) ?: return false
        return runCatching {
            context.sendBroadcast(intent)
            true
        }.getOrDefault(false)
    }
}
