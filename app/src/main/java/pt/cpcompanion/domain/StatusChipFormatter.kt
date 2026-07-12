package pt.cpcompanion.domain

import pt.cpcompanion.model.PassengerPhase
import pt.cpcompanion.model.TrackingSnapshot

/** Builds the compact value used by an Android 16 Live Update status chip. */
object StatusChipFormatter {
    private const val MAX_CRITICAL_TEXT = 7

    fun format(snapshot: TrackingSnapshot, nowEpochMillis: Long = System.currentTimeMillis()): String? {
        val minutes = snapshot.expectedEventEpochMillis?.let { expected ->
            val remaining = (expected - nowEpochMillis).coerceAtLeast(0L)
            (remaining + 59_999L) / 60_000L
        }
        return when (snapshot.phase) {
            PassengerPhase.PRE_TRIP,
            PassengerPhase.APPROACHING_ORIGIN -> compact(
                snapshot.platform?.takeIf(String::isNotBlank)?.let { "P$it" },
                minutes?.let { "${it}m" },
            )
            PassengerPhase.BOARDING_SOON -> compact(
                snapshot.carriage?.takeIf(String::isNotBlank)?.let { "C$it" },
                snapshot.seat?.takeIf(String::isNotBlank),
            ) ?: minutes?.let { "${it}m" }?.takeIf { it.length <= MAX_CRITICAL_TEXT }
            PassengerPhase.ON_BOARD -> {
                val boardingStartedAt = snapshot.expectedOriginArrivalEpochMillis
                val keepSeatVisible = boardingStartedAt != null &&
                    nowEpochMillis in boardingStartedAt..(boardingStartedAt + 5 * 60_000L)
                if (keepSeatVisible) {
                    compact(
                        snapshot.carriage?.takeIf(String::isNotBlank)?.let { "C$it" },
                        snapshot.seat?.takeIf(String::isNotBlank),
                    )
                } else {
                    minutes?.let { "${it}m" }?.takeIf { it.length <= MAX_CRITICAL_TEXT }
                }
            }
            PassengerPhase.APPROACHING_DESTINATION -> minutes?.let { "${it}m" }
                ?.takeIf { it.length <= MAX_CRITICAL_TEXT }
            PassengerPhase.CANCELLED -> "CANCEL"
            else -> null
        }
    }

    private fun compact(first: String?, second: String?): String? {
        val combined = listOfNotNull(first, second).joinToString("·")
        return when {
            combined.isNotBlank() && combined.length <= MAX_CRITICAL_TEXT -> combined
            first != null && first.length <= MAX_CRITICAL_TEXT -> first
            second != null && second.length <= MAX_CRITICAL_TEXT -> second
            else -> null
        }
    }
}
