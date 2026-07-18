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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
internal fun SearchScreen(
    state: MainUiState,
    viewModel: MainViewModel,
    navigate: (AppScreen) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var debouncedQuery by rememberSaveable { mutableStateOf("") }
    var serviceDate by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var stationsOnly by rememberSaveable { mutableStateOf(false) }
    var trainsOnly by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.ensureCatalogsLoaded()
    }
    LaunchedEffect(query) {
        delay(200)
        debouncedQuery = query
    }
    val normalized = debouncedQuery.trim().lowercase().removeAccents()
    val stations by produceState(
        initialValue = emptyList<Station>(),
        state.stations,
        state.favoriteStationCodes,
        normalized,
    ) {
        value = withContext(Dispatchers.Default) {
            val matches = if (normalized.isBlank()) {
                state.stations.filter { it.code in state.favoriteStationCodes }
            } else {
                state.stations.filter { it.searchText.contains(normalized) }
            }
            matches.sortedWith(
                compareByDescending<Station> { it.code in state.favoriteStationCodes }
                    .thenBy { it.name.lowercase() },
            ).take(40)
        }
    }
    val trains by produceState(initialValue = emptyList<TrainServiceEntry>(), state.trains, normalized) {
        value = withContext(Dispatchers.Default) {
            if (normalized.isBlank()) {
                emptyList()
            } else {
                state.trains.asSequence()
                    .filter { it.searchText.contains(normalized) }
                    .sortedWith(
                        compareByDescending<TrainServiceEntry> { it.trainNumber == normalized }
                            .thenBy { it.trainNumber.toLongOrNull() ?: Long.MAX_VALUE },
                    )
                    .take(40)
                    .toList()
            }
        }
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
                label = { Text(stringResource(R.string.search_hint)) },
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
                label = stringResource(R.string.train_service_date),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(stationsOnly, { stationsOnly = !stationsOnly; if (stationsOnly) trainsOnly = false }, { Text(stringResource(R.string.stations)) })
                FilterChip(trainsOnly, { trainsOnly = !trainsOnly; if (trainsOnly) stationsOnly = false }, { Text(stringResource(R.string.trains)) })
            }
        }
        if (state.catalogLoading && state.stations.isEmpty() && state.trains.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            }
        }
        if (!trainsOnly) {
            item {
                SectionHeader(
                    if (normalized.isBlank()) {
                        stringResource(R.string.starred_stations)
                    } else {
                        stringResource(R.string.stations)
                    },
                    stations.size,
                )
            }
            items(stations, key = { it.code }) { station ->
                StationResult(
                    station = station,
                    favorite = station.code in state.favoriteStationCodes,
                    onClick = {
                        viewModel.setStationBoardDate(serviceDate)
                        navigate(AppScreen.StationScreen(station))
                    },
                    onToggleFavorite = { viewModel.toggleFavoriteStation(station.code) },
                )
            }
        }
        if (!stationsOnly && normalized.isNotBlank()) {
            item { SectionHeader(stringResource(R.string.trains), trains.size) }
            items(trains, key = { it.key }) { train ->
                TrainResult(train) {
                    val date = LocalDate.parse(serviceDate)
                    navigate(AppScreen.TripScreen(train.trainNumber, date.toString()))
                }
            }
        }
        if (normalized.isNotBlank() && !state.catalogLoading && stations.isEmpty() && trains.isEmpty()) {
            item {
                ExpressiveInfoCard(
                    title = stringResource(R.string.no_search_matches),
                    body = stringResource(R.string.no_search_matches_body),
                )
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
    val favoriteDescription = stringResource(
        if (favorite) R.string.unfavorite_station_description else R.string.favorite_station_description,
    )
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
            TextButton(
                onClick = onToggleFavorite,
                modifier = Modifier.semantics { contentDescription = favoriteDescription },
            ) {
                Text(if (favorite) "★" else "☆", style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}

@Composable
private fun TrainResult(train: TrainServiceEntry, onClick: () -> Unit) {
    val defaultTrainName = stringResource(R.string.train)
    val unknownOrigin = stringResource(R.string.unknown_origin)
    val unknownDestination = stringResource(R.string.unknown_destination)
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(painterResource(R.drawable.ic_train), contentDescription = stringResource(R.string.train_icon_description), modifier = Modifier.size(30.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.service_train_number, train.serviceName ?: defaultTrainName, train.trainNumber),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(
                        R.string.route_between,
                        train.origin?.name ?: unknownOrigin,
                        train.destination?.name ?: unknownDestination,
                    ),
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = .76f),
                )
            }
        }
    }
}
