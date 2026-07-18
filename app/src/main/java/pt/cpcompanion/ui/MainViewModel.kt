package pt.cpcompanion.ui

import android.app.AlarmManager
import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pt.cpcompanion.AppContainer
import pt.cpcompanion.automation.TicketActivationLauncher
import pt.cpcompanion.automation.TicketActivationScheduler
import pt.cpcompanion.automation.TicketAutomationReconciler
import pt.cpcompanion.automation.TicketValidationCoordinator
import pt.cpcompanion.model.SmsImportSettings
import pt.cpcompanion.model.Station
import pt.cpcompanion.model.StationBoardEntry
import pt.cpcompanion.model.StationDetails
import pt.cpcompanion.model.ThemeMode
import pt.cpcompanion.model.Ticket
import pt.cpcompanion.model.TicketActivationMethod
import pt.cpcompanion.model.TicketAutomationState
import pt.cpcompanion.model.TicketSource
import pt.cpcompanion.model.TrainServiceEntry
import pt.cpcompanion.model.TrainTrip
import pt.cpcompanion.model.shouldShowAutomationSwitch
import pt.cpcompanion.sms.SmsImportCoordinator
import pt.cpcompanion.sms.SmsImportResult
import pt.cpcompanion.tracking.TrainTrackingService
import pt.cpcompanion.worker.SmsInboxSyncScheduler
import pt.cpcompanion.worker.TicketReminderScheduler
import pt.cpcompanion.domain.StationTimeZoneResolver

sealed interface AppScreen {
    data object Home : AppScreen
    data object Search : AppScreen
    data object Tickets : AppScreen
    data object Settings : AppScreen
    data class StationScreen(val station: Station) : AppScreen
    data class TripScreen(val trainNumber: String, val date: String, val ticketId: String? = null) : AppScreen
}

data class MainUiState(
    val screen: AppScreen = AppScreen.Home,
    val stations: List<Station> = emptyList(),
    val trains: List<TrainServiceEntry> = emptyList(),
    val tickets: List<Ticket> = emptyList(),
    val selectedStationDetails: StationDetails? = null,
    val stationBoard: List<StationBoardEntry> = emptyList(),
    val boardDepartures: Boolean = true,
    val stationBoardDate: String = LocalDate.now().toString(),
    val selectedTrip: TrainTrip? = null,
    val loading: Boolean = false,
    val catalogLoading: Boolean = false,
    val settingsLoading: Boolean = false,
    val stationLoading: Boolean = false,
    val boardLoading: Boolean = false,
    val tripLoading: Boolean = false,
    val message: String? = null,
    val smsImportSettings: SmsImportSettings = SmsImportSettings(),
    val favoriteStationCodes: Set<String> = emptySet(),
    val smsImporting: Boolean = false,
    val canGoBack: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val exactAlarmAllowed: Boolean = true,
    val requestCounters: Map<String, Long> = emptyMap(),
)

data class MainShellState(
    val screen: AppScreen = AppScreen.Home,
    val tickets: List<Ticket> = emptyList(),
    val message: String? = null,
    val smsImportSettings: SmsImportSettings = SmsImportSettings(),
    val canGoBack: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val exactAlarmAllowed: Boolean = true,
)

