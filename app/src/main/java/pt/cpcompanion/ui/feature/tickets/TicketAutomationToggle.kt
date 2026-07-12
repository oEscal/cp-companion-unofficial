package pt.cpcompanion.ui.feature.tickets

import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import pt.cpcompanion.R
import pt.cpcompanion.model.Ticket
import pt.cpcompanion.model.shouldShowAutomationSwitch

/** Future tickets opt in by default; past/departed tickets intentionally expose no switch. */
@Composable
fun TicketAutomationToggle(
    ticket: Ticket,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!ticket.shouldShowAutomationSwitch()) return
    val description = stringResource(R.string.automatic_tracking_switch_description)
    Switch(
        checked = ticket.automaticTrackingEnabled,
        onCheckedChange = onCheckedChange,
        modifier = modifier
            .semantics { contentDescription = description }
            .testTag("ticket-automation-switch-${ticket.id}"),
    )
}
