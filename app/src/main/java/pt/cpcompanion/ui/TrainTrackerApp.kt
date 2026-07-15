@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package pt.cpcompanion.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.res.stringResource
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import pt.cpcompanion.R
import pt.cpcompanion.domain.StationTimeZoneResolver
import pt.cpcompanion.model.PassengerPhase
import pt.cpcompanion.model.Station
import pt.cpcompanion.model.StationBoardEntry
import pt.cpcompanion.model.Ticket
import pt.cpcompanion.model.TicketDirection
import pt.cpcompanion.model.ThemeMode
import pt.cpcompanion.model.TicketActivationMethod
import pt.cpcompanion.model.TicketAutomationState
import pt.cpcompanion.model.TrackingSnapshot
import pt.cpcompanion.model.TrainServiceEntry
import pt.cpcompanion.model.TrainTrip
import pt.cpcompanion.model.isPastPassengerSegment
import pt.cpcompanion.model.normalizeSearchText
import pt.cpcompanion.ui.feature.tickets.TicketsScreen
import pt.cpcompanion.ui.theme.CpExpressiveTheme

private enum class SmsPermissionAction {
    IMPORT_NOW,
    ENABLE_AUTO_SCAN,
}

@Composable
fun TrainTrackerApp(viewModel: MainViewModel) {
    val shell by viewModel.shellState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val notificationPermissionRequiredTracking =
        stringResource(R.string.notification_permission_required_tracking)
    val smsPermissionDeniedFallback =
        stringResource(R.string.sms_permission_denied_fallback)
    val smsPermissionRequiredAutoScan =
        stringResource(R.string.sms_permission_required_auto_scan)
    val navController = rememberNavController()
    var pendingTicket by remember { mutableStateOf<Ticket?>(null) }
    var pendingSmsAction by remember { mutableStateOf<SmsPermissionAction?>(null) }
    var notificationOnboardingRequested by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.refreshAutomationCapabilities()
        if (granted) {
            pendingTicket?.let(viewModel::startTracking)
        } else {
            viewModel.showMessage(notificationPermissionRequiredTracking)
        }
        pendingTicket = null
    }
    val smsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        when (pendingSmsAction) {
            SmsPermissionAction.IMPORT_NOW -> {
                if (granted) viewModel.importSmsTickets()
                else viewModel.showMessage(smsPermissionDeniedFallback)
            }
            SmsPermissionAction.ENABLE_AUTO_SCAN -> {
                if (granted) {
                    viewModel.setSmsAutoScan(true)
                    viewModel.importSmsTickets()
                } else {
                    viewModel.showMessage(smsPermissionRequiredAutoScan)
                }
            }
            null -> Unit
        }
        pendingSmsAction = null
    }

    LaunchedEffect(shell.message) {
        shell.message?.let {
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

    LaunchedEffect(shell.tickets, notificationOnboardingRequested) {
        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            shell.tickets.any { it.automaticTrackingEnabled && it.isUpcoming() }
        if (needsPermission && !notificationOnboardingRequested) {
            notificationOnboardingRequested = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshAutomationCapabilities()
        if (shell.smsImportSettings.automaticSmsImportEnabled &&
            context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.importSmsTickets(silent = true)
        }
    }

    LaunchedEffect(shell.screen) {
        val target = shell.screen.navigationRoute()
        val current = navController.currentDestination?.route
        if (current == target) return@LaunchedEffect

        // The ViewModel is the single source of truth for navigation history.
        // Prefer popping to an existing destination; navigating directly here
        // would push the previous page on top and make back gestures bounce.
        val popped = navController.popBackStack(target, inclusive = false)
        if (!popped) {
            navController.navigate(target) {
                launchSingleTop = true
                restoreState = true
                if (target in TOP_LEVEL_ROUTES) {
                    popUpTo(ROUTE_HOME) { saveState = true }
                }
            }
        }
    }

    CpExpressiveTheme(themeMode = shell.themeMode) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                AppTopBar(
                    screen = shell.screen,
                    canGoBack = shell.canGoBack,
                    onBack = viewModel::back,
                    onRefresh = viewModel::refreshCatalogs,
                )
            },
            bottomBar = {
                if (shell.screen is AppScreen.Home || shell.screen is AppScreen.Search ||
                    shell.screen is AppScreen.Tickets || shell.screen is AppScreen.Settings
                ) {
                    MainNavigation(shell.screen, viewModel::navigate)
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                NavHost(
                    navController = navController,
                    startDestination = ROUTE_HOME,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    composable(ROUTE_HOME) {
                        val state by viewModel.state.collectAsStateWithLifecycle()
                        ScreenPullToRefresh(state, viewModel) {
                            HomeScreen(state, viewModel, viewModel::navigate, viewModel::stopTracking, ::requestTracking)
                        }
                    }
                    composable(ROUTE_SEARCH) {
                        val state by viewModel.state.collectAsStateWithLifecycle()
                        ScreenPullToRefresh(state, viewModel) {
                            SearchScreen(state, viewModel, viewModel::navigate)
                        }
                    }
                    composable(ROUTE_TICKETS) {
                        val state by viewModel.state.collectAsStateWithLifecycle()
                        ScreenPullToRefresh(state, viewModel) {
                            TicketsScreen(
                                state = state,
                                viewModel = viewModel,
                                startTracking = ::requestTracking,
                                importFromInbox = ::requestSmsImport,
                                importSmsText = viewModel::importSmsText,
                            )
                        }
                    }
                    composable(ROUTE_SETTINGS) {
                        val state by viewModel.state.collectAsStateWithLifecycle()
                        ScreenPullToRefresh(state, viewModel) {
                            SettingsScreen(
                                state = state,
                                setSmsAutoScan = ::setSmsAutoScan,
                                setThemeMode = viewModel::setThemeMode,
                                refreshDiagnostics = viewModel::refreshDiagnostics,
                            )
                        }
                    }
                    composable(ROUTE_STATION) {
                        val state by viewModel.state.collectAsStateWithLifecycle()
                        ScreenPullToRefresh(state, viewModel) { StationScreen(state, viewModel) }
                    }
                    composable(ROUTE_TRIP) {
                        val state by viewModel.state.collectAsStateWithLifecycle()
                        val ticketId = (shell.screen as? AppScreen.TripScreen)?.ticketId
                        ScreenPullToRefresh(state, viewModel) {
                            TripScreen(state, ticketId, viewModel, ::requestTracking)
                        }
                    }
                }

                // NavHost installs its own back callback. Register the app-level
                // callback after NavHost so it wins and updates the ViewModel
                // history before the NavController is synchronized. Otherwise a
                // gesture can pop only the visual route, leaving the train title
                // and bottom-bar state stuck on the previous detail screen.
                BackHandler(enabled = shell.canGoBack) {
                    viewModel.back()
                }
            }
        }
    }
}

