package pt.cpcompanion.model

import java.time.LocalDate
import java.time.ZoneId

private val DEFAULT_SERVICE_ZONE: ZoneId = ZoneId.of("Europe/Lisbon")
private const val HISTORICAL_AUTOMATION_GRACE_MS = 6L * 60L * 60L * 1000L

/** UI/history classification based on the passenger segment's scheduled end. */
fun Ticket.isPastPassengerSegment(
    nowEpochMillis: Long = System.currentTimeMillis(),
    today: LocalDate = LocalDate.now(DEFAULT_SERVICE_ZONE),
): Boolean {
    completedAtEpochMillis?.let { return true }
    if (automationState == TicketAutomationState.COMPLETED) return true
    scheduledArrivalEpochMillis?.let { return it <= nowEpochMillis }
    val date = runCatching { LocalDate.parse(serviceDate) }.getOrNull() ?: return false
    return date.isBefore(today)
}

/**
 * Conservative terminal classification used by schedulers and migrations. A scheduled arrival is
 * not proof that a delayed train has arrived, so recent tickets remain eligible for one live check.
 */

internal fun Ticket.isClearlyPastByTimeOnly(
    nowEpochMillis: Long = System.currentTimeMillis(),
    today: LocalDate = LocalDate.now(DEFAULT_SERVICE_ZONE),
): Boolean {
    scheduledArrivalEpochMillis?.let {
        return it <= nowEpochMillis - HISTORICAL_AUTOMATION_GRACE_MS
    }
    val date = runCatching { LocalDate.parse(serviceDate) }.getOrNull() ?: return false
    return date.isBefore(today.minusDays(1))
}

fun Ticket.isClearlyPastForAutomation(
    nowEpochMillis: Long = System.currentTimeMillis(),
    today: LocalDate = LocalDate.now(DEFAULT_SERVICE_ZONE),
): Boolean {
    completedAtEpochMillis?.let { return true }
    if (automationState == TicketAutomationState.COMPLETED) return true
    scheduledArrivalEpochMillis?.let {
        return it <= nowEpochMillis - HISTORICAL_AUTOMATION_GRACE_MS
    }
    val date = runCatching { LocalDate.parse(serviceDate) }.getOrNull() ?: return false
    return date.isBefore(today.minusDays(1))
}


/** Whether a non-running passenger segment is already beyond its scheduled destination time. */
fun Ticket.hasScheduledPassengerSegmentEnded(
    nowEpochMillis: Long = System.currentTimeMillis(),
): Boolean = scheduledArrivalEpochMillis?.let { it <= nowEpochMillis } == true

/**
 * Whether the per-ticket automation switch is useful. It is intentionally absent once departure
 * has passed, including while an automatically started trip is still being tracked.
 */
fun Ticket.shouldShowAutomationSwitch(
    nowEpochMillis: Long = System.currentTimeMillis(),
    today: LocalDate = LocalDate.now(DEFAULT_SERVICE_ZONE),
): Boolean {
    completedAtEpochMillis?.let { return false }
    if (automationState == TicketAutomationState.COMPLETED) return false
    scheduledDepartureEpochMillis?.let { return it > nowEpochMillis }
    val date = runCatching { LocalDate.parse(serviceDate) }.getOrNull() ?: return false
    return !date.isBefore(today)
}
