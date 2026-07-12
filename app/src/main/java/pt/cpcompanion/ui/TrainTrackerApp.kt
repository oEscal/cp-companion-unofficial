@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package pt.cpcompanion.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.core.app.NotificationManagerCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.delay
import pt.cpcompanion.R
import pt.cpcompanion.model.PassengerPhase
import pt.cpcompanion.model.Station
import pt.cpcompanion.model.StationBoardEntry
import pt.cpcompanion.model.Ticket
import pt.cpcompanion.model.TicketDirection
import pt.cpcompanion.model.TicketSource
import pt.cpcompanion.model.TrackingSnapshot
import pt.cpcompanion.model.TrainServiceEntry
import pt.cpcompanion.model.TrainTrip
import pt.cpcompanion.ui.theme.CpExpressiveTheme

private enum class SmsPermissionAction {
    IMPORT_NOW,
    ENABLE_AUTO_SCAN,
}

private enum class TicketFilter { FUTURE, PAST }

@Composable
fun TrainTrackerApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var pendingTicket by remember { mutableStateOf<Ticket?>(null) }
    var pendingSmsAction by remember { mutableStateOf<SmsPermissionAction?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            pendingTicket?.let(viewModel::startTracking)
        } else {
            viewModel.showMessage("Notification permission is required for reliable background train tracking")
        }
        pendingTicket = null
    }
    val smsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        when (pendingSmsAction) {
            SmsPermissionAction.IMPORT_NOW -> {
                if (granted) viewModel.importSmsTickets()
                else viewModel.showMessage("SMS access was not granted; paste or share the CP message instead")
            }
            SmsPermissionAction.ENABLE_AUTO_SCAN -> {
                if (granted) {
                    viewModel.setSmsAutoScan(true)
                    viewModel.importSmsTickets()
                } else {
                    viewModel.showMessage("Automatic CP SMS scanning requires SMS access")
                }
            }
            null -> Unit
        }
        pendingSmsAction = null
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    fun requestTracking(ticket: Ticket) {
        if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            viewModel.startTracking(ticket)
        } else {
            pendingTicket = ticket
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun requestSmsImport() {
        if (context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            viewModel.importSmsTickets()
        } else {
            pendingSmsAction = SmsPermissionAction.IMPORT_NOW
            smsPermission.launch(Manifest.permission.READ_SMS)
        }
    }

    fun setSmsAutoScan(enabled: Boolean) {
        if (!enabled) {
            viewModel.setSmsAutoScan(false)
        } else if (context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            viewModel.setSmsAutoScan(true)
            viewModel.importSmsTickets()
        } else {
            pendingSmsAction = SmsPermissionAction.ENABLE_AUTO_SCAN
            smsPermission.launch(Manifest.permission.READ_SMS)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (state.smsImportSettings.autoScanOnAppOpen &&
            context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.importSmsTickets(silent = true)
        }
    }

    BackHandler(enabled = state.canGoBack) {
        viewModel.back()
    }

    CpExpressiveTheme {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                AppTopBar(
                    screen = state.screen,
                    onRefresh = viewModel::refreshCatalogs,
                )
            },
            bottomBar = {
                if (state.screen is AppScreen.Home || state.screen is AppScreen.Search ||
                    state.screen is AppScreen.Tickets || state.screen is AppScreen.Settings
                ) {
                    MainNavigation(state.screen, viewModel::navigate)
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                AnimatedContent(targetState = state.screen, label = "screen") { screen ->
                    PullToRefreshBox(
                        isRefreshing = state.loading,
                        onRefresh = viewModel::refreshCurrentScreen,
                        modifier = Modifier.fillMaxSize(),
                        indicator = {
                            if (state.loading) {
                                LoadingIndicator(
                                    Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                                )
                            }
                        },
                    ) {
                        when (screen) {
                            AppScreen.Home -> HomeScreen(state, viewModel::navigate, viewModel::stopTracking, ::requestTracking)
                            AppScreen.Search -> SearchScreen(state, viewModel, viewModel::navigate)
                            AppScreen.Tickets -> TicketsScreen(
                                state = state,
                                viewModel = viewModel,
                                startTracking = ::requestTracking,
                                importFromInbox = ::requestSmsImport,
                                importSmsText = viewModel::importSmsText,
                            )
                            AppScreen.Settings -> SettingsScreen(
                                smsAutoScan = state.smsImportSettings.autoScanOnAppOpen,
                                setSmsAutoScan = ::setSmsAutoScan,
                            )
                            is AppScreen.StationScreen -> StationScreen(state, viewModel)
                            is AppScreen.TripScreen -> TripScreen(state, screen.ticketId, viewModel, ::requestTracking)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(
    screen: AppScreen,
    onRefresh: () -> Unit,
) {
    val title = when (screen) {
        AppScreen.Home -> "CP Companion"
        AppScreen.Search -> "Search"
        AppScreen.Tickets -> "Tickets"
        AppScreen.Settings -> "Automation"
        is AppScreen.StationScreen -> screen.station.name
        is AppScreen.TripScreen -> "Train ${screen.trainNumber}"
    }
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        actions = {
            if (screen is AppScreen.Search) TextButton(onClick = onRefresh) { Text("Refresh") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}

@Composable
private fun MainNavigation(screen: AppScreen, navigate: (AppScreen) -> Unit) {
    val destinations = listOf(
        Triple(AppScreen.Home, R.drawable.ic_home, "Home"),
        Triple(AppScreen.Search, R.drawable.ic_search, "Search"),
        Triple(AppScreen.Tickets, R.drawable.ic_ticket, "Tickets"),
        Triple(AppScreen.Settings, R.drawable.ic_settings, "Automation"),
    )
    NavigationBar {
        destinations.forEach { (destination, icon, label) ->
            NavigationBarItem(
                selected = screen == destination,
                onClick = { navigate(destination) },
                icon = { Icon(painterResource(icon), label) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun HomeScreen(
    state: MainUiState,
    navigate: (AppScreen) -> Unit,
    stopTracking: () -> Unit,
    startTracking: (Ticket) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("Your rail journey, at a glance", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Search live station boards, inspect a train trip, and pin one passenger journey to an ongoing notification.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            val nextTicket = state.tickets.firstOrNull { ticket ->
                ticket.isUpcoming()
            }
            state.tracking?.let { snapshot ->
                ActiveTrackingCard(
                    snapshot = snapshot,
                    stopTracking = stopTracking,
                    onOpen = {
                        navigate(AppScreen.TripScreen(snapshot.trainNumber, snapshot.serviceDate, snapshot.ticketId))
                    },
                )
            }
                ?: UpcomingHero(nextTicket, navigate, startTracking)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ExtendedFloatingActionButton(
                    onClick = { navigate(AppScreen.Search) },
                    icon = { Icon(painterResource(R.drawable.ic_search), null) },
                    text = { Text("Find a train") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                ExtendedFloatingActionButton(
                    onClick = { navigate(AppScreen.Tickets) },
                    icon = { Icon(painterResource(R.drawable.ic_ticket), null) },
                    text = { Text("Tickets") },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            ExpressiveInfoCard(
                title = "Live Update behavior",
                body = "Android 16 devices receive a progress-centric notification that requests Live Update promotion. " +
                    "Every device retains the complete standard ongoing-notification fallback.",
            )
        }
        if (state.stations.isEmpty() || state.trains.isEmpty()) {
            item {
                ExpressiveInfoCard(
                    title = "Catalogues not loaded",
                    body = "CP access is configured automatically from the public website configuration. Check the network connection, then pull down on the page to refresh.",
                )
            }
        }
    }
}

@Composable
private fun UpcomingHero(ticket: Ticket?, navigate: (AppScreen) -> Unit, startTracking: (Ticket) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        shape = RoundedCornerShape(40.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_train), null, modifier = Modifier.size(40.dp))
            if (ticket == null) {
                Text("No upcoming ticket", style = MaterialTheme.typography.headlineMedium)
                Text("Add a ticket manually with its train number, date, origin, destination, carriage, and seat.")
                Button(onClick = { navigate(AppScreen.Tickets) }) { Text("Add ticket") }
            } else {
                Text("Next saved trip", style = MaterialTheme.typography.labelLarge)
                Text("${ticket.serviceLabel ?: "Train"} ${ticket.trainNumber}", style = MaterialTheme.typography.displaySmall)
                Text("${ticket.originName ?: ticket.originStationCode} → ${ticket.destinationName ?: ticket.destinationStationCode}")
                Text(
                    listOfNotNull(ticket.serviceDate, ticket.departureTimingLabel())
                        .joinToString(" · "),
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { startTracking(ticket) }) { Text("Track train") }
                    OutlinedButton(
                        onClick = { navigate(AppScreen.TripScreen(ticket.trainNumber, ticket.serviceDate, ticket.id)) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f)),
                    ) { Text("Trip") }
                }
            }
        }
    }
}

@Composable
private fun ActiveTrackingCard(
    snapshot: TrackingSnapshot,
    stopTracking: () -> Unit,
    onOpen: () -> Unit,
) {
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        shape = RoundedCornerShape(40.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    painterResource(if (snapshot.phase == PassengerPhase.BOARDING_SOON) R.drawable.ic_seat else R.drawable.ic_train),
                    null,
                    modifier = Modifier.size(36.dp),
                )
                Column {
                    Text(snapshot.phase.label(), style = MaterialTheme.typography.labelLarge)
                    Text(
                        "${snapshot.serviceLabel ?: "Train"} ${snapshot.trainNumber}",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
            }
            Text("${snapshot.originName} → ${snapshot.destinationName}")
            snapshot.expectedEventEpochMillis?.let {
                Text(
                    timeRemaining(it),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            LinearProgressIndicator(
                progress = { snapshot.progress.toFloat() / snapshot.progressMax.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                snapshot.delayMinutes?.let { delay ->
                    StatusPill(
                        if (delay > 0) "$delay min late" else "On time",
                        when {
                            delay >= 10 -> MaterialTheme.colorScheme.error
                            delay > 0 -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        },
                        when {
                            delay >= 10 -> MaterialTheme.colorScheme.onError
                            delay > 0 -> MaterialTheme.colorScheme.onTertiary
                            else -> MaterialTheme.colorScheme.onPrimary
                        },
                    )
                }
                snapshot.platform?.let {
                    StatusPill(
                        "Platform $it",
                        MaterialTheme.colorScheme.secondary,
                        MaterialTheme.colorScheme.onSecondary,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                snapshot.carriage?.let {
                    StatusPill(
                        "Carriage $it",
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                snapshot.seat?.let {
                    StatusPill(
                        "Seat $it",
                        MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.onSecondaryContainer,
                        icon = R.drawable.ic_seat,
                    )
                }
            }
            OutlinedButton(onClick = stopTracking) { Text("Stop tracking") }
        }
    }
}

@Composable
private fun SearchScreen(
    state: MainUiState,
    viewModel: MainViewModel,
    navigate: (AppScreen) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var serviceDate by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var stationsOnly by rememberSaveable { mutableStateOf(false) }
    var trainsOnly by rememberSaveable { mutableStateOf(false) }
    val normalized = query.trim().lowercase().removeAccents()
    val stations = remember(state.stations, state.favoriteStationCodes, normalized) {
        val matches = if (normalized.isBlank()) {
            state.stations.filter { it.code in state.favoriteStationCodes }
        } else state.stations.filter {
            it.name.lowercase().removeAccents().contains(normalized) || it.code.lowercase().contains(normalized)
        }
        matches.sortedWith(
            compareByDescending<Station> { it.code in state.favoriteStationCodes }
                .thenBy { it.name.lowercase() },
        ).take(40)
    }
    val trains = remember(state.trains, normalized) {
        if (normalized.isBlank()) emptyList() else state.trains.filter {
            listOf(it.trainNumber, it.serviceName, it.origin?.name, it.destination?.name)
                .filterNotNull().any { value -> value.lowercase().removeAccents().contains(normalized) }
        }.take(40)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Station, code, train, origin, or destination") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
            )
        }
        item {
            ServiceDateField(
                value = serviceDate,
                onValueChange = { serviceDate = it },
                label = "Train service date",
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(stationsOnly, { stationsOnly = !stationsOnly; if (stationsOnly) trainsOnly = false }, { Text("Stations") })
                FilterChip(trainsOnly, { trainsOnly = !trainsOnly; if (trainsOnly) stationsOnly = false }, { Text("Trains") })
            }
        }
        if (!trainsOnly) {
            item { SectionHeader(if (normalized.isBlank()) "Starred stations" else "Stations", stations.size) }
            items(stations, key = { it.code }) { station ->
                StationResult(
                    station = station,
                    favorite = station.code in state.favoriteStationCodes,
                    onClick = { navigate(AppScreen.StationScreen(station)) },
                    onToggleFavorite = { viewModel.toggleFavoriteStation(station.code) },
                )
            }
        }
        if (!stationsOnly && normalized.isNotBlank()) {
            item { SectionHeader("Trains", trains.size) }
            items(trains, key = { it.key }) { train ->
                TrainResult(train) {
                    val date = LocalDate.parse(serviceDate)
                    navigate(AppScreen.TripScreen(train.trainNumber, date.toString()))
                }
            }
        }
    }
}

@Composable
private fun StationResult(
    station: Station,
    favorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(painterResource(R.drawable.ic_station), null, modifier = Modifier.size(30.dp))
            Column(Modifier.weight(1f)) {
                Text(station.name, style = MaterialTheme.typography.titleMedium)
                Text(station.code, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = .72f))
            }
            TextButton(onClick = onToggleFavorite) {
                Text(if (favorite) "★" else "☆", style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}

@Composable
private fun TrainResult(train: TrainServiceEntry, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(painterResource(R.drawable.ic_train), null, modifier = Modifier.size(30.dp))
            Column(Modifier.weight(1f)) {
                Text(train.serviceName ?: "Train", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${train.origin?.name ?: "Unknown origin"} → ${train.destination?.name ?: "Unknown destination"}",
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = .76f),
                )
            }
        }
    }
}

@Composable
private fun StationScreen(state: MainUiState, viewModel: MainViewModel) {
    val screen = state.screen as? AppScreen.StationScreen
    LaunchedEffect(screen?.station?.code, state.boardDepartures) {
        while (screen != null) {
            delay(30_000)
            viewModel.refreshCurrentStationBoard()
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.selectedStationDetails?.let { details ->
            item {
                Card(shape = RoundedCornerShape(32.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(details.name, style = MaterialTheme.typography.headlineMedium)
                        details.trainLine?.let { Text(it) }
                        details.address?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (details.services.isNotEmpty()) Text(details.services.joinToString(" · "))
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(state.boardDepartures, { viewModel.setBoardDepartures(true) }, { Text("Departures") })
                FilterChip(!state.boardDepartures, { viewModel.setBoardDepartures(false) }, { Text("Arrivals") })
            }
        }
        if (state.stationBoard.isEmpty() && !state.loading) {
            item { ExpressiveInfoCard("No services", "No upcoming services were returned for the selected board.") }
        }
        items(state.stationBoard, key = { "${it.trainNumber}-${it.scheduledArrival}-${it.scheduledDeparture}" }) { entry ->
            BoardEntryCard(entry, state.boardDepartures) { viewModel.openBoardTrip(entry) }
        }
    }
}

@Composable
private fun BoardEntryCard(entry: StationBoardEntry, departures: Boolean, onClick: () -> Unit) {
    val scheduled = if (departures) entry.scheduledDeparture else entry.scheduledArrival
    val expected = if (departures) entry.expectedDeparture else entry.expectedArrival
    val delay = entry.delayMinutes ?: 0
    val containerColor = when {
        entry.isCancelled -> MaterialTheme.colorScheme.errorContainer
        delay >= 10 -> MaterialTheme.colorScheme.errorContainer
        delay > 0 -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = when {
        entry.isCancelled || delay >= 10 -> MaterialTheme.colorScheme.onErrorContainer
        delay > 0 -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(expected ?: scheduled ?: "—", style = MaterialTheme.typography.headlineMedium)
                if (expected != null && scheduled != null && expected != scheduled) {
                    Text(scheduled, color = contentColor.copy(alpha = .65f))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("${entry.serviceName ?: "Train"} ${entry.trainNumber}", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (departures) entry.destination?.name ?: "Unknown destination"
                    else entry.origin?.name ?: "Unknown origin",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    entry.platform?.let {
                        StatusPill(
                            text = "Platform $it",
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    when {
                        entry.isCancelled -> StatusPill(
                            "Cancelled",
                            MaterialTheme.colorScheme.error,
                            MaterialTheme.colorScheme.onError,
                        )
                        delay > 0 -> StatusPill(
                            "+$delay min",
                            if (delay >= 10) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                            if (delay >= 10) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onTertiary,
                        )
                        else -> StatusPill(
                            "On time",
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TripScreen(
    state: MainUiState,
    ticketId: String?,
    viewModel: MainViewModel,
    startTracking: (Ticket) -> Unit,
) {
    val trip = state.selectedTrip
    if (trip == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (state.loading) LoadingIndicator() else Text("Trip information is unavailable")
        }
        return
    }
    val existingTicket = state.tickets.firstOrNull { it.id == ticketId }
    var showTrackingDialog by remember { mutableStateOf(false) }
    val currentStopIndex = remember(trip) { inferCurrentStopIndex(trip) }

    LaunchedEffect(trip.trainNumber, trip.serviceDate) {
        while (true) {
            delay(30_000)
            viewModel.refreshCurrentTripSilently()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { TripHero(trip, existingTicket, { showTrackingDialog = true }, existingTicket?.let { { startTracking(it) } }) }
        if (trip.messages.isNotEmpty()) item { ExpressiveInfoCard("Service message", trip.messages.joinToString("\n")) }
        item { SectionHeader("Calling points", trip.stops.size) }
        itemsIndexed(trip.stops, key = { _, stop -> stop.station.code }) { index, stop ->
            val visualState = when {
                stop.isCancelled -> TripStopVisualState.CANCELLED
                index < currentStopIndex -> TripStopVisualState.PASSED
                index == currentStopIndex -> TripStopVisualState.CURRENT
                else -> TripStopVisualState.UPCOMING
            }
            val containerColor = when (visualState) {
                TripStopVisualState.CANCELLED -> MaterialTheme.colorScheme.errorContainer
                TripStopVisualState.PASSED -> MaterialTheme.colorScheme.surfaceContainerLow
                TripStopVisualState.CURRENT -> MaterialTheme.colorScheme.tertiaryContainer
                TripStopVisualState.UPCOMING -> MaterialTheme.colorScheme.surfaceContainerHigh
            }
            val contentColor = when (visualState) {
                TripStopVisualState.CANCELLED -> MaterialTheme.colorScheme.onErrorContainer
                TripStopVisualState.PASSED -> MaterialTheme.colorScheme.onSurfaceVariant
                TripStopVisualState.CURRENT -> MaterialTheme.colorScheme.onTertiaryContainer
                TripStopVisualState.UPCOMING -> MaterialTheme.colorScheme.onSurface
            }
            Card(
                shape = RoundedCornerShape(if (visualState == TripStopVisualState.CURRENT) 30.dp else 22.dp),
                colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(if (visualState == TripStopVisualState.CURRENT) R.drawable.ic_train else R.drawable.ic_station),
                        contentDescription = null,
                        tint = contentColor,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stop.station.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            when (visualState) {
                                TripStopVisualState.CURRENT -> StatusPill(
                                    "Current",
                                    MaterialTheme.colorScheme.tertiary,
                                    MaterialTheme.colorScheme.onTertiary,
                                )
                                TripStopVisualState.PASSED -> StatusPill(
                                    "Passed",
                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TripStopVisualState.CANCELLED -> StatusPill(
                                    "Cancelled",
                                    MaterialTheme.colorScheme.error,
                                    MaterialTheme.colorScheme.onError,
                                )
                                TripStopVisualState.UPCOMING -> Unit
                            }
                        }
                        Text(stop.station.code, color = contentColor.copy(alpha = .68f))
                        Text(
                            listOfNotNull(
                                stop.scheduledArrival?.let { "Arr $it" },
                                stop.expectedArrival?.takeIf { it != stop.scheduledArrival }?.let { "ETA $it" },
                                stop.scheduledDeparture?.let { "Dep $it" },
                                stop.expectedDeparture?.takeIf { it != stop.scheduledDeparture }?.let { "ETD $it" },
                                stop.platform?.let { "Platform $it" },
                            ).joinToString(" · "),
                            color = contentColor.copy(alpha = .84f),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (existingTicket?.originStationCode == stop.station.code) {
                                StatusPill(
                                    "Your origin",
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                            if (existingTicket?.destinationStationCode == stop.station.code) {
                                StatusPill(
                                    "Your destination",
                                    MaterialTheme.colorScheme.secondary,
                                    MaterialTheme.colorScheme.onSecondary,
                                )
                            }
                        }
                    }
                    stop.delayMinutes?.takeIf { it > 0 }?.let { delay ->
                        StatusPill(
                            "+$delay min",
                            if (delay >= 10) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                            if (delay >= 10) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onTertiary,
                        )
                    }
                }
            }
        }
    }

    if (showTrackingDialog) {
        CreateTrackingTicketDialog(
            trip = trip,
            onDismiss = { showTrackingDialog = false },
            onCreate = { origin, destination, carriage, seat ->
                val ticket = viewModel.createTicketForTrip(trip, origin, destination, carriage, seat)
                if (ticket != null) {
                    showTrackingDialog = false
                    startTracking(ticket)
                }
            },
        )
    }
}

@Composable
private fun TripHero(
    trip: TrainTrip,
    ticket: Ticket?,
    createTracking: () -> Unit,
    trackExisting: (() -> Unit)?,
) {
    val delay = trip.overallDelayMinutes
    val normalizedStatus = trip.status?.replace('_', ' ')?.lowercase()?.replaceFirstChar { it.uppercase() }
    val (statusContainer, statusContent) = when {
        normalizedStatus.orEmpty().contains("complete", ignoreCase = true) ||
            normalizedStatus.orEmpty().contains("arrived", ignoreCase = true) ->
            MaterialTheme.colorScheme.secondary to MaterialTheme.colorScheme.onSecondary
        normalizedStatus.orEmpty().contains("transit", ignoreCase = true) ->
            MaterialTheme.colorScheme.tertiary to MaterialTheme.colorScheme.onTertiary
        normalizedStatus.orEmpty().contains("station", ignoreCase = true) ->
            MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        shape = RoundedCornerShape(40.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(trip.serviceName ?: "Train", style = MaterialTheme.typography.labelLarge)
            Text(trip.trainNumber, style = MaterialTheme.typography.displaySmall)
            Text(
                "${trip.stops.firstOrNull()?.station?.name ?: "Origin"} → " +
                    (trip.stops.lastOrNull()?.station?.name ?: "Destination"),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    delay == null -> StatusPill(
                        "Delay unknown",
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    delay > 0 -> StatusPill(
                        "$delay min late",
                        if (delay >= 10) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                        if (delay >= 10) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onTertiary,
                    )
                    else -> StatusPill(
                        "On time",
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.onPrimary,
                    )
                }
                normalizedStatus?.let { StatusPill(it, statusContainer, statusContent) }
            }
            StatusPill(
                text = LocalDate.parse(trip.serviceDate).format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                icon = R.drawable.ic_calendar,
            )
            ticket?.let {
                val seatText = listOfNotNull(
                    it.carriage?.let { c -> "Carriage $c" },
                    it.seat?.let { seat -> "Seat $seat" },
                ).joinToString(" · ")
                if (seatText.isNotBlank()) {
                    StatusPill(
                        seatText,
                        MaterialTheme.colorScheme.tertiaryContainer,
                        MaterialTheme.colorScheme.onTertiaryContainer,
                        icon = R.drawable.ic_seat,
                    )
                }
            }
            Button(onClick = trackExisting ?: createTracking) {
                Text(if (trackExisting != null) "Track this ticket" else "Choose passenger segment")
            }
        }
    }
}

@Composable
private fun CreateTrackingTicketDialog(
    trip: TrainTrip,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String) -> Unit,
) {
    var origin by rememberSaveable { mutableStateOf(trip.stops.firstOrNull()?.station?.code.orEmpty()) }
    var destination by rememberSaveable { mutableStateOf(trip.stops.lastOrNull()?.station?.code.orEmpty()) }
    var carriage by rememberSaveable { mutableStateOf("") }
    var seat by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Track passenger journey") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Use stable station codes from the calling-point list. Origin must appear before destination.")
                OutlinedTextField(origin, { origin = it }, label = { Text("Origin station code") }, singleLine = true)
                OutlinedTextField(destination, { destination = it }, label = { Text("Destination station code") }, singleLine = true)
                OutlinedTextField(carriage, { carriage = it }, label = { Text("Carriage (optional)") }, singleLine = true)
                OutlinedTextField(seat, { seat = it }, label = { Text("Seat (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(origin.trim(), destination.trim(), carriage, seat) },
                enabled = origin.isNotBlank() && destination.isNotBlank() && origin != destination,
            ) { Text("Save and track") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TicketsScreen(
    state: MainUiState,
    viewModel: MainViewModel,
    startTracking: (Ticket) -> Unit,
    importFromInbox: () -> Unit,
    importSmsText: (String) -> Unit,
) {
    var add by remember { mutableStateOf(false) }
    var ticketLimit by rememberSaveable { mutableIntStateOf(20) }
    var ticketFilter by rememberSaveable { mutableStateOf(TicketFilter.FUTURE) }
    var pasteSms by remember { mutableStateOf(false) }
    var showInboxDisclosure by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp, 20.dp, 20.dp, 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(32.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Import CP ticket SMS", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Check messages sent by CP, or paste/share a ticket SMS. Inbox access is requested only after you choose Check CP SMS.",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { showInboxDisclosure = true }, enabled = !state.smsImporting) {
                                Text(if (state.smsImporting) "Checking…" else "Check CP SMS")
                            }
                            OutlinedButton(
                                onClick = { pasteSms = true },
                                enabled = !state.smsImporting,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f)),
                            ) {
                                Text("Paste SMS")
                            }
                        }
                    }
                }
            }
            val today = LocalDate.now()
            val filteredTickets = state.tickets.filter {
                val isFuture = it.isUpcoming(today)
                isFuture == (ticketFilter == TicketFilter.FUTURE)
            }
            val visibleTickets = filteredTickets.take(ticketLimit)
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = ticketFilter == TicketFilter.FUTURE,
                        onClick = { ticketFilter = TicketFilter.FUTURE; ticketLimit = 20 },
                        label = { Text("Future") },
                    )
                    FilterChip(
                        selected = ticketFilter == TicketFilter.PAST,
                        onClick = { ticketFilter = TicketFilter.PAST; ticketLimit = 20 },
                        label = { Text("Past") },
                    )
                }
            }
            if (state.tickets.isEmpty()) {
                item {
                    ExpressiveInfoCard(
                        "No tickets",
                        "Import a CP SMS or add a trip manually. Round-trip SMS messages create one ticket entry for each train leg.",
                    )
                }
            }
            if (visibleTickets.isNotEmpty()) {
                item {
                    SectionHeader(
                        if (ticketFilter == TicketFilter.FUTURE) "Future tickets" else "Past tickets",
                        visibleTickets.size,
                    )
                }
            }
            items(visibleTickets, key = { it.id }) { ticket ->
                TicketCard(
                    ticket,
                    onOpen = { viewModel.navigate(AppScreen.TripScreen(ticket.trainNumber, ticket.serviceDate, ticket.id)) },
                    onTrack = { startTracking(ticket) },
                    onDelete = { viewModel.deleteTicket(ticket.id) },
                )
            }
            if (visibleTickets.size < filteredTickets.size) {
                item {
                    OutlinedButton(
                        onClick = { ticketLimit += 20 },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Load 20 more tickets") }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { add = true },
            icon = { Icon(painterResource(R.drawable.ic_ticket), null) },
            text = { Text("Add ticket") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
    }
    if (add) ManualTicketDialog({ add = false }) { ticket -> viewModel.saveTicket(ticket); add = false }
    if (pasteSms) {
        SmsPasteDialog(
            onDismiss = { pasteSms = false },
            onImport = { text -> importSmsText(text); pasteSms = false },
        )
    }
    if (showInboxDisclosure) {
        AlertDialog(
            onDismissRequest = { showInboxDisclosure = false },
            title = { Text("Read CP ticket SMS") },
            text = {
                Text(
                                "The first inbox check reads a bounded recent window. Later checks stop as soon as they reach the most recently imported ticket, " +
                        "and retain only messages whose sender or content matches a CP ticket before parsing locally. SMS content is not uploaded. This permission is hard-restricted and " +
                        "works only when the installer or device policy allows it.",
                )
            },
            confirmButton = {
                Button(onClick = { showInboxDisclosure = false; importFromInbox() }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { showInboxDisclosure = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TicketCard(ticket: Ticket, onOpen: () -> Unit, onTrack: () -> Unit, onDelete: () -> Unit) {
    val outbound = ticket.direction != TicketDirection.RETURN
    val containerColor = if (outbound) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (outbound) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painterResource(R.drawable.ic_ticket), null, modifier = Modifier.size(32.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${ticket.direction.labelPrefix()}${ticket.serviceLabel ?: "Train"} ${ticket.trainNumber}",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        listOfNotNull(runCatching {
                            LocalDate.parse(ticket.serviceDate).format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))
                        }.getOrDefault(ticket.serviceDate), ticket.departureTimingLabel())
                            .joinToString(" · "),
                        color = contentColor.copy(alpha = .75f),
                    )
                }
            }
            Text("${ticket.originName ?: ticket.originStationCode} → ${ticket.destinationName ?: ticket.destinationStationCode}")
            val seatText = listOfNotNull(
                ticket.carriage?.let { "Carriage $it" },
                ticket.seat?.let { "Seat $it" },
            ).joinToString(" · ")
            if (seatText.isNotBlank()) {
                StatusPill(
                    seatText,
                    MaterialTheme.colorScheme.tertiary,
                    MaterialTheme.colorScheme.onTertiary,
                    icon = R.drawable.ic_seat,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTrack) { Text("Track") }
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
                ) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun SmsPasteDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste CP ticket SMS") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Paste the complete message, including the Ida/Volta journey and Bilhete section.")
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("SMS text") },
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onImport(text) }, enabled = text.isNotBlank()) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ServiceDateField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    val selectedDate = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
    var showPicker by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { showPicker = true },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Icon(painterResource(R.drawable.ic_calendar), contentDescription = null)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                selectedDate.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text("Change", color = MaterialTheme.colorScheme.primary)
    }

    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            val date = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            onValueChange(date.toString())
                        }
                        showPicker = false
                    },
                    enabled = pickerState.selectedDateMillis != null,
                ) { Text("Select") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun ManualTicketDialog(onDismiss: () -> Unit, onSave: (Ticket) -> Unit) {
    var train by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var origin by rememberSaveable { mutableStateOf("") }
    var destination by rememberSaveable { mutableStateOf("") }
    var carriage by rememberSaveable { mutableStateOf("") }
    var seat by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add future ticket") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedTextField(train, { train = it }, label = { Text("Train number") }, singleLine = true) }
                item { ServiceDateField(date, { date = it }, "Service date") }
                item { OutlinedTextField(origin, { origin = it }, label = { Text("Origin station code") }, singleLine = true) }
                item { OutlinedTextField(destination, { destination = it }, label = { Text("Destination station code") }, singleLine = true) }
                item { OutlinedTextField(carriage, { carriage = it }, label = { Text("Carriage") }, singleLine = true) }
                item { OutlinedTextField(seat, { seat = it }, label = { Text("Seat") }, singleLine = true) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        Ticket(
                            id = UUID.randomUUID().toString(),
                            trainNumber = train.trim(),
                            serviceDate = date.trim(),
                            originStationCode = origin.trim(),
                            destinationStationCode = destination.trim(),
                            carriage = carriage.trim().ifBlank { null },
                            seat = seat.trim().ifBlank { null },
                        ),
                    )
                },
                enabled = train.isNotBlank() && origin.isNotBlank() && destination.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SettingsScreen(
    smsAutoScan: Boolean,
    setSmsAutoScan: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var notificationAccessEnabled by remember { mutableStateOf(isNotificationListenerEnabled(context)) }
    var showNotificationDisclosure by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationAccessEnabled = isNotificationListenerEnabled(context)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Automatic CP access", style = MaterialTheme.typography.headlineMedium)
            Text(
                "No API setup is required. CP Companion obtains the current service URLs and request headers " +
                    "from https://www.cp.pt/fe-config.json, caches them for offline reuse, refreshes them daily, " +
                    "and refreshes immediately after an authorization failure.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            ExpressiveInfoCard(
                "Zero-configuration timetable access",
                "Station catalogues, train catalogues, boards, station details, and train trips use CP's current " +
                    "public website configuration automatically. There are no API keys or gateway fields to enter.",
            )
        }
        item {
            Text("SMS ticket import", style = MaterialTheme.typography.headlineMedium)
        }
        item {
            Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Check CP SMS when app opens", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Opt-in inbox scanning. The first check reads a bounded recent inbox window; later checks stop at the last imported ticket.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = smsAutoScan,
                        onCheckedChange = setSmsAutoScan,
                    )
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Detect new CP SMS notifications", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (notificationAccessEnabled) {
                            "Enabled. New message notifications from recognized CP sender titles are parsed locally."
                        } else {
                            "Optional. Enable notification access to import new CP ticket messages automatically without READ_SMS."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { showNotificationDisclosure = true }) {
                        Text(if (notificationAccessEnabled) "Manage access" else "Enable detection")
                    }
                }
            }
        }
        item {
            ExpressiveInfoCard(
                "SMS privacy",
                "Inbox access is requested only when you use inbox import. It retains CP-ticket matches locally and, after the first check, scans only messages newer than the last imported ticket.",
            )
        }
        item {
            ExpressiveInfoCard(
                "Notification compatibility",
                "The tracking service uses Android 16 ProgressStyle and requests promoted ongoing behavior. " +
                    "On older or ineligible devices, it posts a silent standard ongoing BigText notification with the same information and stop action.",
            )
        }
    }
    if (showNotificationDisclosure) {
        AlertDialog(
            onDismissRequest = { showNotificationDisclosure = false },
            title = { Text("Automatic CP SMS detection") },
            text = {
                Text(
                    "Android notification access allows CP Companion to observe notifications from all apps. " +
                        "The implementation ignores non-message notifications and accepts only recognized CP sender titles, " +
                        "parses ticket text locally, and does not upload notification content. You can revoke access at any time.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    showNotificationDisclosure = false
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { showNotificationDisclosure = false }) { Text("Cancel") } },
        )
    }
}

private enum class TripStopVisualState {
    PASSED,
    CURRENT,
    UPCOMING,
    CANCELLED,
}

@Composable
private fun StatusPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    icon: Int? = null,
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(50),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            icon?.let {
                Icon(
                    painter = painterResource(it),
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
            }
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
        }
    }
}

private fun inferCurrentStopIndex(trip: TrainTrip, now: Instant = Instant.now()): Int {
    trip.lastReportedStationCode?.let { code ->
        val index = trip.stops.indexOfFirst { it.station.code == code }
        if (index >= 0) return index
    }

    val serviceDate = runCatching { LocalDate.parse(trip.serviceDate) }.getOrNull() ?: return -1
    var currentDate = serviceDate
    var previousTime: LocalTime? = null
    var lastPassed = -1
    trip.stops.forEachIndexed { index, stop ->
        val text = stop.expectedDeparture ?: stop.expectedArrival
            ?: stop.scheduledDeparture ?: stop.scheduledArrival
            ?: return@forEachIndexed
        val time = runCatching { LocalTime.parse(text.padStart(5, '0')) }.getOrNull()
            ?: return@forEachIndexed
        if (previousTime != null && time.isBefore(previousTime) &&
            java.time.Duration.between(time, previousTime).toHours() > 2
        ) {
            currentDate = currentDate.plusDays(1)
        }
        previousTime = time
        val zone = if (stop.station.code.startsWith("71-")) {
            ZoneId.of("Europe/Madrid")
        } else {
            ZoneId.of("Europe/Lisbon")
        }
        val event = LocalDateTime.of(currentDate, time).atZone(zone).toInstant()
        if (!event.isAfter(now)) lastPassed = index
    }
    return lastPassed
}

@Composable
private fun ExpressiveInfoCard(title: String, body: String) {
    Card(
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Text(count.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun PassengerPhase.label(): String = when (this) {
    PassengerPhase.PRE_TRIP -> "Upcoming"
    PassengerPhase.APPROACHING_ORIGIN -> "Approaching your station"
    PassengerPhase.BOARDING_SOON -> "Boarding soon"
    PassengerPhase.ON_BOARD -> "On board"
    PassengerPhase.APPROACHING_DESTINATION -> "Approaching destination"
    PassengerPhase.ARRIVED -> "Arrived"
    PassengerPhase.CANCELLED -> "Cancelled"
    PassengerPhase.DATA_UNAVAILABLE -> "Updating"
    PassengerPhase.STOPPED -> "Stopped"
}

private fun timeRemaining(epochMillis: Long): String {
    val minutes = ((epochMillis - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
    val hours = minutes / 60
    val remaining = minutes % 60
    return if (hours > 0) "${hours}h ${remaining}m" else "${remaining} min"
}


private fun isNotificationListenerEnabled(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

private fun TicketSource.label(): String = when (this) {
    TicketSource.MANUAL -> "Manual"
    TicketSource.SMS_INBOX -> "CP SMS"
    TicketSource.SMS_NOTIFICATION -> "Detected SMS"
    TicketSource.SHARED_SMS -> "Shared SMS"
}

private fun TicketDirection?.labelPrefix(): String = when (this) {
    TicketDirection.OUTBOUND -> "Outbound · "
    TicketDirection.RETURN -> "Return · "
    null -> ""
}

/** A ticket remains upcoming until its known arrival time; service date alone is insufficient. */
private fun Ticket.isUpcoming(today: LocalDate = LocalDate.now(), nowMillis: Long = System.currentTimeMillis()): Boolean =
    scheduledArrivalEpochMillis?.let { it > nowMillis }
        ?: runCatching { !LocalDate.parse(serviceDate).isBefore(today) }.getOrDefault(true)

private fun Ticket.departureTimingLabel(): String? {
    val scheduled = scheduledDepartureEpochMillis?.let(::timeLabel)
    val expected = expectedDepartureEpochMillis?.let(::timeLabel)
    return when {
        expected != null && scheduled != null && expected != scheduled -> "Expected $expected · scheduled $scheduled"
        expected != null -> "Expected $expected"
        scheduled != null -> "Departs $scheduled"
        else -> null
    }
}

private fun timeLabel(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

private fun String.removeAccents(): String = java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")