@Composable
private fun ScreenPullToRefresh(
    state: MainUiState,
    viewModel: MainViewModel,
    content: @Composable () -> Unit,
) {
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
        content()
    }
}

private fun AppScreen.navigationRoute(): String = when (this) {
    AppScreen.Home -> ROUTE_HOME
    AppScreen.Search -> ROUTE_SEARCH
    AppScreen.Tickets -> ROUTE_TICKETS
    AppScreen.Settings -> ROUTE_SETTINGS
    is AppScreen.StationScreen -> ROUTE_STATION
    is AppScreen.TripScreen -> ROUTE_TRIP
}

private const val ROUTE_HOME = "home"
private const val ROUTE_SEARCH = "search"
private const val ROUTE_TICKETS = "tickets"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_STATION = "station"
private const val ROUTE_TRIP = "trip"
private val TOP_LEVEL_ROUTES = setOf(ROUTE_HOME, ROUTE_SEARCH, ROUTE_TICKETS, ROUTE_SETTINGS)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(
    screen: AppScreen,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    val title = when (screen) {
        AppScreen.Home -> stringResource(R.string.app_name)
        AppScreen.Search -> stringResource(R.string.search)
        AppScreen.Tickets -> stringResource(R.string.tickets)
        AppScreen.Settings -> stringResource(R.string.automation)
        is AppScreen.StationScreen -> screen.station.name
        is AppScreen.TripScreen -> stringResource(R.string.train_number, screen.trainNumber)
    }
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (canGoBack && (screen is AppScreen.StationScreen || screen is AppScreen.TripScreen)) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back))
                }
            }
        },
        actions = {
            if (screen is AppScreen.Search) TextButton(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}

@Composable
private fun MainNavigation(screen: AppScreen, navigate: (AppScreen) -> Unit) {
    val destinations = listOf(
        Triple(AppScreen.Home, R.drawable.ic_home, stringResource(R.string.home)),
        Triple(AppScreen.Search, R.drawable.ic_search, stringResource(R.string.search)),
        Triple(AppScreen.Tickets, R.drawable.ic_ticket, stringResource(R.string.tickets)),
        Triple(AppScreen.Settings, R.drawable.ic_settings, stringResource(R.string.automation)),
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
internal fun SmsPasteDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.paste_cp_ticket_sms)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.paste_cp_ticket_sms_body))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.sms_text)) },
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onImport(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.import_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun ServiceDateField(
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
        Text(stringResource(R.string.change), color = MaterialTheme.colorScheme.primary)
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
                ) { Text(stringResource(R.string.select)) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.cancel)) }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

