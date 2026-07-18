@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package pt.cpcompanion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.delay
import pt.cpcompanion.R
import pt.cpcompanion.domain.StationTimeZoneResolver
import pt.cpcompanion.model.StationBoardEntry

@Composable
internal fun StationScreen(state: MainUiState, viewModel: MainViewModel) {
    val screen = state.screen as? AppScreen.StationScreen
    val lifecycleOwner = LocalLifecycleOwner.current
    var query by rememberSaveable(screen?.station?.code) { mutableStateOf("") }
    val normalizedQuery = query.trim().lowercase().removeAccents()
    val visibleEntries = remember(state.stationBoard, normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            state.stationBoard
        } else {
            state.stationBoard.filter { it.matches(normalizedQuery) }
        }
    }
    val stationZone = screen?.station?.let { StationTimeZoneResolver.resolve(it) } ?: ZoneId.systemDefault()

    LaunchedEffect(lifecycleOwner, screen?.station?.code, state.boardDepartures, state.stationBoardDate) {
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
            ServiceDateField(
                value = state.stationBoardDate,
                onValueChange = viewModel::setStationBoardDate,
                label = stringResource(R.string.service_date),
            )
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.search_station_trips)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.boardDepartures,
                    onClick = { viewModel.setBoardDepartures(true) },
                    label = { Text(stringResource(R.string.departures)) },
                )
                FilterChip(
                    selected = !state.boardDepartures,
                    onClick = { viewModel.setBoardDepartures(false) },
                    label = { Text(stringResource(R.string.arrivals)) },
                )
            }
        }
        if (state.boardLoading && state.stationBoard.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingIndicator()
                }
            }
        }
        if (visibleEntries.isEmpty() && !state.boardLoading) {
            item {
                ExpressiveInfoCard(
                    title = if (normalizedQuery.isBlank()) {
                        stringResource(R.string.no_services)
                    } else {
                        stringResource(R.string.no_matching_trips)
                    },
                    body = if (normalizedQuery.isBlank()) {
                        stringResource(R.string.no_services_for_date)
                    } else {
                        stringResource(R.string.no_matching_trips_body)
                    },
                )
            }
        }
        items(
            items = visibleEntries,
            key = { "${it.serviceDate}-${it.trainNumber}-${it.scheduledArrival}-${it.scheduledDeparture}" },
        ) { entry ->
            BoardEntryCard(
                entry = entry,
                departures = state.boardDepartures,
                stationZone = stationZone,
                onClick = { viewModel.openBoardTrip(entry) },
            )
        }
    }
}

@Composable
private fun BoardEntryCard(
    entry: StationBoardEntry,
    departures: Boolean,
    stationZone: ZoneId,
    onClick: () -> Unit,
) {
    val scheduled = if (departures) entry.scheduledDeparture else entry.scheduledArrival
    val expected = if (departures) entry.expectedDeparture else entry.expectedArrival
    val delay = entry.delayMinutes ?: 0
    val finished = entry.isFinished(departures, stationZone)
    val containerColor = when {
        entry.isCancelled -> MaterialTheme.colorScheme.errorContainer
        finished -> MaterialTheme.colorScheme.surfaceVariant
        delay >= 10 -> MaterialTheme.colorScheme.errorContainer
        delay > 0 -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = when {
        entry.isCancelled -> MaterialTheme.colorScheme.onErrorContainer
        finished -> MaterialTheme.colorScheme.onSurfaceVariant
        delay >= 10 -> MaterialTheme.colorScheme.onErrorContainer
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
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    stringResource(
                        R.string.service_train_number,
                        entry.serviceName ?: stringResource(R.string.train),
                        entry.trainNumber,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (departures) {
                        entry.destination?.name ?: stringResource(R.string.unknown_destination)
                    } else {
                        entry.origin?.name ?: stringResource(R.string.unknown_origin)
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LifecyclePill(entry.isCancelled, finished)
                    entry.platform?.let {
                        StatusPill(
                            text = stringResource(R.string.platform, it),
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                if (!entry.isCancelled) {
                    when {
                        delay > 0 -> StatusPill(
                            text = stringResource(R.string.minutes_late, delay),
                            containerColor = if (delay >= 10) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.tertiary
                            },
                            contentColor = if (delay >= 10) {
                                MaterialTheme.colorScheme.onError
                            } else {
                                MaterialTheme.colorScheme.onTertiary
                            },
                        )
                        else -> StatusPill(
                            text = stringResource(R.string.on_time),
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LifecyclePill(cancelled: Boolean, finished: Boolean) {
    when {
        cancelled -> StatusPill(
            text = stringResource(R.string.cancelled),
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        )
        finished -> StatusPill(
            text = stringResource(R.string.finished),
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
        )
        else -> StatusPill(
            text = stringResource(R.string.upcoming),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

private fun StationBoardEntry.matches(normalizedQuery: String): Boolean = listOfNotNull(
    trainNumber,
    serviceCode,
    serviceName,
    origin?.code,
    origin?.name,
    destination?.code,
    destination?.name,
    platform,
).joinToString(" ")
    .lowercase()
    .removeAccents()
    .contains(normalizedQuery)

private fun StationBoardEntry.isFinished(
    departures: Boolean,
    zoneId: ZoneId,
    now: ZonedDateTime = ZonedDateTime.now(zoneId),
): Boolean {
    val date = runCatching { LocalDate.parse(serviceDate) }.getOrNull() ?: return false
    if (date.isBefore(now.toLocalDate())) return true
    if (date.isAfter(now.toLocalDate())) return false

    val eventTime = if (departures) {
        expectedDeparture ?: scheduledDeparture ?: expectedArrival ?: scheduledArrival
    } else {
        expectedArrival ?: scheduledArrival ?: expectedDeparture ?: scheduledDeparture
    }
    val time = eventTime?.let(::parseBoardTime) ?: return false
    return !ZonedDateTime.of(date, time, zoneId).isAfter(now)
}

private fun parseBoardTime(value: String): LocalTime? {
    val match = BOARD_TIME_PATTERN.find(value.trim()) ?: return null
    return runCatching {
        LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt())
    }.getOrNull()
}

private val BOARD_TIME_PATTERN = Regex("(?:^|T|\\s)([01]\\d|2[0-3]):([0-5]\\d)")