class MainViewModel(
    application: Application,
    private val container: AppContainer,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val backStack = ArrayDeque<AppScreen>().apply {
        savedStateHandle.get<ArrayList<String>>(KEY_BACK_STACK)
            ?.mapNotNull(::decodeScreen)
            ?.forEach(::addLast)
    }
    private val restoredScreen = savedStateHandle.get<String>(KEY_SCREEN)
        ?.let(::decodeScreen)
        ?: AppScreen.Home
    private val _state = MutableStateFlow(
        MainUiState(
            screen = restoredScreen,
            stationBoardDate = savedStateHandle.get<String>(KEY_STATION_BOARD_DATE) ?: LocalDate.now().toString(),
            canGoBack = hasBackDestination(restoredScreen),
        ),
    )
    val state: StateFlow<MainUiState> = _state.asStateFlow()
    val shellState: StateFlow<MainShellState> = state
        .map { current ->
            MainShellState(
                screen = current.screen,
                tickets = current.tickets,
                message = current.message,
                smsImportSettings = current.smsImportSettings,
                canGoBack = current.canGoBack,
                themeMode = current.themeMode,
                exactAlarmAllowed = current.exactAlarmAllowed,
            )
        }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            MainShellState(
                screen = restoredScreen,
                canGoBack = hasBackDestination(restoredScreen),
            ),
        )
    val tracking = container.stores.tracking
    private var smsImportJob: Job? = null
    private var catalogLoadJob: Job? = null
    private var settingsLoadJob: Job? = null
    private var stationLoadJob: Job? = null
    private var boardLoadJob: Job? = null
    private var tripLoadJob: Job? = null
    private var stationRequestKey: String? = null
    private var boardRequestKey: String? = null
    private var tripRequestKey: String? = null
    private var stationRequestSequence: Long = 0L
    private var boardRequestSequence: Long = 0L
    private var tripRequestSequence: Long = 0L

    init {
        viewModelScope.launch {
            container.stores.awaitReady()
            val stations = container.repository.cachedStations()
            val trains = container.repository.cachedTrains()
            _state.value = _state.value.copy(stations = stations, trains = trains)
            resolveSmsTicketStations(stations)
            if (stations.isEmpty() || trains.isEmpty() || container.repository.catalogCacheIsStale()) {
                refreshCatalogs()
            } else {
                runCatching { container.api.warmUpConfiguration() }
            }
        }
        viewModelScope.launch {
            container.stores.persistenceError.collectLatest { error ->
                if (!error.isNullOrBlank()) _state.value = _state.value.copy(message = error)
            }
        }
        viewModelScope.launch {
            container.stores.tickets.collectLatest { tickets ->
                // Scheduling is deliberately not performed from this continuous collector.
                _state.value = _state.value.copy(tickets = tickets)
            }
        }
        viewModelScope.launch {
            container.stores.smsImportSettings.collectLatest {
                _state.value = _state.value.copy(smsImportSettings = it)
            }
        }
        viewModelScope.launch {
            container.stores.favoriteStationCodes.collectLatest {
                _state.value = _state.value.copy(favoriteStationCodes = it)
            }
        }
        viewModelScope.launch {
            container.stores.themeMode.collectLatest { _state.value = _state.value.copy(themeMode = it) }
        }
        refreshAutomationCapabilities()
        when (val screen = restoredScreen) {
            is AppScreen.StationScreen -> loadStation(screen.station)
            is AppScreen.TripScreen -> loadTrip(screen.trainNumber, screen.date)
            else -> Unit
        }
    }

    fun navigate(screen: AppScreen) {
        val current = _state.value.screen
        if (screen == current) return
        if (current.isTopLevel() && screen.isTopLevel()) {
            backStack.clear()
        } else if (backStack.peekLast() != current) {
            backStack.addLast(current)
        }
        persistNavigationState(screen)
        showScreen(screen)
    }

    fun back() {
        val previous = backStack.pollLast()
            ?: if (_state.value.screen != AppScreen.Home) AppScreen.Home else return
        persistNavigationState(previous)
        showScreen(previous)
    }

    private fun showScreen(screen: AppScreen) {
        cancelLoadsNotNeeded(screen)
        persistNavigationState(screen)
        _state.value = _state.value.copy(
            screen = screen,
            message = null,
            canGoBack = hasBackDestination(screen),
        )
        when (screen) {
            is AppScreen.StationScreen -> loadStation(screen.station)
            is AppScreen.TripScreen -> loadTrip(screen.trainNumber, screen.date)
            else -> Unit
        }
    }

    private fun hasBackDestination(screen: AppScreen): Boolean =
        backStack.isNotEmpty() || screen != AppScreen.Home

    private fun persistNavigationState(screen: AppScreen) {
        savedStateHandle[KEY_SCREEN] = encodeScreen(screen)
        savedStateHandle[KEY_BACK_STACK] = ArrayList(backStack.map(::encodeScreen))
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun showMessage(message: String) {
        _state.value = _state.value.copy(message = message)
    }

    fun refreshAutomationCapabilities() {
        val exactAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getApplication<Application>().getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        } else true
        _state.value = _state.value.copy(exactAlarmAllowed = exactAllowed)
        TicketAutomationReconciler.enqueue(getApplication())
    }

    fun setThemeMode(mode: ThemeMode) {
        container.stores.saveThemeMode(mode)
    }

    fun refreshDiagnostics() {
        _state.value = _state.value.copy(requestCounters = container.api.debugRequestCounters())
    }

    fun refreshCatalogs() {
        if (catalogLoadJob?.isActive == true) return
        catalogLoadJob = viewModelScope.launch {
            _state.value = _state.value.copy(catalogLoading = true)
            updateCombinedLoading()
            try {
                val (stations, trains) = coroutineScope {
                    val stations = async { container.repository.refreshStations(forceRefresh = true) }
                    val trains = async { container.repository.refreshTrains(forceRefresh = true) }
                    stations.await() to trains.await()
                }
                _state.value = _state.value.copy(stations = stations, trains = trains, message = null)
                resolveSmsTicketStations(stations)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                showError(error)
            } finally {
                _state.value = _state.value.copy(catalogLoading = false)
                updateCombinedLoading()
            }
        }
    }

    fun ensureCatalogsLoaded() {
        if (_state.value.stations.isEmpty() || _state.value.trains.isEmpty()) refreshCatalogs()
    }

    fun toggleFavoriteStation(stationCode: String) {
        container.stores.toggleFavoriteStation(stationCode)
    }

    fun setBoardDepartures(departures: Boolean) {
        val screen = _state.value.screen as? AppScreen.StationScreen ?: return
        if (_state.value.boardDepartures == departures) return
        _state.value = _state.value.copy(boardDepartures = departures, stationBoard = emptyList())
        loadBoard(screen.station.code, departures)
    }

    fun setStationBoardDate(value: String) {
        val date = runCatching { LocalDate.parse(value) }.getOrNull() ?: return
        val normalized = date.toString()
        if (_state.value.stationBoardDate == normalized) return
        savedStateHandle[KEY_STATION_BOARD_DATE] = normalized
        _state.value = _state.value.copy(stationBoardDate = normalized, stationBoard = emptyList())
        val screen = _state.value.screen as? AppScreen.StationScreen ?: return
        loadBoard(screen.station.code, _state.value.boardDepartures)
    }

    fun openBoardTrip(entry: StationBoardEntry) {
        navigate(AppScreen.TripScreen(entry.trainNumber, entry.serviceDate))
    }

    fun openTicket(ticketId: String) {
        viewModelScope.launch {
            container.stores.awaitReady()
            val ticket = container.stores.ticket(ticketId)
            if (ticket == null) {
                _state.value = _state.value.copy(message = "The selected ticket is no longer available")
                return@launch
            }
            navigate(AppScreen.TripScreen(ticket.trainNumber, ticket.serviceDate, ticket.id))
        }
    }

    fun refreshCurrentStationBoard() {
        val screen = _state.value.screen as? AppScreen.StationScreen ?: return
        val selectedDate = runCatching { LocalDate.parse(_state.value.stationBoardDate) }.getOrNull() ?: return
        if (selectedDate != ZonedDateTime.now(stationZone(screen.station.code)).toLocalDate()) return
        loadBoard(screen.station.code, _state.value.boardDepartures, showLoading = false, forceRefresh = true)
    }

    fun refreshCurrentScreen() {
        when (val screen = _state.value.screen) {
            AppScreen.Home -> {
                refreshAutomationCapabilities()
                _state.value = _state.value.copy(message = "Automation state reconciled")
            }
            AppScreen.Search -> refreshCatalogs()
            AppScreen.Tickets -> importSmsTickets(silent = false)
            AppScreen.Settings -> {
                if (settingsLoadJob?.isActive == true) return
                settingsLoadJob = viewModelScope.launch {
                    _state.value = _state.value.copy(settingsLoading = true)
                    updateCombinedLoading()
                    try {
                        container.api.warmUpConfiguration(forceRefresh = true)
                        refreshAutomationCapabilities()
                        refreshDiagnostics()
                        _state.value = _state.value.copy(message = "CP configuration and automation state refreshed")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        showError(error)
                    } finally {
                        _state.value = _state.value.copy(settingsLoading = false)
                        updateCombinedLoading()
                    }
                }
            }
            is AppScreen.StationScreen -> loadStation(screen.station, forceRefresh = true)
            is AppScreen.TripScreen -> loadTrip(screen.trainNumber, screen.date, forceRefresh = true)
        }
    }

    fun refreshCurrentTrip() {
        val screen = _state.value.screen as? AppScreen.TripScreen ?: return
        loadTrip(screen.trainNumber, screen.date, showLoading = true, forceRefresh = true)
    }

    fun refreshCurrentTripSilently() {
        val screen = _state.value.screen as? AppScreen.TripScreen ?: return
        val alreadyTracked = container.stores.trackingSnapshots.value.values.any { tracking ->
            tracking.trainNumber == screen.trainNumber && tracking.serviceDate == screen.date
        }
        if (alreadyTracked) return
        loadTrip(screen.trainNumber, screen.date, showLoading = false)
    }

    fun setSmsAutoScan(enabled: Boolean) {
        container.stores.updateSmsImportSettings { current ->
            current.copy(automaticSmsImportEnabled = enabled)
        }
        SmsInboxSyncScheduler.syncWithSettings(getApplication())
        _state.value = _state.value.copy(
            message = if (enabled) "Automatic CP SMS inbox import enabled" else "Automatic CP SMS inbox import disabled",
        )
    }

    fun importSmsTickets(silent: Boolean = false) {
        if (smsImportJob?.isActive == true) return
        val now = System.currentTimeMillis()
        val settings = container.stores.smsImportSettings.value
        if (silent && settings.lastScanAtEpochMillis != null && now - settings.lastScanAtEpochMillis < 5 * 60_000L) return
        val stations = _state.value.stations
        smsImportJob = viewModelScope.launch {
            _state.value = _state.value.copy(smsImporting = true)
            runCatching {
                SmsImportCoordinator.scanInbox(
                    context = getApplication(),
                    stations = stations,
                    requireAutomaticOptIn = false,
                )
            }.onSuccess { outcome ->
                outcome.result?.let { result ->
                    showSmsImportResult(result, outcome.changedCount, silent)
                }
            }.onFailure { error ->
                SmsImportCoordinator.recordFailedScan(getApplication())
                if (!silent) _state.value = _state.value.copy(message = error.message ?: "Could not read CP SMS messages")
            }
            _state.value = _state.value.copy(smsImporting = false)
        }
    }

    fun importSmsText(text: String) {
        if (text.isBlank()) {
            _state.value = _state.value.copy(message = "Paste or share a CP ticket SMS first")
            return
        }
        val stations = _state.value.stations
        viewModelScope.launch {
            _state.value = _state.value.copy(smsImporting = true)
            runCatching { withContext(Dispatchers.Default) { container.smsTicketImporter.importSharedText(text, stations) } }
                .onSuccess { result -> persistSmsImport(result, silent = false) }
                .onFailure { error ->
                    _state.value = _state.value.copy(message = error.message ?: "The shared text could not be imported")
                }
            _state.value = _state.value.copy(smsImporting = false)
        }
    }

    private suspend fun persistSmsImport(result: SmsImportResult, silent: Boolean) {
        var changedCount = 0
        result.tickets.forEach { ticket ->
            val upsert = container.stores.upsertImportedTicket(ticket)
            if (upsert.changed) changedCount += 1
            if (upsert.requiresValidation) {
                val outcome = TicketValidationCoordinator.validateAndSchedule(getApplication(), upsert.ticket)
                if (outcome.shouldRetry) TicketAutomationReconciler.enqueue(getApplication())
            }
        }
        showSmsImportResult(result, changedCount, silent)
    }

    private fun showSmsImportResult(result: SmsImportResult, changedCount: Int, silent: Boolean) {
        if (!silent || result.tickets.isNotEmpty()) {
            val issueSuffix = if (result.issues.isEmpty()) "" else " ${result.issues.size} leg(s) still need station matching."
            _state.value = _state.value.copy(
                message = when {
                    result.tickets.isNotEmpty() ->
                        if (changedCount > 0) {
                            "Imported or updated $changedCount ticket leg(s); future tickets were scheduled automatically.$issueSuffix"
                        } else {
                            "The ticket was already imported and its automatic tracking schedule is unchanged.$issueSuffix"
                        }
                    result.candidateMessages == 0 ->
                        "Checked ${result.messagesScanned} recent SMS message(s), but none contained a recognizable CP ticket."
                    else -> "CP ticket SMS found, but no ticket could be created.$issueSuffix"
                },
            )
        }
    }

    fun saveTicket(ticket: Ticket): Ticket {
        val result = container.stores.upsertTicket(
            ticket.copy(
                automationState = TicketAutomationState.NEEDS_VALIDATION,
                automationMessage = "Validating the passenger segment",
            ),
        )
        _state.value = _state.value.copy(message = "Ticket saved; validating automatic tracking")
        viewModelScope.launch { enrichAndSchedule(result) }
        return result
    }

    fun deleteTicket(ticketId: String) {
        val isActive = ticketId in container.stores.activeTicketIds.value
        if (isActive) TrainTrackingService.stop(getApplication(), ticketId)
        TicketReminderScheduler.cancel(getApplication(), ticketId)
        container.stores.deleteTicket(ticketId)
        _state.value = _state.value.copy(message = "Ticket deleted")
    }

    fun setTicketAutomaticTracking(ticketId: String, enabled: Boolean) {
        val current = container.stores.ticket(ticketId) ?: return
        if (enabled && !current.shouldShowAutomationSwitch()) {
            _state.value = _state.value.copy(message = "Automatic tracking cannot be enabled for a past departure")
            return
        }
        val ticket = container.stores.updateTicket(ticketId) {
            it.copy(
                automaticTrackingEnabled = enabled,
                automationState = if (enabled) TicketAutomationState.NEEDS_VALIDATION else TicketAutomationState.DISABLED,
                automationMessage = if (enabled) "Automatic tracking re-enabled" else "Automatic tracking disabled",
                completedAtEpochMillis = null,
                validationFailureCount = if (enabled) 0 else it.validationFailureCount,
                activationEpochMillis = if (enabled) it.activationEpochMillis else null,
                activationMethod = if (enabled) it.activationMethod else TicketActivationMethod.NONE,
                schedulingFingerprint = null,
            )
        } ?: return
        if (enabled) {
            TicketActivationScheduler.schedule(getApplication(), ticket, force = true)
        } else {
            TicketActivationScheduler.cancel(getApplication(), ticketId)
            if (ticketId in container.stores.activeTicketIds.value) {
                TrainTrackingService.stop(getApplication(), ticketId)
            }
        }
    }

    fun createTicketForTrip(
        trip: TrainTrip,
        originCode: String,
        destinationCode: String,
        carriage: String,
        seat: String,
    ): Ticket? {
        val originIndex = trip.stops.indexOfFirst { it.station.code == originCode }
        val destinationIndex = trip.stops.indexOfFirst { it.station.code == destinationCode }
        if (originIndex < 0 || destinationIndex < 0 || originIndex >= destinationIndex) {
            _state.value = _state.value.copy(message = "Choose an origin and a later destination from this train's calling points")
            return null
        }
        val origin = trip.stops[originIndex]
        val destination = trip.stops[destinationIndex]
        val ticket = Ticket(
            id = UUID.randomUUID().toString(),
            trainNumber = trip.trainNumber,
            serviceLabel = trip.serviceName,
            serviceDate = trip.serviceDate,
            originStationCode = originCode,
            destinationStationCode = destinationCode,
            originName = origin.station.name,
            destinationName = destination.station.name,
            carriage = carriage.trim().ifBlank { null },
            seat = seat.trim().ifBlank { null },
        )
        val snapshot = runCatching { container.resolver.resolve(ticket, trip) }.getOrNull()
        val enriched = ticket.copy(
            scheduledDepartureEpochMillis = snapshot?.scheduledOriginEpochMillis,
            scheduledArrivalEpochMillis = snapshot?.scheduledDestinationEpochMillis,
            automationState = if (snapshot == null) TicketAutomationState.NEEDS_VALIDATION else TicketAutomationState.SCHEDULED,
        )
        val saved = container.stores.upsertTicket(enriched)
        TicketReminderScheduler.schedule(getApplication(), saved)
        _state.value = _state.value.copy(message = "Ticket saved; tracking starts automatically one hour before departure")
        return saved
    }

    fun startTracking(ticket: Ticket) {
        viewModelScope.launch {
            val resolved = runCatching {
                withContext(Dispatchers.IO) {
                    var stations = _state.value.stations.ifEmpty { container.repository.cachedStations() }
                    var matched = container.smsTicketImporter.resolveTicketStations(ticket, stations)
                    if (matched.originStationCode.isBlank() || matched.destinationStationCode.isBlank()) {
                        stations = container.repository.refreshStations(forceRefresh = false)
                        matched = container.smsTicketImporter.resolveTicketStations(ticket, stations)
                    }
                    matched
                }
            }.getOrElse { error ->
                showError(error)
                return@launch
            }
            if (resolved.originStationCode.isBlank() || resolved.destinationStationCode.isBlank()) {
                _state.value = _state.value.copy(message = "This ticket's stations could not be matched to CP's current catalogue.")
                return@launch
            }
            val saved = container.stores.upsertTicket(resolved.copy(automaticTrackingEnabled = true))
            when (TicketActivationLauncher.activate(getApplication(), saved.id, "manual Track now")) {
                TicketActivationLauncher.Result.STARTED -> _state.value = _state.value.copy(message = "Tracking started early")
                TicketActivationLauncher.Result.ALREADY_RUNNING -> _state.value = _state.value.copy(message = "This ticket is already being tracked")
                TicketActivationLauncher.Result.BLOCKED_PERMISSION ->
                    _state.value = _state.value.copy(message = "Enable notification permission to start tracking")
                TicketActivationLauncher.Result.CONFLICT ->
                    _state.value = _state.value.copy(message = "Another passenger journey is active; this ticket is queued")
                TicketActivationLauncher.Result.FAILED ->
                    _state.value = _state.value.copy(message = "Android blocked tracking startup")
            }
        }
    }

    suspend fun loadTripForTicketForm(trainNumber: String, serviceDate: String): TrainTrip? =
        withContext(Dispatchers.IO) {
            val date = runCatching { LocalDate.parse(serviceDate) }.getOrNull() ?: return@withContext null
            runCatching { container.repository.trip(trainNumber, date) }.getOrNull()
        }

    fun stopTracking(ticketId: String? = container.stores.activeTicketId.value) {
        TrainTrackingService.stop(getApplication(), ticketId)
        _state.value = _state.value.copy(message = "Tracking stopped and disabled for this ticket")
    }

    private fun loadStation(station: Station, forceRefresh: Boolean = false) {
        stationLoadJob?.cancel()
        boardLoadJob?.cancel()
        val stationCode = station.code
        val key = "$stationCode|${++stationRequestSequence}"
        stationRequestKey = key
        _state.value = _state.value.copy(
            selectedStationDetails = null,
            stationBoard = emptyList(),
            stationLoading = true,
            boardLoading = true,
        )
        updateCombinedLoading()
        stationLoadJob = viewModelScope.launch {
            try {
                val details = container.repository.stationDetails(stationCode, forceRefresh)
                if (stationRequestKey == key && (_state.value.screen as? AppScreen.StationScreen)?.station?.code == stationCode) {
                    _state.value = _state.value.copy(selectedStationDetails = details)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (stationRequestKey == key) showError(error)
            } finally {
                if (stationRequestKey == key) {
                    _state.value = _state.value.copy(stationLoading = false)
                    updateCombinedLoading()
                }
            }
        }
        loadBoard(stationCode, _state.value.boardDepartures, showLoading = true, forceRefresh = forceRefresh)
    }

    private fun loadBoard(
        stationCode: String,
        departures: Boolean,
        showLoading: Boolean = true,
        forceRefresh: Boolean = false,
    ) {
        boardLoadJob?.cancel()
        val selectedDate = runCatching { LocalDate.parse(_state.value.stationBoardDate) }
            .getOrElse { LocalDate.now(stationZone(stationCode)) }
        val requestStart = LocalTime.MIDNIGHT
        val logicalKey = "$stationCode|$departures|$selectedDate"
        val key = "$logicalKey|${++boardRequestSequence}"
        boardRequestKey = key
        _state.value = _state.value.copy(boardLoading = true)
        updateCombinedLoading()
        boardLoadJob = viewModelScope.launch {
            try {
                val board = container.repository.stationBoard(
                    stationCode,
                    selectedDate,
                    departures,
                    requestStart,
                    forceRefresh,
                )
                val activeStation = (_state.value.screen as? AppScreen.StationScreen)?.station?.code
                if (boardRequestKey == key && activeStation == stationCode && _state.value.boardDepartures == departures) {
                    _state.value = _state.value.copy(stationBoard = board, message = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (boardRequestKey == key && showLoading) showError(error)
            } finally {
                if (boardRequestKey == key) {
                    _state.value = _state.value.copy(boardLoading = false)
                    updateCombinedLoading()
                }
            }
        }
    }

    private fun loadTrip(
        trainNumber: String,
        date: String,
        showLoading: Boolean = true,
        forceRefresh: Boolean = false,
    ) {
        tripLoadJob?.cancel()
        val logicalKey = "$trainNumber|$date"
        val key = "$logicalKey|${++tripRequestSequence}"
        tripRequestKey = key
        if (_state.value.selectedTrip?.let { "${it.trainNumber}|${it.serviceDate}" } != logicalKey) {
            _state.value = _state.value.copy(selectedTrip = null)
        }
        _state.value = _state.value.copy(tripLoading = true)
        updateCombinedLoading()
        tripLoadJob = viewModelScope.launch {
            try {
                val trip = container.repository.trip(trainNumber, LocalDate.parse(date), forceRefresh)
                val screen = _state.value.screen as? AppScreen.TripScreen
                if (tripRequestKey == key && screen?.trainNumber == trainNumber && screen.date == date) {
                    _state.value = _state.value.copy(selectedTrip = trip, message = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (tripRequestKey == key && showLoading) showError(error)
            } finally {
                if (tripRequestKey == key) {
                    _state.value = _state.value.copy(tripLoading = false)
                    updateCombinedLoading()
                }
            }
        }
    }

    private fun cancelLoadsNotNeeded(screen: AppScreen) {
        var next = _state.value
        if (screen !is AppScreen.StationScreen) {
            stationRequestKey = null
            boardRequestKey = null
            stationLoadJob?.cancel()
            boardLoadJob?.cancel()
            next = next.copy(stationLoading = false, boardLoading = false)
        }
        if (screen !is AppScreen.TripScreen) {
            tripRequestKey = null
            tripLoadJob?.cancel()
            next = next.copy(tripLoading = false)
        }
        _state.value = next
        updateCombinedLoading()
    }

    private fun resolveSmsTicketStations(stations: List<Station>) {
        if (stations.isEmpty()) return
        container.stores.tickets.value
            .asSequence()
            .filter { it.source != TicketSource.MANUAL }
            .filter { it.originStationCode.isBlank() || it.destinationStationCode.isBlank() }
            .map { container.smsTicketImporter.resolveTicketStations(it, stations) }
            .filter { it.originStationCode.isNotBlank() && it.destinationStationCode.isNotBlank() }
            .forEach { resolved ->
                val saved = container.stores.upsertTicket(resolved)
                viewModelScope.launch {
                    val outcome = TicketValidationCoordinator.validateAndSchedule(getApplication(), saved)
                    if (outcome.shouldRetry) TicketAutomationReconciler.enqueue(getApplication())
                }
            }
    }

    private suspend fun enrichAndSchedule(ticket: Ticket) {
        val outcome = TicketValidationCoordinator.validateAndSchedule(getApplication(), ticket)
        if (outcome.shouldRetry) TicketAutomationReconciler.enqueue(getApplication())
        _state.value = _state.value.copy(message = outcome.message)
    }

    private fun showError(error: Throwable) {
        _state.value = _state.value.copy(message = error.message ?: "Unexpected error")
    }

    private fun updateCombinedLoading() {
        val state = _state.value
        _state.value = state.copy(
            loading = state.catalogLoading || state.settingsLoading || state.stationLoading ||
                state.boardLoading || state.tripLoading || state.smsImporting,
        )
    }

    private fun stationZone(stationCode: String): ZoneId {
        val station = _state.value.stations.firstOrNull { it.code == stationCode }
        return if (station != null) StationTimeZoneResolver.resolve(station)
        else StationTimeZoneResolver.resolve(stationCode)
    }


    private fun AppScreen.isTopLevel(): Boolean = this in setOf(
        AppScreen.Home,
        AppScreen.Search,
        AppScreen.Tickets,
        AppScreen.Settings,
    )

    companion object {
        private const val KEY_SCREEN = "navigation_screen"
        private const val KEY_BACK_STACK = "navigation_back_stack"
        private const val KEY_STATION_BOARD_DATE = "station_board_date"

        fun factory(application: Application, container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                    MainViewModel(application, container, extras.createSavedStateHandle()) as T
            }

        internal fun encodeScreen(screen: AppScreen): String = when (screen) {
            AppScreen.Home -> "home"
            AppScreen.Search -> "search"
            AppScreen.Tickets -> "tickets"
            AppScreen.Settings -> "settings"
            is AppScreen.StationScreen -> listOf(
                "station",
                Uri.encode(screen.station.code),
                Uri.encode(screen.station.name),
                Uri.encode(screen.station.timeZoneId.orEmpty()),
            ).joinToString("|")
            is AppScreen.TripScreen -> listOf(
                "trip",
                Uri.encode(screen.trainNumber),
                Uri.encode(screen.date),
                Uri.encode(screen.ticketId.orEmpty()),
            ).joinToString("|")
        }

        internal fun decodeScreen(value: String): AppScreen? {
            val parts = value.split('|')
            return when (parts.firstOrNull()) {
                "home" -> AppScreen.Home
                "search" -> AppScreen.Search
                "tickets" -> AppScreen.Tickets
                "settings" -> AppScreen.Settings
                "station" -> parts.getOrNull(1)?.let { code ->
                    AppScreen.StationScreen(
                        Station(
                            code = Uri.decode(code),
                            name = Uri.decode(parts.getOrNull(2).orEmpty()).ifBlank { Uri.decode(code) },
                            timeZoneId = Uri.decode(parts.getOrNull(3).orEmpty()).ifBlank { null },
                        ),
                    )
                }
                "trip" -> parts.getOrNull(1)?.let { train ->
                    AppScreen.TripScreen(
                        trainNumber = Uri.decode(train),
                        date = Uri.decode(parts.getOrNull(2).orEmpty()),
                        ticketId = Uri.decode(parts.getOrNull(3).orEmpty()).ifBlank { null },
                    )
                }
                else -> null
            }
        }
    }
}
