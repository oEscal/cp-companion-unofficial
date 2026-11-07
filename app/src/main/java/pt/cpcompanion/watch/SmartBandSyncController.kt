package pt.cpcompanion.watch

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import pt.cpcompanion.data.AppStores

/** Observes existing state and isolates optional watch synchronization from tracking. */
class SmartBandSyncController(
    private val stores: AppStores,
    private val transport: WatchTransport,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    private val adapter = WatchSnapshotAdapter()

    private var lastJourneyFingerprint: String? = null
    private var lastJourneyId: String? = null
    private var lastSequence = 0L

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            combine(
                stores.smartBandSettings,
                stores.activeTicketId,
                stores.trackingSnapshots,
            ) { settings, activeTicketId, snapshots ->
                SyncInput(settings, activeTicketId?.let(snapshots::get))
            }.collect { input ->
                runCatching { synchronize(input) }
            }
        }
    }

    private fun synchronize(input: SyncInput) {
        val settings = input.settings.normalized()
        if (!settings.canSend) {
            // Disabling the integration must not send even a clearing message.
            lastJourneyFingerprint = null
            lastJourneyId = null
            return
        }

        val snapshot = input.snapshot
        if (snapshot == null) {
            val previousJourneyId = lastJourneyId ?: return
            val message = WatchMessage(
                version = WatchProtocol.VERSION,
                type = WatchProtocol.JOURNEY_CLEARED,
                sequence = nextSequence(),
                sentAtEpochMs = nowEpochMillis(),
                journeyId = previousJourneyId,
            )
            if (transport.send(settings, WatchMessageCodec.encode(message))) {
                lastJourneyFingerprint = null
                lastJourneyId = null
            }
            return
        }

        val journey = adapter.toJourney(snapshot)
        val fingerprint = WatchMessageCodec.encodeJourney(journey)
        if (fingerprint == lastJourneyFingerprint && journey.id == lastJourneyId) return

        val message = WatchMessage(
            version = WatchProtocol.VERSION,
            type = WatchProtocol.JOURNEY_SNAPSHOT,
            sequence = nextSequence(),
            sentAtEpochMs = nowEpochMillis(),
            journey = journey,
        )
        if (transport.send(settings, WatchMessageCodec.encode(message))) {
            lastJourneyFingerprint = fingerprint
            lastJourneyId = journey.id
        }
    }

    private fun nextSequence(): Long {
        val candidate = nowEpochMillis()
        lastSequence = maxOf(candidate, lastSequence + 1L)
        return lastSequence
    }

    private data class SyncInput(
        val settings: SmartBandSettings,
        val snapshot: pt.cpcompanion.model.TrackingSnapshot?,
    )
}

fun SmartBandSyncController(context: Context, stores: AppStores): SmartBandSyncController =
    SmartBandSyncController(stores, GadgetbridgeTransport(context.applicationContext))
