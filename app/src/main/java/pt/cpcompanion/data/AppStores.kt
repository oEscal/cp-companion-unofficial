package pt.cpcompanion.data

import android.content.Context
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import pt.cpcompanion.data.db.CpDatabase
import pt.cpcompanion.data.db.TicketEntity
import pt.cpcompanion.model.CachedCpFrontendConfig
import pt.cpcompanion.model.SmsImportSettings
import pt.cpcompanion.model.ThemeMode
import pt.cpcompanion.model.Ticket
import pt.cpcompanion.model.TicketActivationMethod
import pt.cpcompanion.model.TicketAutomationState
import pt.cpcompanion.model.TrackingSnapshot
import pt.cpcompanion.model.isClearlyPastForAutomation
import pt.cpcompanion.model.isClearlyPastByTimeOnly

/** Result of merging an SMS/notification ticket into stable persisted ticket data. */
data class ImportedTicketUpsertResult(
    val ticket: Ticket,
    val changed: Boolean,
    val requiresValidation: Boolean,
)

/**
 * Persistence facade. Tickets are stored as individual AES-GCM encrypted Room rows, while small
 * non-sensitive preferences use DataStore. Foreground-session recovery values remain encrypted in
 * the Keystore-backed store because they must be immediately available before Room is opened.
 */
