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

@Composable
internal fun HomeScreen(
    state: MainUiState,
    viewModel: MainViewModel,
    navigate: (AppScreen) -> Unit,
    stopTracking: () -> Unit,
    startTracking: (Ticket) -> Unit,
) {
    val tracking by viewModel.tracking.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(stringResource(R.string.your_rail_journey), style = MaterialTheme.typography.headlineLarge)
            Text(
                stringResource(R.string.home_intro),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            val nextTicket = state.tickets.firstOrNull { ticket ->
                ticket.isUpcoming()
            }
            tracking?.let { snapshot ->
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
                    text = { Text(stringResource(R.string.find_train)) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                ExtendedFloatingActionButton(
                    onClick = { navigate(AppScreen.Tickets) },
                    icon = { Icon(painterResource(R.drawable.ic_ticket), null) },
                    text = { Text(stringResource(R.string.tickets)) },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            ExpressiveInfoCard(
                title = stringResource(R.string.live_update_behavior),
                body = stringResource(R.string.live_update_behavior_body),
            )
        }
        if (state.stations.isEmpty() || state.trains.isEmpty()) {
            item {
                ExpressiveInfoCard(
                    title = stringResource(R.string.catalogues_not_loaded),
                    body = stringResource(R.string.catalogues_not_loaded_body),
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
            Icon(painterResource(R.drawable.ic_train), contentDescription = stringResource(R.string.train_icon_description), modifier = Modifier.size(40.dp))
            if (ticket == null) {
                Text(stringResource(R.string.no_upcoming_ticket), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.no_upcoming_ticket_body))
                Button(onClick = { navigate(AppScreen.Tickets) }) { Text(stringResource(R.string.add_ticket)) }
            } else {
                Text(stringResource(R.string.next_saved_trip), style = MaterialTheme.typography.labelLarge)
                Text(
                    stringResource(
                        R.string.service_train_number,
                        ticket.serviceLabel ?: stringResource(R.string.train),
                        ticket.trainNumber,
                    ),
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    stringResource(
                        R.string.route_between,
                        ticket.originName ?: ticket.originStationCode,
                        ticket.destinationName ?: ticket.destinationStationCode,
                    ),
                )
                Text(
                    listOfNotNull(ticket.serviceDate, ticket.departureTimingLabel()).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f),
                )
                StatusPill(
                    ticket.automationStatusLabel(),
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { startTracking(ticket) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f)),
                    ) { Text(stringResource(R.string.track_now)) }
                    Button(onClick = { navigate(AppScreen.TripScreen(ticket.trainNumber, ticket.serviceDate, ticket.id)) }) {
                        Text(stringResource(R.string.trip))
                    }
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
                        stringResource(
                            R.string.service_train_number,
                            snapshot.serviceLabel ?: stringResource(R.string.train),
                            snapshot.trainNumber,
                        ),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
            }
            Text(stringResource(R.string.route_between, snapshot.originName, snapshot.destinationName))
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
                        if (delay > 0) stringResource(R.string.minutes_late, delay) else stringResource(R.string.on_time),
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
                        stringResource(R.string.platform, it),
                        MaterialTheme.colorScheme.secondary,
                        MaterialTheme.colorScheme.onSecondary,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                snapshot.carriage?.let {
                    StatusPill(
                        stringResource(R.string.carriage_value, it),
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                snapshot.seat?.let {
                    StatusPill(
                        stringResource(R.string.seat_value, it),
                        MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.onSecondaryContainer,
                        icon = R.drawable.ic_seat,
                    )
                }
            }
            OutlinedButton(onClick = stopTracking) { Text(stringResource(R.string.stop_tracking)) }
        }
    }
}
