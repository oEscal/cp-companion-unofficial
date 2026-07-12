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
internal fun StationScreen(state: MainUiState, viewModel: MainViewModel) {
    val screen = state.screen as? AppScreen.StationScreen
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, screen?.station?.code, state.boardDepartures) {
        if (screen == null) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(30_000)
                viewModel.refreshCurrentStationBoard()
            }
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
                FilterChip(state.boardDepartures, { viewModel.setBoardDepartures(true) }, { Text(stringResource(R.string.departures)) })
                FilterChip(!state.boardDepartures, { viewModel.setBoardDepartures(false) }, { Text(stringResource(R.string.arrivals)) })
            }
        }
        if (state.stationBoard.isEmpty() && !state.boardLoading) {
            item { ExpressiveInfoCard("No services", "No upcoming services were returned for the selected board.") }
        }
        items(state.stationBoard, key = { "${it.serviceDate}-${it.trainNumber}-${it.scheduledArrival}-${it.scheduledDeparture}" }) { entry ->
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
                Text("${entry.serviceName ?: stringResource(R.string.train)} ${entry.trainNumber}", style = MaterialTheme.typography.titleMedium)
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
