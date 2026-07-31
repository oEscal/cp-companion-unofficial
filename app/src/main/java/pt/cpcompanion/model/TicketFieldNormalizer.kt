package pt.cpcompanion.model

/** Removes the ticket labels that CP sometimes includes in carriage and seat values. */
fun normalizeCarriage(value: String?): String? = normalizeLabelledNumber(value, 'C')

fun normalizeSeat(value: String?): String? = normalizeLabelledNumber(value, 'L')

private fun normalizeLabelledNumber(value: String?, label: Char): String? {
    val normalized = value?.trim()?.ifBlank { null } ?: return null
    return if (
        normalized.length > 1 &&
        normalized[0].equals(label, ignoreCase = true) &&
        normalized[1].isDigit()
    ) {
        normalized.drop(1)
    } else {
        normalized
    }
}
