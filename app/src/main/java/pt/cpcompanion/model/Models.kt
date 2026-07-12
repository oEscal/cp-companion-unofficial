package pt.cpcompanion.model

import java.text.Normalizer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class Station(
    val code: String,
    val name: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val region: String? = null,
    /** Persisted IANA zone inferred when the station catalogue is refreshed. */
    val timeZoneId: String? = null,
    val railwayCodes: List<String> = emptyList(),
    @Transient
    val searchText: String = normalizeSearchText(listOf(code, name, region).joinToString(" ")),
)

@Serializable
data class StationRef(
    val code: String,
    val name: String,
)

@Serializable
data class TrainServiceEntry(
    val key: String,
    val trainNumber: String,
    val serviceCode: String? = null,
    val serviceName: String? = null,
    val origin: StationRef? = null,
    val destination: StationRef? = null,
    @Transient
    val searchText: String = normalizeSearchText(
        listOfNotNull(trainNumber, serviceCode, serviceName, origin?.name, origin?.code, destination?.name, destination?.code)
            .joinToString(" "),
    ),
)

@Serializable
data class TrainStop(
    val station: StationRef,
    val scheduledArrival: String? = null,
    val scheduledDeparture: String? = null,
    val expectedArrival: String? = null,
    val expectedDeparture: String? = null,
    val platform: String? = null,
    val delayMinutes: Int? = null,
    /** Upstream suppression metadata. A suppressed intermediate stop is not a cancelled journey. */
    val suppressionCode: String? = null,
    val suppressionDesignation: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val isSuppressed: Boolean get() = suppressionCode != null || suppressionDesignation != null
    @Deprecated("Use isSuppressed and endpoint-aware journey semantics")
    val isCancelled: Boolean get() = isSuppressed
}

@Serializable
data class TrainTrip(
    val trainNumber: String,
    val serviceDate: String,
    val serviceCode: String? = null,
    val serviceName: String? = null,
    val status: String? = null,
    val overallDelayMinutes: Int? = null,
    val lastReportedStationCode: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val duration: String? = null,
    val hasDisruptions: Boolean = false,
    val messages: List<String> = emptyList(),
    val stops: List<TrainStop> = emptyList(),
    val fetchedAtEpochMillis: Long,
    val dataStale: Boolean = false,
    val cooldownUntilEpochMillis: Long? = null,
)

@Serializable
data class StationBoardEntry(
    val trainNumber: String,
    val serviceDate: String,
    val serviceCode: String? = null,
    val serviceName: String? = null,
    val origin: StationRef? = null,
    val destination: StationRef? = null,
    val scheduledArrival: String? = null,
    val scheduledDeparture: String? = null,
    val expectedArrival: String? = null,
    val expectedDeparture: String? = null,
    val platform: String? = null,
    val delayMinutes: Int? = null,
    val isCancelled: Boolean = false,
)

@Serializable
data class StationDetails(
    val code: String,
    val name: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val trainLine: String? = null,
    val mobilityAccess: String? = null,
    val address: String? = null,
    val services: List<String> = emptyList(),
)

@Serializable
enum class TicketSource {
    MANUAL,
    SMS_INBOX,
    SMS_NOTIFICATION,
    SHARED_SMS,
}

@Serializable
enum class TicketDirection {
    OUTBOUND,
    RETURN,
}

@Serializable
data class SmsImportSettings(
    /** Kept on the old serialized key so upgrades preserve the user's explicit opt-in. */
    @SerialName("autoScanOnAppOpen")
    val automaticSmsImportEnabled: Boolean = false,
    val lastScanAtEpochMillis: Long? = null,
    /** The newest inbox SMS that produced a saved CP ticket on the previous scan. */
    val lastImportedInboxMessageId: String? = null,
    /** Cursor checkpoint so old non-ticket SMS messages are not re-read. */
    val lastInboxCheckpointMessageId: String? = null,
    val lastInboxCheckpointReceivedAtEpochMillis: Long? = null,
)


@Serializable
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

@Serializable
enum class TicketAutomationState {
    NEEDS_VALIDATION,
    SCHEDULED,
    STARTING,
    TRACKING,
    DISABLED,
    COMPLETED,
    BLOCKED_NOTIFICATION_PERMISSION,
    BLOCKED_EXACT_ALARM_PERMISSION,
    CONFLICT_WITH_OTHER_TRIP,
    FAILED,
}