class AppStores(context: Context) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val secure = SecureStorage(appContext)
    private val preferences = AppPreferences(appContext)
    private val ticketDao = CpDatabase.get(appContext).ticketDao()
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val _cpFrontendConfig = MutableStateFlow(
        secure.get(KEY_CP_FRONTEND_CONFIG)?.let {
            runCatching { json.decodeFromString<CachedCpFrontendConfig>(it) }.getOrNull()
        },
    )
    val cpFrontendConfig: StateFlow<CachedCpFrontendConfig?> = _cpFrontendConfig.asStateFlow()

    private val _tickets = MutableStateFlow<List<Ticket>>(emptyList())
    val tickets: StateFlow<List<Ticket>> = _tickets.asStateFlow()

    private val _persistenceError = MutableStateFlow<String?>(null)
    val persistenceError: StateFlow<String?> = _persistenceError.asStateFlow()

    private val _smsImportSettings = MutableStateFlow(SmsImportSettings())
    val smsImportSettings: StateFlow<SmsImportSettings> = _smsImportSettings.asStateFlow()

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _favoriteStationCodes = MutableStateFlow<Set<String>>(emptySet())
    val favoriteStationCodes: StateFlow<Set<String>> = _favoriteStationCodes.asStateFlow()

    private val initialTrackingSnapshots: Map<String, TrackingSnapshot> =
        secure.get(KEY_TRACKING_SESSIONS)?.let { encoded ->
            runCatching {
                json.decodeFromString(ListSerializer(TrackingSnapshot.serializer()), encoded)
                    .associateBy(TrackingSnapshot::ticketId)
            }.getOrNull()
        } ?: secure.get(KEY_TRACKING)?.let { encoded ->
            runCatching { json.decodeFromString<TrackingSnapshot>(encoded) }
                .getOrNull()
                ?.let { mapOf(it.ticketId to it) }
        }.orEmpty()

    private val initialActiveTicketIds: LinkedHashSet<String> = linkedSetOf<String>().apply {
        secure.get(KEY_ACTIVE_TICKETS)
            ?.lineSequence()
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.forEach(::add)
        secure.get(KEY_ACTIVE_TICKET)?.takeIf(String::isNotBlank)?.let(::add)
        addAll(initialTrackingSnapshots.keys)
    }

    private val _trackingSnapshots = MutableStateFlow(initialTrackingSnapshots)
    val trackingSnapshots: StateFlow<Map<String, TrackingSnapshot>> = _trackingSnapshots.asStateFlow()

    private val _activeTicketIds = MutableStateFlow<Set<String>>(initialActiveTicketIds)
    val activeTicketIds: StateFlow<Set<String>> = _activeTicketIds.asStateFlow()

    private val _activeTicketId = MutableStateFlow(initialActiveTicketIds.firstOrNull())
    val activeTicketId: StateFlow<String?> = _activeTicketId.asStateFlow()

    private val _tracking = MutableStateFlow(
        _activeTicketId.value?.let(initialTrackingSnapshots::get)
            ?: initialTrackingSnapshots.values.firstOrNull(),
    )
    /** Primary/oldest active session retained for existing single-session UI surfaces. */
    val tracking: StateFlow<TrackingSnapshot?> = _tracking.asStateFlow()
    private val lastTrackingPersistEpochMillis = initialTrackingSnapshots
        .mapValues { (_, snapshot) -> snapshot.lastAttemptEpochMillis }
        .toMutableMap()

    private val processedNotificationFingerprints = LinkedHashSet<String>()
    private val ready = CompletableDeferred<Unit>()

    init {
        persistenceScope.launch {
            try {
                loadPersistentState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _persistenceError.value = "Local data could not be loaded: ${error.javaClass.simpleName}"
            } finally {
                ready.complete(Unit)
            }
        }
    }

    suspend fun awaitReady() {
        ready.await()
    }

    private suspend fun loadPersistentState() {
        val loadedTickets = loadTicketsAndMigrate()
        val loadedSms = preferenceOrLegacy(KEY_SMS_IMPORT, KEY_SMS_IMPORT)?.let {
            runCatching { json.decodeFromString<SmsImportSettings>(it) }.getOrNull()
        } ?: SmsImportSettings()
        val loadedTheme = preferenceOrLegacy(KEY_THEME_MODE, KEY_THEME_MODE)?.let { value ->
            runCatching { ThemeMode.valueOf(value) }.getOrNull()
        } ?: ThemeMode.SYSTEM
        val loadedFavorites = preferenceOrLegacy(KEY_FAVORITE_STATIONS, KEY_FAVORITE_STATIONS)
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.toSet()
            ?: emptySet()
        val loadedFingerprints = preferenceOrLegacy(
            KEY_NOTIFICATION_IMPORT_FINGERPRINTS,
            KEY_NOTIFICATION_IMPORT_FINGERPRINTS,
        ).orEmpty().lineSequence().map(String::trim).filter(String::isNotBlank).toList()

        synchronized(this) {
            _tickets.value = loadedTickets
            _smsImportSettings.value = loadedSms
            _themeMode.value = loadedTheme
            _favoriteStationCodes.value = loadedFavorites
            processedNotificationFingerprints.clear()
            processedNotificationFingerprints.addAll(loadedFingerprints.takeLast(MAX_NOTIFICATION_FINGERPRINTS))
        }
        if (secure.contains(KEY_LEGACY_API_SETTINGS)) secure.remove(KEY_LEGACY_API_SETTINGS)
    }

    fun saveCpFrontendConfig(config: CachedCpFrontendConfig) {
        secure.put(KEY_CP_FRONTEND_CONFIG, json.encodeToString(config))
        _cpFrontendConfig.value = config
    }

    fun clearCpFrontendConfig() {
        secure.remove(KEY_CP_FRONTEND_CONFIG)
        _cpFrontendConfig.value = null
    }

    @Synchronized
    fun saveSmsImportSettings(settings: SmsImportSettings) {
        if (settings == _smsImportSettings.value) return
        _smsImportSettings.value = settings
        preferences.writeString(KEY_SMS_IMPORT, json.encodeToString(settings))
    }

    /** Atomically merge scan cursors with the latest user preference state. */
    @Synchronized
    fun updateSmsImportSettings(transform: (SmsImportSettings) -> SmsImportSettings): SmsImportSettings {
        val updated = transform(_smsImportSettings.value)
        saveSmsImportSettings(updated)
        return updated
    }

    fun saveThemeMode(mode: ThemeMode) {
        if (mode == _themeMode.value) return
        _themeMode.value = mode
        preferences.writeString(KEY_THEME_MODE, mode.name)
    }

    fun toggleFavoriteStation(stationCode: String) {
        val updated = _favoriteStationCodes.value.toMutableSet().apply {
            if (!add(stationCode)) remove(stationCode)
        }.toSet()
        _favoriteStationCodes.value = updated
        preferences.writeString(KEY_FAVORITE_STATIONS, updated.sorted().joinToString(","))
    }

    @Synchronized
    fun upsertTicket(ticket: Ticket): Ticket {
        val normalized = normalizeTicket(
            if (ticket.id.isBlank()) ticket.copy(id = UUID.randomUUID().toString()) else ticket,
        )
        val previous = _tickets.value.firstOrNull { it.id == normalized.id }
        if (previous == normalized) return normalized
        _tickets.value = sortTickets(_tickets.value.filterNot { it.id == normalized.id } + normalized)
        persistTicket(normalized)
        return normalized
    }

    /**
     * Merge a repeated SMS/notification import without destroying validation, scheduling, or an
     * active tracking state. Validation is requested only when passenger-segment fields changed.
     */
    @Synchronized
    fun upsertImportedTicket(imported: Ticket): ImportedTicketUpsertResult {
        val incoming = normalizeTicket(imported)
        val existing = ticket(incoming.id)
        if (existing == null) {
            val isPast = incoming.isClearlyPastForAutomation()
            val saved = upsertTicket(
                incoming.copy(
                    automaticTrackingEnabled = !isPast,
                    automationState = if (isPast) TicketAutomationState.COMPLETED else TicketAutomationState.NEEDS_VALIDATION,
                    automationMessage = if (isPast) "Past passenger segment" else "Validating imported passenger segment",
                    validationFailureCount = 0,
                    completedAtEpochMillis = if (isPast) System.currentTimeMillis() else null,
                ),
            )
            return ImportedTicketUpsertResult(
                saved,
                changed = true,
                requiresValidation = !isPast,
            )
        }

        val merged = existing.copy(
            trainNumber = incoming.trainNumber,
            serviceLabel = incoming.serviceLabel ?: existing.serviceLabel,
            serviceDate = incoming.serviceDate,
            originStationCode = incoming.originStationCode.ifBlank { existing.originStationCode },
            destinationStationCode = incoming.destinationStationCode.ifBlank { existing.destinationStationCode },
            originName = incoming.originName ?: existing.originName,
            destinationName = incoming.destinationName ?: existing.destinationName,
            scheduledDepartureEpochMillis = incoming.scheduledDepartureEpochMillis
                ?: existing.scheduledDepartureEpochMillis,
            scheduledArrivalEpochMillis = incoming.scheduledArrivalEpochMillis
                ?: existing.scheduledArrivalEpochMillis,
            carriage = incoming.carriage ?: existing.carriage,
            seat = incoming.seat ?: existing.seat,
            reference = incoming.reference ?: existing.reference,
            source = incoming.source,
            direction = incoming.direction ?: existing.direction,
            sourceMessageId = incoming.sourceMessageId ?: existing.sourceMessageId,
            createdAtEpochMillis = minOf(existing.createdAtEpochMillis, incoming.createdAtEpochMillis),
        )
        val materialChanged = importedTicketFingerprint(existing) != importedTicketFingerprint(merged)
        if (!materialChanged) return ImportedTicketUpsertResult(existing, changed = false, requiresValidation = false)

        val normalizedMerged = normalizeTicket(merged)
        val shouldValidate = normalizedMerged.automaticTrackingEnabled && !normalizedMerged.isClearlyPastForAutomation()
        val saved = upsertTicket(
            normalizedMerged.copy(
                automationState = if (shouldValidate) TicketAutomationState.NEEDS_VALIDATION else normalizedMerged.automationState,
                activationEpochMillis = if (shouldValidate) null else normalizedMerged.activationEpochMillis,
                activationMethod = if (shouldValidate) TicketActivationMethod.NONE else normalizedMerged.activationMethod,
                schedulingFingerprint = if (shouldValidate) null else normalizedMerged.schedulingFingerprint,
                automationMessage = if (shouldValidate) {
                    "Imported ticket details changed; validating again"
                } else {
                    normalizedMerged.automationMessage ?: "Imported past ticket updated"
                },
                lastAutomationAttemptEpochMillis = if (shouldValidate) null else normalizedMerged.lastAutomationAttemptEpochMillis,
                validationFailureCount = if (shouldValidate) 0 else normalizedMerged.validationFailureCount,
                completedAtEpochMillis = if (shouldValidate) null else normalizedMerged.completedAtEpochMillis,
            ),
        )
        return ImportedTicketUpsertResult(saved, changed = true, requiresValidation = shouldValidate)
    }

    @Synchronized
    fun updateTicket(ticketId: String, transform: (Ticket) -> Ticket): Ticket? {
        val current = ticket(ticketId) ?: return null
        return upsertTicket(transform(current))
    }

    fun updateAutomation(
        ticketId: String,
        state: TicketAutomationState,
        activationEpochMillis: Long? = null,
        method: TicketActivationMethod? = null,
        fingerprint: String? = null,
        message: String? = null,
        attemptedAtEpochMillis: Long? = null,
        clearAttemptedAtEpochMillis: Boolean = false,
        completedAtEpochMillis: Long? = null,
        validationFailureCount: Int? = null,
    ): Ticket? = updateTicket(ticketId) { ticket ->
        ticket.copy(
            automationState = state,
            activationEpochMillis = activationEpochMillis ?: ticket.activationEpochMillis,
            activationMethod = method ?: ticket.activationMethod,
            schedulingFingerprint = fingerprint ?: ticket.schedulingFingerprint,
            automationMessage = message,
            lastAutomationAttemptEpochMillis = when {
                clearAttemptedAtEpochMillis -> null
                attemptedAtEpochMillis != null -> attemptedAtEpochMillis
                else -> ticket.lastAutomationAttemptEpochMillis
            },
            validationFailureCount = validationFailureCount ?: ticket.validationFailureCount,
            completedAtEpochMillis = completedAtEpochMillis ?: ticket.completedAtEpochMillis,
        )
    }

    @Synchronized
    fun deleteTicket(ticketId: String) {
        val updated = _tickets.value.filterNot { it.id == ticketId }
        if (updated.size == _tickets.value.size) return
        _tickets.value = updated
        persistenceScope.launch { ticketDao.delete(ticketId) }
        if (ticketId in _activeTicketIds.value) clearTracking(ticketId)
    }

    /** Register a concurrent tracking session. The oldest session remains the primary UI session. */
    @Synchronized
    fun claimActiveTicket(ticketId: String): Boolean {
        if (ticketId in _activeTicketIds.value) return true
        val updated = LinkedHashSet(_activeTicketIds.value).apply { add(ticketId) }
        persistActiveTicketIds(updated)
        _activeTicketIds.value = updated
        if (_activeTicketId.value == null) {
            _activeTicketId.value = ticketId
            secure.putDurable(KEY_ACTIVE_TICKET, ticketId)
        }
        return true
    }

    @Synchronized
    fun saveTracking(snapshot: TrackingSnapshot) {
        val previous = _trackingSnapshots.value[snapshot.ticketId]
        if (previous == snapshot) return
        val now = System.currentTimeMillis()
        val meaningfulTransition = previous == null ||
            previous.phase != snapshot.phase ||
            previous.dataStale != snapshot.dataStale ||
            previous.failureCategory != snapshot.failureCategory ||
            previous.retryAtEpochMillis != snapshot.retryAtEpochMillis
        val lastPersist = lastTrackingPersistEpochMillis[snapshot.ticketId] ?: 0L
        val updated = _trackingSnapshots.value + (snapshot.ticketId to snapshot)
        _trackingSnapshots.value = updated
        if (_activeTicketId.value == snapshot.ticketId || _tracking.value == null) {
            _tracking.value = snapshot
        }
        if (meaningfulTransition || now - lastPersist >= TRACKING_CHECKPOINT_INTERVAL_MS) {
            persistTrackingSnapshots(updated)
            lastTrackingPersistEpochMillis[snapshot.ticketId] = now
        }
    }

    fun tracking(ticketId: String): TrackingSnapshot? = _trackingSnapshots.value[ticketId]

    @Synchronized
    fun clearTracking(ticketId: String? = null) {
        if (ticketId == null) {
            secure.remove(KEY_TRACKING)
            secure.remove(KEY_TRACKING_SESSIONS)
            secure.removeDurable(KEY_ACTIVE_TICKET)
            secure.removeDurable(KEY_ACTIVE_TICKETS)
            _trackingSnapshots.value = emptyMap()
            _activeTicketIds.value = emptySet()
            _tracking.value = null
            _activeTicketId.value = null
            lastTrackingPersistEpochMillis.clear()
            return
        }

        val snapshots = _trackingSnapshots.value - ticketId
        val active = LinkedHashSet(_activeTicketIds.value).apply { remove(ticketId) }
        val primary = if (_activeTicketId.value == ticketId) active.firstOrNull() else _activeTicketId.value

        _trackingSnapshots.value = snapshots
        _activeTicketIds.value = active
        _activeTicketId.value = primary
        _tracking.value = primary?.let(snapshots::get) ?: snapshots.values.firstOrNull()
        lastTrackingPersistEpochMillis.remove(ticketId)

        persistTrackingSnapshots(snapshots)
        persistActiveTicketIds(active)
        if (primary == null) secure.removeDurable(KEY_ACTIVE_TICKET)
        else secure.putDurable(KEY_ACTIVE_TICKET, primary)
    }

    private fun persistTrackingSnapshots(snapshots: Map<String, TrackingSnapshot>) {
        if (snapshots.isEmpty()) {
            secure.remove(KEY_TRACKING_SESSIONS)
            secure.remove(KEY_TRACKING)
        } else {
            secure.put(
                KEY_TRACKING_SESSIONS,
                json.encodeToString(ListSerializer(TrackingSnapshot.serializer()), snapshots.values.toList()),
            )
            // Keep a legacy-compatible primary checkpoint for upgrades/downgrades.
            val primary = _activeTicketId.value?.let(snapshots::get) ?: snapshots.values.first()
            secure.put(KEY_TRACKING, json.encodeToString(primary))
        }
    }

    private fun persistActiveTicketIds(ticketIds: Collection<String>) {
        if (ticketIds.isEmpty()) secure.removeDurable(KEY_ACTIVE_TICKETS)
        else secure.putDurable(KEY_ACTIVE_TICKETS, ticketIds.joinToString("\n"))
    }

    fun ticket(ticketId: String): Ticket? = _tickets.value.firstOrNull { it.id == ticketId }

    fun notificationFingerprint(packageName: String, notificationKey: String, body: String): String {
        val bytes = "$packageName\u0000$notificationKey\u0000$body".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    @Synchronized
    fun wasNotificationImportProcessed(fingerprint: String): Boolean =
        fingerprint in processedNotificationFingerprints

    @Synchronized
    fun markNotificationImportProcessed(fingerprint: String) {
        if (!processedNotificationFingerprints.add(fingerprint)) return
        while (processedNotificationFingerprints.size > MAX_NOTIFICATION_FINGERPRINTS) {
            processedNotificationFingerprints.remove(processedNotificationFingerprints.first())
        }
        preferences.writeString(
            KEY_NOTIFICATION_IMPORT_FINGERPRINTS,
            processedNotificationFingerprints.joinToString("\n"),
        )
    }

    private suspend fun loadTicketsAndMigrate(): List<Ticket> {
        val entities = ticketDao.getAll()
        var corruptRows = 0
        val fromRoom = entities.mapNotNull { entity ->
            runCatching {
                json.decodeFromString<Ticket>(secure.decryptPayload(entity.encryptedPayload))
            }.onFailure { corruptRows += 1 }.getOrNull()
        }
        if (corruptRows > 0) {
            _persistenceError.value = "$corruptRows saved ticket row(s) could not be decrypted; other tickets were preserved"
        }
        val legacy = if (fromRoom.isEmpty()) {
            secure.get(KEY_LEGACY_TICKETS)?.let { encoded ->
                runCatching { json.decodeFromString(ListSerializer(Ticket.serializer()), encoded) }.getOrNull()
            }.orEmpty()
        } else {
            emptyList()
        }
        val source = if (fromRoom.isNotEmpty()) fromRoom else legacy
        val normalized = sortTickets(source.map(::normalizeTicket))
        if (entities.isEmpty() && normalized.isNotEmpty()) {
            ticketDao.upsertAll(normalized.map(::entityFor))
            secure.removeDurable(KEY_LEGACY_TICKETS)
        } else {
            val byId = fromRoom.associateBy(Ticket::id)
            val changed = normalized.filter { byId[it.id] != it }
            if (changed.isNotEmpty()) ticketDao.upsertAll(changed.map(::entityFor))
        }
        return normalized
    }

    private fun persistTicket(ticket: Ticket) {
        persistenceScope.launch { ticketDao.upsert(entityFor(ticket)) }
    }

    private fun entityFor(ticket: Ticket): TicketEntity = TicketEntity(
        id = ticket.id,
        encryptedPayload = secure.encryptPayload(json.encodeToString(ticket)),
        updatedAtEpochMillis = System.currentTimeMillis(),
    )

    @Suppress("DEPRECATION")
    private fun normalizeTicket(ticket: Ticket): Ticket {
        var withoutVolatileFields = ticket.copy(
            expectedDepartureEpochMillis = null,
            expectedArrivalEpochMillis = null,
            liveDelayMinutes = null,
        )
        val legacyManualOptOut = !withoutVolatileFields.automaticTrackingEnabled &&
            withoutVolatileFields.automationState == TicketAutomationState.COMPLETED &&
            withoutVolatileFields.completedAtEpochMillis != null &&
            !withoutVolatileFields.isClearlyPastByTimeOnly() &&
            withoutVolatileFields.automationMessage.orEmpty().contains("disabled", ignoreCase = true)
        if (legacyManualOptOut) {
            withoutVolatileFields = withoutVolatileFields.copy(
                automationState = TicketAutomationState.DISABLED,
                completedAtEpochMillis = null,
            )
        }
        if (!withoutVolatileFields.isClearlyPastForAutomation()) return withoutVolatileFields
        return withoutVolatileFields.copy(
            automaticTrackingEnabled = false,
            automationState = TicketAutomationState.COMPLETED,
            activationEpochMillis = null,
            activationMethod = TicketActivationMethod.NONE,
            schedulingFingerprint = null,
            automationMessage = withoutVolatileFields.automationMessage ?: "Past passenger segment",
            completedAtEpochMillis = withoutVolatileFields.completedAtEpochMillis ?: System.currentTimeMillis(),
        )
    }

    private suspend fun preferenceOrLegacy(preferenceKey: String, legacySecureKey: String): String? {
        preferences.readString(preferenceKey)?.let { return it }
        val legacy = secure.get(legacySecureKey) ?: return null
        preferences.writeStringNow(preferenceKey, legacy)
        secure.removeDurable(legacySecureKey)
        return legacy
    }

    private fun importedTicketFingerprint(ticket: Ticket): String = listOf(
        ticket.trainNumber,
        ticket.serviceDate,
        ticket.originStationCode,
        ticket.destinationStationCode,
        ticket.originName,
        ticket.destinationName,
        ticket.scheduledDepartureEpochMillis,
        ticket.scheduledArrivalEpochMillis,
        ticket.carriage,
        ticket.seat,
        ticket.reference,
        ticket.direction,
    ).joinToString("|")

    private fun sortTickets(tickets: List<Ticket>): List<Ticket> {
        val today = LocalDate.now()
        return tickets.sortedWith(
            compareBy<Ticket> { if (it.isClearlyPastForAutomation(today = today)) 1 else 0 }
                .thenBy {
                    val date = runCatching { LocalDate.parse(it.serviceDate) }.getOrNull()
                    if (date != null && date.isBefore(today)) -date.toEpochDay() else date?.toEpochDay() ?: Long.MAX_VALUE
                }
                .thenBy { it.scheduledDepartureEpochMillis ?: Long.MAX_VALUE },
        )
    }

    private companion object {
        const val KEY_CP_FRONTEND_CONFIG = "cp_frontend_config"
        const val KEY_LEGACY_API_SETTINGS = "api_settings"
        const val KEY_LEGACY_TICKETS = "tickets"
        const val KEY_SMS_IMPORT = "sms_import_settings"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_FAVORITE_STATIONS = "favorite_station_codes"
        const val KEY_TRACKING = "tracking"
        const val KEY_TRACKING_SESSIONS = "tracking_sessions_v2"
        const val KEY_ACTIVE_TICKET = "active_ticket"
        const val KEY_ACTIVE_TICKETS = "active_tickets_v2"
        const val KEY_NOTIFICATION_IMPORT_FINGERPRINTS = "notification_import_fingerprints"
        const val MAX_NOTIFICATION_FINGERPRINTS = 256
        const val TRACKING_CHECKPOINT_INTERVAL_MS = 60_000L
    }
}