internal data class SearchableDropdownOption(
    val key: String,
    val label: String,
    val searchText: String = label,
)

@Composable
internal fun SearchableDropdownField(
    label: String,
    selectedKey: String?,
    options: List<SearchableDropdownOption>,
    onSelected: (SearchableDropdownOption?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingText: String? = null,
    stateKey: Any? = Unit,
    minimumQueryLength: Int = 0,
    maximumDisplayedOptions: Int = 100,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.key == selectedKey }
    var query by rememberSaveable(stateKey) { mutableStateOf(selected?.label.orEmpty()) }

    LaunchedEffect(selected?.label) {
        if (selected != null && query != selected.label) query = selected.label
    }

    val normalizedQuery = normalizeSearchText(query)
    val queryIsLongEnough = normalizedQuery.length >= minimumQueryLength
    val filtered = remember(
        options,
        normalizedQuery,
        minimumQueryLength,
        maximumDisplayedOptions,
    ) {
        if (normalizedQuery.length < minimumQueryLength) {
            emptyList()
        } else {
            options.asSequence()
                .filter { option ->
                    normalizedQuery.isBlank() ||
                        normalizeSearchText(option.label).contains(normalizedQuery) ||
                        normalizeSearchText(option.searchText).contains(normalizedQuery) ||
                        normalizeSearchText(option.key).contains(normalizedQuery)
                }
                .take(maximumDisplayedOptions.coerceAtLeast(1))
                .toList()
        }
    }
    val menuExpanded = expanded && enabled && queryIsLongEnough

    ExposedDropdownMenuBox(
        expanded = menuExpanded,
        onExpandedChange = { requestedExpanded ->
            if (enabled) expanded = requestedExpanded && queryIsLongEnough
        },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { value ->
                query = value
                if (selected?.label != value) onSelected(null)
                expanded = normalizeSearchText(value).length >= minimumQueryLength
            },
            label = { Text(label) },
            supportingText = supportingText?.let { text -> { Text(text) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { expanded = false },
        ) {
            filtered.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        query = option.label
                        onSelected(option)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
            if (filtered.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.no_matching_options)) },
                    onClick = {},
                    enabled = false,
                )
            }
        }
    }
}

