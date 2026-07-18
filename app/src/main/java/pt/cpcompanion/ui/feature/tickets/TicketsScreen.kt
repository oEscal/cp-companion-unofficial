package pt.cpcompanion.ui.feature.tickets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import pt.cpcompanion.R
import pt.cpcompanion.model.Ticket
import pt.cpcompanion.model.TicketAutomationState
import pt.cpcompanion.model.TicketDirection
import pt.cpcompanion.model.isPastPassengerSegment
import pt.cpcompanion.ui.AppScreen
import pt.cpcompanion.ui.ExpressiveInfoCard
import pt.cpcompanion.ui.MainUiState
import pt.cpcompanion.ui.MainViewModel
import pt.cpcompanion.ui.ManualTicketDialog
import pt.cpcompanion.ui.SectionHeader
import pt.cpcompanion.ui.SmsPasteDialog
import pt.cpcompanion.ui.StatusPill
import pt.cpcompanion.ui.automationStatusLabel
import pt.cpcompanion.ui.departureTimingLabel
import pt.cpcompanion.ui.isUpcoming
import pt.cpcompanion.ui.labelPrefix

private enum class TicketFilter { FUTURE, PAST }

@Composable
fun TicketsScreen(
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
                        Text(stringResource(R.string.import_cp_ticket_sms), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            stringResource(R.string.import_cp_ticket_sms_body),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { showInboxDisclosure = true }, enabled = !state.smsImporting) {
                                Text(if (state.smsImporting) stringResource(R.string.checking) else stringResource(R.string.check_cp_sms))
                            }
                            OutlinedButton(
                                onClick = { pasteSms = true },
                                enabled = !state.smsImporting,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f)),
                            ) { Text(stringResource(R.string.paste_sms)) }
                        }
                    }
                }
            }
            val today = LocalDate.now()
            val filteredTickets = state.tickets.filter {
                it.isUpcoming(today) == (ticketFilter == TicketFilter.FUTURE)
            }
            val visibleTickets = filteredTickets.take(ticketLimit)
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = ticketFilter == TicketFilter.FUTURE,
                        onClick = { ticketFilter = TicketFilter.FUTURE; ticketLimit = 20 },
                        label = { Text(stringResource(R.string.future)) },
                    )
                    FilterChip(
                        selected = ticketFilter == TicketFilter.PAST,
                        onClick = { ticketFilter = TicketFilter.PAST; ticketLimit = 20 },
                        label = { Text(stringResource(R.string.past)) },
                    )
                }
            }
            if (state.tickets.isEmpty()) {
                item {
                    ExpressiveInfoCard(
                        stringResource(R.string.no_tickets),
                        stringResource(R.string.no_tickets_body),
                    )
                }
            }
            if (visibleTickets.isNotEmpty()) {
                item {
                    SectionHeader(
                        if (ticketFilter == TicketFilter.FUTURE) {
                            stringResource(R.string.future_tickets)
                        } else {
                            stringResource(R.string.past_tickets)
                        },
                        visibleTickets.size,
                    )
                }
            }
            items(visibleTickets, key = { it.id }) { ticket ->
                TicketCard(
                    ticket = ticket,
                    onOpen = { viewModel.navigate(AppScreen.TripScreen(ticket.trainNumber, ticket.serviceDate, ticket.id)) },
                    onTrack = { startTracking(ticket) },
                    onAutomaticTrackingChanged = { enabled ->
                        viewModel.setTicketAutomaticTracking(ticket.id, enabled)
                    },
                    onDelete = { viewModel.deleteTicket(ticket.id) },
                )
            }
            if (visibleTickets.size < filteredTickets.size) {
                item {
                    OutlinedButton(
                        onClick = { ticketLimit += 20 },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.load_more_tickets)) }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { add = true },
            icon = { Icon(painterResource(R.drawable.ic_ticket), contentDescription = null) },
            text = { Text(stringResource(R.string.add_ticket)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
    }
    if (add) {
        ManualTicketDialog(
            trains = state.trains,
            viewModel = viewModel,
            onDismiss = { add = false },
            onSave = { ticket -> viewModel.saveTicket(ticket); add = false },
        )
    }
    if (pasteSms) {
        SmsPasteDialog(
            onDismiss = { pasteSms = false },
            onImport = { text -> importSmsText(text); pasteSms = false },
        )
    }
    if (showInboxDisclosure) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showInboxDisclosure = false },
            title = { Text(stringResource(R.string.read_cp_ticket_sms)) },
            text = {
                Text(stringResource(R.string.inbox_disclosure_detailed))
            },
            confirmButton = {
                Button(onClick = { showInboxDisclosure = false; importFromInbox() }) { Text(stringResource(R.string.continue_action)) }
            },
            dismissButton = { TextButton(onClick = { showInboxDisclosure = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun TicketCard(
    ticket: Ticket,
    onOpen: () -> Unit,
    onTrack: () -> Unit,
    onAutomaticTrackingChanged: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val outbound = ticket.direction != TicketDirection.RETURN
    val containerColor = if (outbound) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val contentColor = if (outbound) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    val warningState = ticket.automationState in setOf(
        TicketAutomationState.BLOCKED_NOTIFICATION_PERMISSION,
        TicketAutomationState.BLOCKED_EXACT_ALARM_PERMISSION,
        TicketAutomationState.CONFLICT_WITH_OTHER_TRIP,
        TicketAutomationState.FAILED,
        TicketAutomationState.NEEDS_VALIDATION,
    )
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painterResource(R.drawable.ic_ticket), contentDescription = stringResource(R.string.tickets), modifier = Modifier.size(32.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        ticket.direction.labelPrefix() + stringResource(
                            R.string.service_train_number,
                            ticket.serviceLabel ?: stringResource(R.string.train),
                            ticket.trainNumber,
                        ),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        listOfNotNull(
                            runCatching {
                                LocalDate.parse(ticket.serviceDate).format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))
                            }.getOrDefault(ticket.serviceDate),
                            ticket.departureTimingLabel(),
                        ).joinToString(" · "),
                        color = contentColor.copy(alpha = .75f),
                    )
                }
                TicketAutomationToggle(ticket, onAutomaticTrackingChanged)
            }
            Text(
                stringResource(
                    R.string.route_between,
                    ticket.originName ?: ticket.originStationCode,
                    ticket.destinationName ?: ticket.destinationStationCode,
                ),
            )
            StatusPill(
                ticket.automationStatusLabel(),
                if (warningState) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                if (warningState) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ticket.automationMessage?.takeIf(String::isNotBlank)?.let {
                Text(it, color = contentColor.copy(alpha = .78f), style = MaterialTheme.typography.bodyMedium)
            }
            val seatText = listOfNotNull(
                ticket.carriage?.let { stringResource(R.string.carriage_value, it) },
                ticket.seat?.let { stringResource(R.string.seat_value, it) },
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
                if (!ticket.isPastPassengerSegment()) {
                    OutlinedButton(
                        onClick = onTrack,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
                        border = BorderStroke(1.dp, contentColor.copy(alpha = .7f)),
                    ) { Text(stringResource(R.string.track_now)) }
                }
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
                ) { Text(stringResource(R.string.delete)) }
            }
        }
    }
}
