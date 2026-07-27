package pt.cpcompanion.domain

import pt.cpcompanion.model.PassengerPhase
import pt.cpcompanion.model.TrackingSnapshot

/** Builds the compact value used by an Android 16 Live Update status chip. */
object StatusChipFormatter {
    private const val MAX_CRITICAL_TEXT = 6

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
                val effectiveDeparture = snapshot.expectedOriginEpochMillis

                /*
                * Keep carriage and seat visible from three minutes before the effective
                * departure until three minutes after it.
                *
                * expectedOriginEpochMillis represents ETD, or a calculated departure
                * based on stop/train delay when ETD is unavailable.
                */
                val keepSeatVisible = effectiveDeparture != null &&
                    nowEpochMillis in
                        (effectiveDeparture - 3 * 60_000L)..(
                            effectiveDeparture + 3 * 60_000L
                        )

                if (keepSeatVisible) {
                    compact(
                        snapshot.carriage
                            ?.takeIf(String::isNotBlank)
                            ?.let { "C$it" },
                        snapshot.seat
                            ?.takeIf(String::isNotBlank),
                    ) ?: minutes
                        ?.let { "${it}m" }
                        ?.takeIf { it.length <= MAX_CRITICAL_TEXT }
                } else {
                    minutes
                        ?.let { "${it}m" }
                        ?.takeIf { it.length <= MAX_CRITICAL_TEXT }
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
            first != null && second != null -> {
                /*
                 * Do not fall back to carriage only when the normal token is one
                 * character too long. Dropping the seat makes the chip ambiguous.
                 * Removing the display-only carriage prefix keeps the usual two-
                 * digit carriage plus three-character seat within the compact budget.
                 * If even that is too long, retain both values and let System UI
                 * apply its own chip-width handling rather than losing the seat.
                 */
                val compactCombined = "${first.removePrefix("C")}·$second"
                compactCombined.takeIf { it.length <= MAX_CRITICAL_TEXT } ?: combined
            }
            first != null && first.length <= MAX_CRITICAL_TEXT -> first
            second != null && second.length <= MAX_CRITICAL_TEXT -> second
            else -> null
        }
    }
}