@Serializable
enum class TicketActivationMethod {
    NONE,
    EXACT_ALARM,
    INEXACT_ALARM,
    WORK_MANAGER_FALLBACK,
    IMMEDIATE,
}

@Serializable
enum class ExpectedTimeSource {
    OBSERVED,
    CALCULATED_FROM_STOP_DELAY,
    CALCULATED_FROM_TRAIN_DELAY,
    SCHEDULED_ONLY,
    UNKNOWN,
}

@Serializable
enum class TrackingFailureCategory {
    NONE,
    NETWORK_UNAVAILABLE,
    TIMEOUT,
    RATE_LIMITED,
    AUTHORIZATION_OR_CONFIGURATION,
    TRIP_NOT_FOUND,
    TICKET_TRIP_MISMATCH,
    MALFORMED_RESPONSE,
    NOTIFICATION_PERMISSION,
    FOREGROUND_SERVICE_BLOCKED,
    UNKNOWN_TRANSIENT,
    PERMANENT_INVALID_TICKET,
}

@Serializable
data class Ticket(
    val id: String,
    val trainNumber: String,
    val serviceLabel: String? = null,
    val serviceDate: String,
    val originStationCode: String,
    val destinationStationCode: String,
    val originName: String? = null,
    val destinationName: String? = null,
    val scheduledDepartureEpochMillis: Long? = null,
    val scheduledArrivalEpochMillis: Long? = null,
    /** Volatile fields from 0.4.x are decoded for migration only and are no longer updated. */
    @Deprecated("Live values are stored in TrackingSnapshot")
    val expectedDepartureEpochMillis: Long? = null,
    @Deprecated("Live values are stored in TrackingSnapshot")
    val expectedArrivalEpochMillis: Long? = null,
    @Deprecated("Live values are stored in TrackingSnapshot")
    val liveDelayMinutes: Int? = null,
    val automaticTrackingEnabled: Boolean = true,
    val activationEpochMillis: Long? = null,
    val automationState: TicketAutomationState = TicketAutomationState.NEEDS_VALIDATION,
    val activationMethod: TicketActivationMethod = TicketActivationMethod.NONE,
    val schedulingFingerprint: String? = null,
    val automationMessage: String? = null,
    val lastAutomationAttemptEpochMillis: Long? = null,
    val validationFailureCount: Int = 0,
    val completedAtEpochMillis: Long? = null,
    val carriage: String? = null,
    val seat: String? = null,
    val reference: String? = null,
    val source: TicketSource = TicketSource.MANUAL,
    val direction: TicketDirection? = null,
    val sourceMessageId: String? = null,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
)

@Serializable
enum class PassengerPhase {
    PRE_TRIP,
    APPROACHING_ORIGIN,
    BOARDING_SOON,
    ON_BOARD,
    APPROACHING_DESTINATION,
    ARRIVED,
    CANCELLED,
    DATA_UNAVAILABLE,
    STOPPED,
}

@Serializable
data class TrackingSnapshot(
    val ticketId: String,
    val trainNumber: String,
    val serviceLabel: String? = null,
    val serviceDate: String,
    val phase: PassengerPhase,
    val originName: String,
    val destinationName: String,
    val nextStopName: String? = null,
    val lastReportedStationName: String? = null,
    val scheduledEventEpochMillis: Long? = null,
    val expectedEventEpochMillis: Long? = null,
    val scheduledOriginEpochMillis: Long? = null,
    val expectedOriginEpochMillis: Long? = null,
    /** ETA at the boarding station, distinct from its ETD. */
    val expectedOriginArrivalEpochMillis: Long? = null,
    val scheduledDestinationEpochMillis: Long? = null,
    val expectedDestinationEpochMillis: Long? = null,
    val delayMinutes: Int? = null,
    val platform: String? = null,
    val carriage: String? = null,
    val seat: String? = null,
    val progress: Int = 0,
    val progressMax: Int = 1000,
    /** Calling-point markers, expressed on the 0..progressMax notification line. */
    val progressMarkers: List<Int> = emptyList(),
    val message: String? = null,
    /** Timestamp of the last successful CP response represented by this snapshot. */
    val lastSuccessfulFetchEpochMillis: Long = System.currentTimeMillis(),
    /** Timestamp of the latest fetch attempt, successful or not. */
    val lastAttemptEpochMillis: Long = System.currentTimeMillis(),
    /** Backward-compatible alias used by existing notification/UI code. */
    val updatedAtEpochMillis: Long = lastSuccessfulFetchEpochMillis,
    val consecutiveFailures: Int = 0,
    val dataStale: Boolean = false,
    val expectedTimeSource: ExpectedTimeSource = ExpectedTimeSource.UNKNOWN,
    val failureCategory: TrackingFailureCategory = TrackingFailureCategory.NONE,
    val retryAtEpochMillis: Long? = null,
    val currentStopIndex: Int? = null,
    val passengerOriginIndex: Int? = null,
    val passengerDestinationIndex: Int? = null,
    /** Time zone used to render this passenger segment consistently, even when the device is abroad. */
    val eventTimeZoneId: String = "Europe/Lisbon",
    /** Updated by the running service and used for diagnostics/recovery decisions. */
    val serviceHeartbeatEpochMillis: Long = lastAttemptEpochMillis,
)

