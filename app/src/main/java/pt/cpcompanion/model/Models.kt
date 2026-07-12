package pt.cpcompanion.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Station(
    val code: String,
    val name: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val region: String? = null,
    val railwayCodes: List<String> = emptyList(),
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
    val isCancelled: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

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
)

@Serializable
data class StationBoardEntry(
    val trainNumber: String,
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
    val autoScanOnAppOpen: Boolean = false,
    val lastScanAtEpochMillis: Long? = null,
    /** The newest inbox SMS that produced a saved CP ticket on the previous scan. */
    val lastImportedInboxMessageId: String? = null,
)

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
    val scheduledDestinationEpochMillis: Long? = null,
    val expectedDestinationEpochMillis: Long? = null,
    val delayMinutes: Int? = null,
    val platform: String? = null,
    val carriage: String? = null,
    val seat: String? = null,
    val progress: Int = 0,
    val progressMax: Int = 1000,
    val message: String? = null,
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
    val consecutiveFailures: Int = 0,
)

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