@Composable
internal fun ManualTicketDialog(
    trains: List<TrainServiceEntry>,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onSave: (Ticket) -> Unit,
) {
    var selectedTrainKey by rememberSaveable { mutableStateOf<String?>(null) }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var selectedOriginCode by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDestinationCode by rememberSaveable { mutableStateOf<String?>(null) }
    var carriage by rememberSaveable { mutableStateOf("") }
    var seat by rememberSaveable { mutableStateOf("") }
    var route by remember { mutableStateOf<TrainTrip?>(null) }
    var routeLoading by remember { mutableStateOf(false) }

    val trainOptions = remember(trains) {
        trains.map { train ->
            val routeLabel = listOfNotNull(train.origin?.name, train.destination?.name)
                .joinToString(" → ")
                .takeIf(String::isNotBlank)
            SearchableDropdownOption(
                key = train.key,
                label = buildString {
                    append(train.trainNumber)
                    train.serviceName?.takeIf(String::isNotBlank)?.let { append(" · ").append(it) }
                    routeLabel?.let { append(" · ").append(it) }
                },
                searchText = listOfNotNull(
                    train.trainNumber,
                    train.serviceCode,
                    train.serviceName,
                    train.origin?.name,
                    train.origin?.code,
                    train.destination?.name,
                    train.destination?.code,
                ).joinToString(" "),
            )
        }
    }
    val selectedTrain = trains.firstOrNull { it.key == selectedTrainKey }

    LaunchedEffect(selectedTrain?.trainNumber, date) {
        route = null
        routeLoading = false
        selectedOriginCode = null
        selectedDestinationCode = null
        val trainNumber = selectedTrain?.trainNumber ?: return@LaunchedEffect
        routeLoading = true
        route = viewModel.loadTripForTicketForm(trainNumber, date)
        routeLoading = false
    }

    val stops = route?.stops.orEmpty()
    val originOptions = stops.dropLast(1).map { stop ->
        SearchableDropdownOption(
            key = stop.station.code,
            label = stop.station.name,
            searchText = "${stop.station.name} ${stop.station.code}",
        )
    }
    val originIndex = stops.indexOfFirst { it.station.code == selectedOriginCode }
    val destinationOptions = if (originIndex >= 0) {
        stops.drop(originIndex + 1).map { stop ->
            SearchableDropdownOption(
                key = stop.station.code,
                label = stop.station.name,
                searchText = "${stop.station.name} ${stop.station.code}",
            )
        }
    } else {
        emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_future_ticket)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    SearchableDropdownField(
                        label = stringResource(R.string.train_number_label),
                        selectedKey = selectedTrainKey,
                        options = trainOptions,
                        onSelected = { option -> selectedTrainKey = option?.key },
                        supportingText = stringResource(R.string.type_two_characters_to_search_trains),
                        minimumQueryLength = 2,
                        maximumDisplayedOptions = 30,
                    )
                }
                item { ServiceDateField(date, { date = it }, stringResource(R.string.service_date)) }
                item {
                    SearchableDropdownField(
                        label = stringResource(R.string.origin_station),
                        selectedKey = selectedOriginCode,
                        options = originOptions,
                        onSelected = { option ->
                            selectedOriginCode = option?.key
                            selectedDestinationCode = null
                        },
                        enabled = route != null && !routeLoading,
                        supportingText = when {
                            routeLoading -> stringResource(R.string.loading_train_route)
                            selectedTrain != null && route == null -> stringResource(R.string.train_route_unavailable)
                            else -> null
                        },
                        stateKey = "${selectedTrainKey.orEmpty()}|$date",
                    )
                }
                item {
                    SearchableDropdownField(
                        label = stringResource(R.string.destination_station),
                        selectedKey = selectedDestinationCode,
                        options = destinationOptions,
                        onSelected = { option -> selectedDestinationCode = option?.key },
                        enabled = selectedOriginCode != null && destinationOptions.isNotEmpty(),
                        stateKey = selectedOriginCode,
                    )
                }
                item { OutlinedTextField(carriage, { carriage = it }, label = { Text(stringResource(R.string.carriage)) }, singleLine = true) }
                item { OutlinedTextField(seat, { seat = it }, label = { Text(stringResource(R.string.seat)) }, singleLine = true) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val train = selectedTrain ?: return@Button
                    val selectedRoute = route ?: return@Button
                    val origin = selectedRoute.stops.first { it.station.code == selectedOriginCode }
                    val destination = selectedRoute.stops.first { it.station.code == selectedDestinationCode }
                    onSave(
                        Ticket(
                            id = UUID.randomUUID().toString(),
                            trainNumber = train.trainNumber,
                            serviceLabel = selectedRoute.serviceName ?: train.serviceName,
                            serviceDate = date,
                            originStationCode = origin.station.code,
                            destinationStationCode = destination.station.code,
                            originName = origin.station.name,
                            destinationName = destination.station.name,
                            carriage = carriage.trim().ifBlank { null },
                            seat = seat.trim().ifBlank { null },
                        ),
                    )
                },
                enabled = selectedTrain != null && route != null &&
                    selectedOriginCode != null && selectedDestinationCode != null,
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun StatusPill(
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

internal fun inferCurrentStopIndex(trip: TrainTrip, now: Instant = Instant.now()): Int {
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
internal fun ExpressiveInfoCard(title: String, body: String) {
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
internal fun SectionHeader(title: String, count: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Text(count.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PassengerPhase.label(): String = stringResource(
    when (this) {
        PassengerPhase.PRE_TRIP -> R.string.phase_pre_trip
        PassengerPhase.APPROACHING_ORIGIN -> R.string.phase_approaching_origin
        PassengerPhase.BOARDING_SOON -> R.string.phase_boarding
        PassengerPhase.ON_BOARD -> R.string.phase_on_board
        PassengerPhase.APPROACHING_DESTINATION -> R.string.phase_approaching
        PassengerPhase.ARRIVED -> R.string.phase_arrived
        PassengerPhase.CANCELLED -> R.string.phase_cancelled
        PassengerPhase.DATA_UNAVAILABLE -> R.string.phase_unavailable
        PassengerPhase.STOPPED -> R.string.phase_stopped
    },
)

@Composable
internal fun timeRemaining(epochMillis: Long): String {
    val minutes = ((epochMillis - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0L).toInt()
    return if (minutes < 60) {
        pluralStringResource(R.plurals.minutes_remaining, minutes, minutes)
    } else {
        val hours = (minutes / 60f).toInt().coerceAtLeast(1)
        pluralStringResource(R.plurals.hours_remaining, hours, hours)
    }
}

internal fun isNotificationListenerEnabled(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)


@Composable
internal fun TicketDirection?.labelPrefix(): String = when (this) {
    TicketDirection.OUTBOUND -> stringResource(R.string.outbound_prefix)
    TicketDirection.RETURN -> stringResource(R.string.return_prefix)
    null -> ""
}

/** A ticket remains upcoming until its known arrival time; service date alone is insufficient. */
internal fun Ticket.isUpcoming(today: LocalDate = LocalDate.now(), nowMillis: Long = System.currentTimeMillis()): Boolean =
    scheduledArrivalEpochMillis?.let { it > nowMillis }
        ?: runCatching { !LocalDate.parse(serviceDate).isBefore(today) }.getOrDefault(true)

@Composable
internal fun Ticket.departureTimingLabel(): String? =
    scheduledDepartureEpochMillis?.let { stringResource(R.string.departs_at, timeLabel(it, originStationCode)) }

@Composable
internal fun Ticket.automationStatusLabel(): String = when {
    !automaticTrackingEnabled || automationState == TicketAutomationState.DISABLED -> stringResource(R.string.automation_disabled)
    automationState == TicketAutomationState.SCHEDULED -> {
        val at = activationEpochMillis?.let { timeLabel(it, originStationCode) } ?: "T-60"
        val method = stringResource(
            when (activationMethod) {
                TicketActivationMethod.EXACT_ALARM -> R.string.exact_alarm
                TicketActivationMethod.INEXACT_ALARM -> R.string.alarm_recovery
                TicketActivationMethod.WORK_MANAGER_FALLBACK -> R.string.workmanager_recovery
                TicketActivationMethod.IMMEDIATE -> R.string.starting_now
                TicketActivationMethod.NONE -> R.string.pending_validation
            },
        )
        stringResource(R.string.automation_scheduled, at, method)
    }
    automationState == TicketAutomationState.STARTING -> stringResource(R.string.automation_starting)
    automationState == TicketAutomationState.TRACKING -> stringResource(R.string.automation_active)
    automationState == TicketAutomationState.COMPLETED -> stringResource(R.string.automation_completed)
    automationState == TicketAutomationState.BLOCKED_NOTIFICATION_PERMISSION -> stringResource(R.string.automation_blocked_notifications)
    automationState == TicketAutomationState.BLOCKED_EXACT_ALARM_PERMISSION -> stringResource(R.string.automation_degraded_timing)
    automationState == TicketAutomationState.CONFLICT_WITH_OTHER_TRIP -> stringResource(R.string.automation_queued)
    automationState == TicketAutomationState.FAILED -> stringResource(R.string.automation_failed)
    else -> stringResource(R.string.automation_validating)
}

internal fun timeLabel(epochMillis: Long, stationCode: String): String =
    DateTimeFormatter.ofPattern("HH:mm").format(
        Instant.ofEpochMilli(epochMillis).atZone(StationTimeZoneResolver.resolve(stationCode)),
    )

internal fun String.removeAccents(): String = java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")