private val SEARCH_COMBINING_MARKS = Regex("\\p{M}+")
private val SEARCH_WHITESPACE = Regex("\\s+")

internal fun normalizeSearchText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(SEARCH_COMBINING_MARKS, "")
    .lowercase()
    .replace(SEARCH_WHITESPACE, " ")
    .trim()

@Serializable
data class CpFrontendConfig(
    val xcck: String,
    val xccs: String,
    val travelApiUrl: String,
    val stationsApiUrl: String,
    val travelApiKey: String,
    val stationsApiKey: String,
)

@Serializable
data class CachedCpFrontendConfig(
    val config: CpFrontendConfig,
    val fetchedAtEpochMillis: Long,
)

// Observed upstream DTOs. Keep provider spelling at the boundary.
@Serializable
data class StationDto(
    val code: String,
    val designation: String,
    val latitude: String? = null,
    val longitude: String? = null,
    val region: String? = null,
    val railways: List<String> = emptyList(),
)

@Serializable
data class RefDto(
    val code: String? = null,
    val designation: String? = null,
)

@Serializable
data class TrainCatalogDto(
    val trainNumber: Long,
    val trainService: RefDto? = null,
    val trainOrigin: RefDto? = null,
    val trainDestination: RefDto? = null,
)

@Serializable
data class TrainStopDto(
    val station: RefDto,
    val arrival: String? = null,
    val departure: String? = null,
    val platform: String? = null,
    val latitude: String? = null,
    val longitude: String? = null,
    val delay: Int? = null,
    val supression: SuppressionDto? = null,
    @SerialName("ETA") val eta: String? = null,
    @SerialName("ETD") val etd: String? = null,
)

@Serializable
data class SuppressionDto(
    val code: String? = null,
    val designation: String? = null,
)

@Serializable
data class TrainTripDto(
    val trainNumber: Long,
    val serviceCode: RefDto? = null,
    val lastStationCode: String? = null,
    val delay: Int? = null,
    val latitude: String? = null,
    val longitude: String? = null,
    val status: String? = null,
    val hasDisruptions: Boolean = false,
    val duration: String? = null,
    val messages: List<MessageDto> = emptyList(),
    val trainStops: List<TrainStopDto> = emptyList(),
)

@Serializable
data class MessageDto(
    val text: String? = null,
    val code: String? = null,
)

@Serializable
data class StationBoardResponseDto(
    val stationStops: List<StationBoardStopDto> = emptyList(),
    val messages: List<MessageDto> = emptyList(),
)

@Serializable
data class StationBoardStopDto(
    val trainNumber: Long,
    val trainService: RefDto? = null,
    val trainOrigin: RefDto? = null,
    val trainDestination: RefDto? = null,
    val arrivalTime: String? = null,
    val departureTime: String? = null,
    val platform: String? = null,
    val delay: Int? = null,
    val supression: SuppressionDto? = null,
    @SerialName("ETA") val eta: String? = null,
    @SerialName("ETD") val etd: String? = null,
)

@Serializable
data class StationDetailsDto(
    val code: String,
    val designation: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val trainLine: String? = null,
    val mobilityAccess: String? = null,
    val address: String? = null,
    val services: List<String> = emptyList(),
)
