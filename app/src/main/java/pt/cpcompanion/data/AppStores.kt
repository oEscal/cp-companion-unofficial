package pt.cpcompanion.data

import android.content.Context
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import pt.cpcompanion.model.CachedCpFrontendConfig
import pt.cpcompanion.model.SmsImportSettings
import pt.cpcompanion.model.Ticket
import pt.cpcompanion.model.TrackingSnapshot

class AppStores(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val secure = SecureStorage(context)

    private val _cpFrontendConfig = MutableStateFlow(
        secure.get(KEY_CP_FRONTEND_CONFIG)?.let {
            runCatching { json.decodeFromString<CachedCpFrontendConfig>(it) }.getOrNull()
        },
    )
    val cpFrontendConfig: StateFlow<CachedCpFrontendConfig?> = _cpFrontendConfig.asStateFlow()

    private val _tickets = MutableStateFlow(
        secure.get(KEY_TICKETS)?.let {
            runCatching { json.decodeFromString(ListSerializer(Ticket.serializer()), it) }.getOrNull()
        } ?: emptyList(),
    )
    val tickets: StateFlow<List<Ticket>> = _tickets.asStateFlow()

    private val _smsImportSettings = MutableStateFlow(
        secure.get(KEY_SMS_IMPORT)?.let {
            runCatching { json.decodeFromString<SmsImportSettings>(it) }.getOrNull()
        } ?: SmsImportSettings(),
    )
    val smsImportSettings: StateFlow<SmsImportSettings> = _smsImportSettings.asStateFlow()

    private val _favoriteStationCodes = MutableStateFlow(
        secure.get(KEY_FAVORITE_STATIONS)?.split(',')?.map(String::trim)
            ?.filter(String::isNotBlank)?.toSet() ?: emptySet(),
    )
    val favoriteStationCodes: StateFlow<Set<String>> = _favoriteStationCodes.asStateFlow()

    private val _tracking = MutableStateFlow(
        secure.get(KEY_TRACKING)?.let { runCatching { json.decodeFromString<TrackingSnapshot>(it) }.getOrNull() },
    )
    val tracking: StateFlow<TrackingSnapshot?> = _tracking.asStateFlow()

    private val _activeTicketId = MutableStateFlow(secure.get(KEY_ACTIVE_TICKET))
    val activeTicketId: StateFlow<String?> = _activeTicketId.asStateFlow()

    init {
        // Remove settings from older builds. CP configuration is now obtained from
        // https://www.cp.pt/fe-config.json and is never entered by the user.
        secure.remove(KEY_LEGACY_API_SETTINGS)
    }

    fun saveCpFrontendConfig(config: CachedCpFrontendConfig) {
        secure.put(KEY_CP_FRONTEND_CONFIG, json.encodeToString(config))
        _cpFrontendConfig.value = config
    }

    fun clearCpFrontendConfig() {
        secure.remove(KEY_CP_FRONTEND_CONFIG)
        _cpFrontendConfig.value = null
    }

    fun saveSmsImportSettings(settings: SmsImportSettings) {
        secure.put(KEY_SMS_IMPORT, json.encodeToString(settings))
        _smsImportSettings.value = settings
    }

    fun toggleFavoriteStation(stationCode: String) {
        val updated = _favoriteStationCodes.value.toMutableSet().apply {
            if (!add(stationCode)) remove(stationCode)
        }.toSet()
        secure.put(KEY_FAVORITE_STATIONS, updated.sorted().joinToString(","))
        _favoriteStationCodes.value = updated
    }

    @Synchronized
    fun upsertTicket(ticket: Ticket): Ticket {
        val normalized = if (ticket.id.isBlank()) ticket.copy(id = UUID.randomUUID().toString()) else ticket
        val today = LocalDate.now()
        val updated = (_tickets.value.filterNot { it.id == normalized.id } + normalized)
            .sortedWith(
                compareBy<Ticket> {
                    val date = runCatching { LocalDate.parse(it.serviceDate) }.getOrNull()
                    if (date == null || date.isBefore(today)) 1 else 0
                }.thenBy {
                    val date = runCatching { LocalDate.parse(it.serviceDate) }.getOrNull()
                    if (date != null && date.isBefore(today)) -date.toEpochDay() else date?.toEpochDay() ?: Long.MAX_VALUE
                }.thenBy { it.scheduledDepartureEpochMillis ?: Long.MAX_VALUE },
            )
        secure.put(KEY_TICKETS, json.encodeToString(ListSerializer(Ticket.serializer()), updated))
        _tickets.value = updated
        return normalized
    }

    @Synchronized
    fun deleteTicket(ticketId: String) {
        val updated = _tickets.value.filterNot { it.id == ticketId }
        secure.put(KEY_TICKETS, json.encodeToString(ListSerializer(Ticket.serializer()), updated))
        _tickets.value = updated
        if (_activeTicketId.value == ticketId) clearTracking()
    }

    fun setActiveTicket(ticketId: String) {
        secure.put(KEY_ACTIVE_TICKET, ticketId)
        _activeTicketId.value = ticketId
    }

    fun saveTracking(snapshot: TrackingSnapshot) {
        secure.put(KEY_TRACKING, json.encodeToString(snapshot))
        _tracking.value = snapshot
    }

    fun clearTracking() {
        secure.remove(KEY_TRACKING)
        secure.remove(KEY_ACTIVE_TICKET)
        _tracking.value = null
        _activeTicketId.value = null
    }

    fun ticket(ticketId: String): Ticket? = _tickets.value.firstOrNull { it.id == ticketId }

    private companion object {
        const val KEY_CP_FRONTEND_CONFIG = "cp_frontend_config"
        const val KEY_LEGACY_API_SETTINGS = "api_settings"
        const val KEY_TICKETS = "tickets"
        const val KEY_SMS_IMPORT = "sms_import_settings"
        const val KEY_FAVORITE_STATIONS = "favorite_station_codes"
        const val KEY_TRACKING = "tracking"
        const val KEY_ACTIVE_TICKET = "active_ticket"
    }
}
