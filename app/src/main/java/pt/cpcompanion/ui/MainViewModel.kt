package pt.cpcompanion.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import java.time.LocalTime
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import pt.cpcompanion.AppContainer
import pt.cpcompanion.model.SmsImportSettings
import pt.cpcompanion.model.Station
import pt.cpcompanion.model.StationBoardEntry
import pt.cpcompanion.model.StationDetails
import pt.cpcompanion.model.Ticket
import pt.cpcompanion.model.TicketSource
import pt.cpcompanion.model.TrackingSnapshot
import pt.cpcompanion.model.TrainServiceEntry
import pt.cpcompanion.model.TrainTrip
import pt.cpcompanion.tracking.TrainTrackingService
import pt.cpcompanion.sms.SmsImportResult
import pt.cpcompanion.worker.TicketReminderScheduler

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
    val tracking: TrackingSnapshot? = null,
    val selectedStationDetails: StationDetails? = null,
    val stationBoard: List<StationBoardEntry> = emptyList(),
    val boardDepartures: Boolean = true,
    val selectedTrip: TrainTrip? = null,
    val loading: Boolean = false,
    val message: String? = null,
    val smsImportSettings: SmsImportSettings = SmsImportSettings(),
    val favoriteStationCodes: Set<String> = emptySet(),
    val smsImporting: Boolean = false,
    val canGoBack: Boolean = false,
)

class MainViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()
    private val backStack = ArrayDeque<AppScreen>()

    init {
        viewModelScope.launch {
            val stations = container.repository.cachedStations()
            val trains = container.repository.cachedTrains()
            _state.value = _state.value.copy(stations = stations, trains = trains)
            resolveSmsTicketStations(stations)
            if (stations.isEmpty() || trains.isEmpty()) {
                refreshCatalogs()
            } else {
                // Refresh the public CP runtime configuration in the background without
                // delaying cached catalogue display.
                runCatching { container.api.warmUpConfiguration() }
            }
        }
        viewModelScope.launch {
            container.stores.tickets.collectLatest { tickets ->
                _state.value = _state.value.copy(tickets = tickets)
                // Re-enqueue every saved future ticket after process start so
                // tickets imported in an earlier app session still activate
                // tracking at their one-hour checkpoint.
                tickets.forEach { TicketReminderScheduler.schedule(getApplication(), it) }
            }
        }
        viewModelScope.launch {
            container.stores.tracking.collectLatest { _state.value = _state.value.copy(tracking = it) }
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
    }

    fun navigate(screen: AppScreen) {
        val current = _state.value.screen
        if (screen == current) return
        if (backStack.peekLast() != current) backStack.addLast(current)
        showScreen(screen)
    }

    fun back() {
        val previous = backStack.pollLast() ?: return
        showScreen(previous)
    }

    private fun showScreen(screen: AppScreen) {
        _state.value = _state.value.copy(
            screen = screen,
            message = null,
            canGoBack = backStack.isNotEmpty(),
        )
        when (screen) {
            is AppScreen.StationScreen -> loadStation(screen.station)
            is AppScreen.TripScreen -> loadTrip(screen.trainNumber, screen.date)
            else -> Unit
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun showMessage(message: String) {
        _state.value = _state.value.copy(message = message)
    }

    fun refreshCatalogs() {
        if (_state.value.loading) return
        viewModelScope.launch {
            setLoading(true)
            runCatching {
                val stations = container.repository.refreshStations()
                val trains = container.repository.refreshTrains()
                stations to trains
            }.onSuccess { (stations, trains) ->
                _state.value = _state.value.copy(stations = stations, trains = trains, message = null)
                resolveSmsTicketStations(stations)
            }.onFailure(::showError)
            setLoading(false)
        }
    }

    fun toggleFavoriteStation(stationCode: String) {
        container.stores.toggleFavoriteStation(stationCode)
    }

    fun setBoardDepartures(departures: Boolean) {
        val screen = _state.value.screen as? AppScreen.StationScreen ?: return
        _state.value = _state.value.copy(boardDepartures = departures)
        loadBoard(screen.station.code, departures)
    }

    fun openBoardTrip(entry: StationBoardEntry) {
        navigate(AppScreen.TripScreen(entry.trainNumber, LocalDate.now().toString()))
    }

    fun openTicket(ticketId: String) {
        val ticket = container.stores.ticket(ticketId)
        if (ticket == null) {
            _state.value = _state.value.copy(message = "The selected ticket is no longer available")
            return
        }
        navigate(AppScreen.TripScreen(ticket.trainNumber, ticket.serviceDate, ticket.id))
    }

    fun refreshCurrentStationBoard() {
        val screen = _state.value.screen as? AppScreen.StationScreen ?: return
        loadBoard(screen.station.code, _state.value.boardDepartures, showLoading = false)
    }

    fun refreshCurrentScreen() {
        if (_state.value.loading) return

        when (val screen = _state.value.screen) {
            AppScreen.Home -> {
                _state.value = _state.value.copy(
                    message = "Home information is up to date",
                )
            }

            AppScreen.Search -> refreshCatalogs()

            AppScreen.Tickets -> importSmsTickets(silent = false)

            AppScreen.Settings -> {
                viewModelScope.launch {
                    setLoading(true)

                    runCatching {
                        container.api.warmUpConfiguration(forceRefresh = true)
                    }.onSuccess {
                        _state.value = _state.value.copy(
                            message = "CP configuration refreshed",
                        )
                    }.onFailure(::showError)

                    setLoading(false)
                }
            }

            is AppScreen.StationScreen ->
                loadStation(screen.station)

            is AppScreen.TripScreen ->
                loadTrip(screen.trainNumber, screen.date)
        }
    }

    fun refreshCurrentTrip() {
        val screen = _state.value.screen as? AppScreen.TripScreen ?: return
        loadTrip(screen.trainNumber, screen.date, showLoading = true)
    }

    fun setSmsAutoScan(enabled: Boolean) {
        container.stores.saveSmsImportSettings(
            container.stores.smsImportSettings.value.copy(autoScanOnAppOpen = enabled),
        )
        _state.value = _state.value.copy(
            message = if (enabled) "CP SMS scanning enabled when the app opens" else "Automatic CP SMS scanning disabled",
        )
    }

    fun importSmsTickets(silent: Boolean = false) {
        val now = System.currentTimeMillis()
        val settings = container.stores.smsImportSettings.value
        if (silent && settings.lastScanAtEpochMillis != null && now - settings.lastScanAtEpochMillis < 5 * 60_000L) return
        val stations = _state.value.stations
        viewModelScope.launch {
            _state.value = _state.value.copy(smsImporting = true)
            runCatching {
                container.smsTicketImporter.importInbox(
                    stations = stations,
                    stopAtMessageId = settings.lastImportedInboxMessageId,
                )
            }.onSuccess { result ->
                persistSmsImport(result, silent)
                result.newestImportedInboxMessageId?.let { newestMessageId ->
                    container.stores.saveSmsImportSettings(
                        container.stores.smsImportSettings.value.copy(
                            lastImportedInboxMessageId = newestMessageId,
                        ),
                    )
                }
            }
                .onFailure { error ->
                    if (!silent) _state.value = _state.value.copy(
                        message = error.message ?: "Could not read CP SMS messages",
                    )
                }
            container.stores.saveSmsImportSettings(
                container.stores.smsImportSettings.value.copy(lastScanAtEpochMillis = System.currentTimeMillis()),
            )
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
            runCatching { container.smsTicketImporter.importSharedText(text, stations) }
                .onSuccess { result -> persistSmsImport(result, silent = false) }
                .onFailure { error -> _state.value = _state.value.copy(
                    message = error.message ?: "The shared text could not be imported",
                ) }
            _state.value = _state.value.copy(smsImporting = false)
        }
    }

    private suspend fun persistSmsImport(
        result: SmsImportResult,
        silent: Boolean,
    ) {
        result.tickets.forEach { ticket ->
            container.stores.upsertTicket(ticket)
            TicketReminderScheduler.schedule(getApplication(), ticket)
        }

        if (!silent || result.tickets.isNotEmpty()) {
            val issueSuffix =
                if (result.issues.isEmpty()) {
                    ""
                } else {
                    " ${result.issues.size} leg(s) still need station matching."
                }

            _state.value = _state.value.copy(
                message = when {
                    result.tickets.isNotEmpty() ->
                        "Imported or updated ${result.tickets.size} ticket leg(s) " +
                            "from ${result.candidateMessages} CP SMS message(s).$issueSuffix"

                    result.candidateMessages == 0 ->
                        "Checked ${result.messagesScanned} recent SMS message(s), " +
                            "but none contained a recognizable CP ticket."

                    else ->
                        "CP ticket SMS found, but no ticket could be created.$issueSuffix"
                },
            )
        }
    }

    fun saveTicket(ticket: Ticket): Ticket {
        val result = container.stores.upsertTicket(ticket)
        _state.value = _state.value.copy(message = "Ticket saved; validating its passenger segment")
        viewModelScope.launch { enrichAndSchedule(result) }
        return result
    }

    fun deleteTicket(ticketId: String) {
        TicketReminderScheduler.cancel(getApplication(), ticketId)
        container.stores.deleteTicket(ticketId)
        _state.value = _state.value.copy(message = "Ticket deleted")
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
            _state.value = _state.value.copy(
                message = "Choose an origin and a later destination from this train's calling points",
            )
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
        )
        container.stores.upsertTicket(enriched)
        TicketReminderScheduler.schedule(getApplication(), enriched)
        _state.value = _state.value.copy(message = "Ticket saved and reminder scheduled")
        return enriched
    }

    fun startTracking(ticket: Ticket) {
        val resolved = container.smsTicketImporter.resolveTicketStations(ticket, _state.value.stations)
        if (resolved.originStationCode.isBlank() || resolved.destinationStationCode.isBlank()) {
            _state.value = _state.value.copy(
                message = "This SMS ticket still needs station matching. Refresh the station catalogue before tracking.",
            )
            return
        }
        container.stores.upsertTicket(resolved)
        TrainTrackingService.start(getApplication(), resolved.id)
        _state.value = _state.value.copy(message = "Tracking started")
    }

    fun stopTracking() {
        TrainTrackingService.stop(getApplication())
        _state.value = _state.value.copy(message = "Tracking stopped")
    }

    private fun loadStation(station: Station) {
        viewModelScope.launch {
            setLoading(true)
            val details = runCatching { container.repository.stationDetails(station.code) }
                .onFailure(::showError)
                .getOrNull()
            val board = runCatching {
                container.repository.stationBoard(
                    station.code,
                    LocalDate.now(),
                    _state.value.boardDepartures,
                    LocalTime.now(),
                )
            }.onFailure(::showError).getOrNull()
            _state.value = _state.value.copy(
                selectedStationDetails = details,
                stationBoard = board ?: _state.value.stationBoard,
            )
            setLoading(false)
        }
    }

    private fun loadBoard(stationCode: String, departures: Boolean, showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) setLoading(true)
            runCatching {
                container.repository.stationBoard(stationCode, LocalDate.now(), departures, LocalTime.now())
            }.onSuccess {
                _state.value = _state.value.copy(stationBoard = it, message = null)
            }.onFailure { error ->
                if (showLoading) showError(error)
            }
            if (showLoading) setLoading(false)
        }
    }

    private fun loadTrip(trainNumber: String, date: String, showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) setLoading(true)
            runCatching { container.repository.trip(trainNumber, LocalDate.parse(date)) }
                .onSuccess { _state.value = _state.value.copy(selectedTrip = it, message = null) }
                .onFailure(::showError)
            if (showLoading) setLoading(false)
        }
    }

    private fun resolveSmsTicketStations(stations: List<Station>) {
        if (stations.isEmpty()) return
        container.stores.tickets.value
            .asSequence()
            .filter { it.source != TicketSource.MANUAL }
            .filter { it.originStationCode.isBlank() || it.destinationStationCode.isBlank() }
            .map { container.smsTicketImporter.resolveTicketStations(it, stations) }
            .filter { it.originStationCode.isNotBlank() && it.destinationStationCode.isNotBlank() }
            .forEach(container.stores::upsertTicket)
    }

    private suspend fun enrichAndSchedule(ticket: Ticket) {
        runCatching {
            val trip = container.repository.trip(ticket.trainNumber, LocalDate.parse(ticket.serviceDate))
            val snapshot = container.resolver.resolve(ticket, trip)
            ticket.copy(
                serviceLabel = trip.serviceName ?: ticket.serviceLabel,
                originName = ticket.originName ?: snapshot.originName,
                destinationName = ticket.destinationName ?: snapshot.destinationName,
                scheduledDepartureEpochMillis = snapshot.scheduledOriginEpochMillis,
                scheduledArrivalEpochMillis = snapshot.scheduledDestinationEpochMillis,
            )
        }.onSuccess { enriched ->
            container.stores.upsertTicket(enriched)
            TicketReminderScheduler.schedule(getApplication(), enriched)
            _state.value = _state.value.copy(message = "Ticket validated; reminder scheduled one hour before departure")
        }.onFailure { error ->
            _state.value = _state.value.copy(
                message = "Ticket saved, but it could not be validated: ${error.message ?: "unknown error"}",
            )
        }
    }

    private fun showError(error: Throwable) {
        _state.value = _state.value.copy(message = error.message ?: "Unexpected error")
    }

    private fun setLoading(value: Boolean) {
        _state.value = _state.value.copy(loading = value)
    }

    companion object {
        fun factory(application: Application, container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MainViewModel(application, container) as T
            }
    }
}
