package pt.cpcompanion.watch

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** User-controlled, opt-in configuration for the experimental Smart Band integration. */
@Serializable
data class SmartBandSettings(
    val enabled: Boolean = false,
    val deviceAddress: String? = null,
) {
    val canSend: Boolean
        get() = enabled && isValidDeviceAddress(deviceAddress)

    fun normalized(): SmartBandSettings = copy(
        deviceAddress = deviceAddress
            ?.trim()
            ?.uppercase()
            ?.takeIf(String::isNotEmpty),
    )

    companion object {
        private val DEVICE_ADDRESS = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

        fun isValidDeviceAddress(value: String?): Boolean =
            value != null && DEVICE_ADDRESS.matches(value.trim().uppercase())
    }
}

@Serializable
data class WatchJourneySnapshot(
    val id: String,
    val train: String,
    val origin: String,
    val destination: String,
    val status: String,
    val nextStop: String? = null,
    val countdownTarget: String? = null,
    val countdownEpochMs: Long? = null,
    val scheduledDepartureEpochMs: Long? = null,
    val estimatedDepartureEpochMs: Long? = null,
    val scheduledArrivalEpochMs: Long? = null,
    val estimatedArrivalEpochMs: Long? = null,
    val scheduledDepartureLocal: String? = null,
    val estimatedDepartureLocal: String? = null,
    val scheduledArrivalLocal: String? = null,
    val estimatedArrivalLocal: String? = null,
    val delayMinutes: Int? = null,
    val platform: String? = null,
    val timeZoneId: String = DEFAULT_TIME_ZONE,
    val timeSource: String = TIME_SOURCE_UNKNOWN,
    val lastSyncEpochMs: Long,
    val dataStale: Boolean = false,
) {
    companion object {
        const val DEFAULT_TIME_ZONE = "Europe/Lisbon"
        const val TIME_SOURCE_OBSERVED = "observed"
        const val TIME_SOURCE_ESTIMATED = "estimated"
        const val TIME_SOURCE_SCHEDULED = "scheduled"
        const val TIME_SOURCE_UNKNOWN = "unknown"
    }
}

@Serializable
data class WatchMessage(
    val version: Int,
    val type: String,
    val sequence: Long,
    val sentAtEpochMs: Long,
    val journey: WatchJourneySnapshot? = null,
    val journeyId: String? = null,
)

object WatchProtocol {
    const val VERSION = 1
    const val JOURNEY_SNAPSHOT = "journey_snapshot"
    const val JOURNEY_CLEARED = "journey_cleared"

    const val STATUS_WAITING = "waiting"
    const val STATUS_UPCOMING = "upcoming_departure"
    const val STATUS_IN_PROGRESS = "in_progress"
    const val STATUS_APPROACHING = "approaching_destination"
    const val STATUS_COMPLETED = "completed"
    const val STATUS_CANCELLED = "cancelled"
    const val STATUS_OUTDATED = "outdated"
    const val STATUS_STOPPED = "stopped"

    const val COUNTDOWN_ORIGIN = "origin"
    const val COUNTDOWN_NEXT_STOP = "next_stop"
    const val COUNTDOWN_DESTINATION = "destination"
}

object WatchMessageCodec {
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    fun encode(message: WatchMessage): String = json.encodeToString(message)

    fun encodeJourney(journey: WatchJourneySnapshot): String = json.encodeToString(journey)
}
