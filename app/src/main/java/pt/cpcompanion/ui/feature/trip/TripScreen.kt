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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
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
import pt.cpcompanion.model.TicketSource
import pt.cpcompanion.model.TrackingSnapshot
import pt.cpcompanion.model.TrainServiceEntry
import pt.cpcompanion.model.TrainTrip
import pt.cpcompanion.model.isPastPassengerSegment
import pt.cpcompanion.ui.feature.tickets.TicketsScreen
import pt.cpcompanion.ui.theme.CpExpressiveTheme

internal enum class TripStopVisualState {
    PASSED,
    CURRENT,
    UPCOMING,
    CANCELLED,
}

@Composable
internal fun TripScreen(
    state: MainUiState,
    ticketId: String?,
    viewModel: MainViewModel,
    startTracking: (Ticket) -> Unit,
) {
    val trip = state.selectedTrip
    if (trip == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (state.tripLoading) LoadingIndicator() else Text(stringResource(R.string.trip_unavailable))
        }
        return
    }
    val existingTicket = state.tickets.firstOrNull { it.id == ticketId }
    var showTrackingDialog by remember { mutableStateOf(false) }
    val tracking by viewModel.tracking.collectAsStateWithLifecycle()
    val activeSnapshot = tracking?.takeIf {
        it.trainNumber == trip.trainNumber && it.serviceDate == trip.serviceDate
    }
    val completed = activeSnapshot?.phase == PassengerPhase.ARRIVED ||
        trip.status.orEmpty().contains("complete", ignoreCase = true) ||
        trip.status.orEmpty().contains("arrived", ignoreCase = true)
    val currentStopIndex = if (completed) trip.stops.size else activeSnapshot?.currentStopIndex
        ?: remember(trip) { inferCurrentStopIndex(trip) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner, trip.trainNumber, trip.serviceDate, activeSnapshot?.ticketId) {
        if (activeSnapshot != null) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(30_000)
                viewModel.refreshCurrentTripSilently()
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { TripHero(trip, existingTicket, { showTrackingDialog = true }, existingTicket?.let { { startTracking(it) } }) }
        if (activeSnapshot != null) {
            item {
                ExpressiveInfoCard(
                    "Shared live session",
                    "This screen observes the foreground tracking session and does not create a second trip poller.",
                )
            }
        }
        if (trip.dataStale) {
            item {
                ExpressiveInfoCard(
                    "Rate limited",
                    trip.cooldownUntilEpochMillis?.let {
                        "Showing the last successful trip response. CP requests resume at ${timeLabel(it, trip.stops.firstOrNull()?.station?.code.orEmpty())}."
                    } ?: "Showing the last successful trip response until CP requests can resume.",
                )
            }
        }
        if (trip.messages.isNotEmpty()) item { ExpressiveInfoCard("Service message", trip.messages.joinToString("\n")) }
        item { SectionHeader("Calling points", trip.stops.size) }
        itemsIndexed(trip.stops, key = { index, stop -> "$index-${stop.station.code}" }) { index, stop ->
            val visualState = when {
                stop.isSuppressed -> TripStopVisualState.CANCELLED
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
                        contentDescription = when (visualState) {
                            TripStopVisualState.CURRENT -> "Current stop"
                            TripStopVisualState.PASSED -> "Passed stop"
                            TripStopVisualState.CANCELLED -> "Suppressed stop"
                            TripStopVisualState.UPCOMING -> "Upcoming stop"
                        },
                        tint = contentColor,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stop.station.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            when (visualState) {
                                TripStopVisualState.CURRENT -> StatusPill("Current", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary)
                                TripStopVisualState.PASSED -> StatusPill("Passed", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
                                TripStopVisualState.CANCELLED -> StatusPill("Stop skipped", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError)
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
                                StatusPill("Your origin", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                            }
                            if (existingTicket?.destinationStationCode == stop.station.code) {
                                StatusPill("Your destination", MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.onSecondary)
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
                if (viewModel.createTicketForTrip(trip, origin, destination, carriage, seat) != null) {
                    showTrackingDialog = false
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
            Text("${trip.serviceName ?: stringResource(R.string.train)} ${trip.trainNumber}", style = MaterialTheme.typography.displaySmall)
            Text(
                "${trip.stops.firstOrNull()?.station?.name ?: "Origin"} → ${trip.stops.lastOrNull()?.station?.name ?: "Destination"}",
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    delay == null -> StatusPill("Delay unknown", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
                    delay > 0 -> StatusPill(
                        "$delay min late",
                        if (delay >= 10) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                        if (delay >= 10) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onTertiary,
                    )
                    else -> StatusPill("On time", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
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
                Text(it.automationStatusLabel(), color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .8f))
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
            if (ticket == null) {
                Button(onClick = createTracking) { Text(stringResource(R.string.save_passenger_segment)) }
            } else {
                OutlinedButton(
                    onClick = trackExisting ?: {},
                    enabled = trackExisting != null,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                ) { Text(stringResource(R.string.track_now)) }
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
        title = { Text(stringResource(R.string.save_passenger_journey)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.save_passenger_journey_body))
                OutlinedTextField(origin, { origin = it }, label = { Text(stringResource(R.string.origin_station_code)) }, singleLine = true)
                OutlinedTextField(destination, { destination = it }, label = { Text(stringResource(R.string.destination_station_code)) }, singleLine = true)
                OutlinedTextField(carriage, { carriage = it }, label = { Text(stringResource(R.string.carriage_optional)) }, singleLine = true)
                OutlinedTextField(seat, { seat = it }, label = { Text(stringResource(R.string.seat_optional)) }, singleLine = true)
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(origin.trim(), destination.trim(), carriage, seat) },
                enabled = origin.isNotBlank() && destination.isNotBlank() && origin != destination,
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
