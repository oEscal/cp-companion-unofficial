package pt.cpcompanion.sms

import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

enum class SmsJourneyDirection {
    OUTBOUND,
    RETURN,
}

data class ParsedSmsTicketLeg(
    val direction: SmsJourneyDirection,
    val serviceDate: LocalDate,
    val serviceCode: String,
    val trainNumber: String,
    val originName: String,
    val departureTime: LocalTime,
    val destinationName: String,
    val arrivalTime: LocalTime,
    val carriage: String?,
    val seat: String?,
    val ticketReference: String,
)

data class ParsedSmsTicketMessage(
    val ticketReference: String,
    val priceText: String?,
    val legs: List<ParsedSmsTicketLeg>,
)

/**
 * Parses the variants of CP ticket SMS observed in the wild.
 *
 * CP has used more than one formatting convention: ISO and Portuguese dates,
 * `21h33` and `21:33` times, different arrow/dash characters, and both
 * abbreviated and full carriage/seat labels. The parser deliberately does not
 * filter by journey date; old and future tickets are both imported.
 */
class SmsTicketParser {
    fun parse(message: String): ParsedSmsTicketMessage? {
        val normalized = normalize(message)
        if (normalized.isBlank()) return null

        val firstJourneyMarker = listOfNotNull(
            DIRECTION.find(normalized)?.range?.first,
            DATE.find(normalized)?.range?.first,
        ).minOrNull() ?: 0
        val ticketSectionStart = TICKET_SECTION.findAll(normalized)
            .map { it.range.first }
            .firstOrNull { it > firstJourneyMarker }
            ?: normalized.length
        val journeySection = if (ticketSectionStart == 0) normalized else normalized.substring(0, ticketSectionStart)
        val reference = extractReference(normalized)
            ?: syntheticReference(normalized)

        val assignments = parseAssignments(
            if (ticketSectionStart < normalized.length) normalized.substring(ticketSectionStart) else normalized,
        )

        val legs = parseLabelledLegs(journeySection, reference, assignments).ifEmpty {
            parseUnlabelledSingleLeg(journeySection, reference, assignments)
        }

        if (legs.isEmpty()) return null
        return ParsedSmsTicketMessage(
            ticketReference = reference,
            priceText = PRICE.find(normalized)?.groupValues?.getOrNull(1)?.trim(),
            legs = legs,
        )
    }

    private fun parseLabelledLegs(
        journeySection: String,
        reference: String,
        assignments: Map<AssignmentKey, SeatAssignment>,
    ): List<ParsedSmsTicketLeg> {
        val directionMatches = DIRECTION.findAll(journeySection).toList()
        if (directionMatches.isEmpty()) return emptyList()

        return directionMatches.mapIndexedNotNull { index, directionMatch ->
            val start = directionMatch.range.first
            val end = directionMatches.getOrNull(index + 1)?.range?.first ?: journeySection.length
            val segment = journeySection.substring(start, end)
            parseLegSegment(
                segment = segment,
                direction = directionMatch.value.toDirection(),
                reference = reference,
                assignments = assignments,
            )
        }
    }

    private fun parseUnlabelledSingleLeg(
        journeySection: String,
        reference: String,
        assignments: Map<AssignmentKey, SeatAssignment>,
    ): List<ParsedSmsTicketLeg> {
        // Some one-way SMS omit the word "Ida". Only accept this fallback when
        // the message still has the characteristic date/train/two-time shape.
        val leg = parseLegSegment(
            segment = journeySection,
            direction = SmsJourneyDirection.OUTBOUND,
            reference = reference,
            assignments = assignments,
        ) ?: return emptyList()
        return listOf(leg)
    }

    private fun parseLegSegment(
        segment: String,
        direction: SmsJourneyDirection,
        reference: String,
        assignments: Map<AssignmentKey, SeatAssignment>,
    ): ParsedSmsTicketLeg? {
        val dateMatch = DATE.find(segment) ?: return null
        val serviceDate = parseDate(dateMatch.value) ?: return null

        val afterDateOffset = dateMatch.range.last + 1
        val trainMatch = TRAIN.find(segment, afterDateOffset) ?: return null
        val serviceCode = trainMatch.groups[1]?.value?.trim().orEmpty()
        val trainNumber = trainMatch.groups[2]?.value?.trim().orEmpty()
        if (trainNumber.isBlank()) return null

        val timeMatches = TIME.findAll(segment, trainMatch.range.last + 1).take(2).toList()
        if (timeMatches.size < 2) return null

        val originRaw = segment.substring(trainMatch.range.last + 1, timeMatches[0].range.first)
        val destinationRaw = segment.substring(timeMatches[0].range.last + 1, timeMatches[1].range.first)
        val origin = cleanStation(originRaw, isDestination = false)
        val destination = cleanStation(destinationRaw, isDestination = true)
        if (origin.isBlank() || destination.isBlank()) return null

        val assignment = assignments[AssignmentKey(direction, trainNumber)]
            ?: assignments[AssignmentKey(null, trainNumber)]
            ?: assignments.entries.singleOrNull()
                ?.takeIf { it.key.direction == null }
                ?.value

        return ParsedSmsTicketLeg(
            direction = direction,
            serviceDate = serviceDate,
            serviceCode = serviceCode,
            trainNumber = trainNumber,
            originName = origin,
            departureTime = parseTime(timeMatches[0].value) ?: return null,
            destinationName = destination,
            arrivalTime = parseTime(timeMatches[1].value) ?: return null,
            carriage = assignment?.carriage,
            seat = assignment?.seat,
            ticketReference = reference,
        )
    }

    private fun parseAssignments(section: String): Map<AssignmentKey, SeatAssignment> {
        val result = linkedMapOf<AssignmentKey, SeatAssignment>()

        // Parse each Ida/Volta block independently. A single cross-block regex
        // can otherwise attach the return seat to an outbound leg that has no
        // reservation information of its own.
        val directionMatches = DIRECTION.findAll(section).toList()
        directionMatches.forEachIndexed { index, directionMatch ->
            val start = directionMatch.range.first
            val end = directionMatches.getOrNull(index + 1)?.range?.first ?: section.length
            val segment = section.substring(start, end)
            val dateMatch = DATE.find(segment)
            val trainMatch = if (dateMatch != null) {
                TRAIN.find(segment, dateMatch.range.last + 1)
            } else {
                TRAIN.find(segment)
            }
            val trainNumber = trainMatch?.groups?.get(2)?.value.orEmpty()
            val carriage = CARRIAGE.find(segment)?.groups?.get(1)?.value?.ifBlank { null }
            val seat = SEAT.find(segment)?.groups?.get(1)?.value?.ifBlank { null }
            if (trainNumber.isNotBlank() && (carriage != null || seat != null)) {
                result[AssignmentKey(directionMatch.value.toDirection(), trainNumber)] =
                    SeatAssignment(carriage, seat)
            }
        }

        // Fallback for one-way messages that list only a train followed by
        // "Carruagem 4 Lugar 32A" and do not repeat Ida/Volta.
        if (result.isEmpty()) {
            GENERIC_ASSIGNMENT.findAll(section).forEach { match ->
                val trainNumber = match.groups[2]?.value.orEmpty()
                val carriage = match.groups[3]?.value?.ifBlank { null }
                val seat = match.groups[4]?.value?.ifBlank { null }
                if (trainNumber.isNotBlank() && (carriage != null || seat != null)) {
                    result[AssignmentKey(null, trainNumber)] = SeatAssignment(carriage, seat)
                }
            }
        }

        // Last fallback for a message that contains one train and one global
        // carriage/seat assignment.
        if (result.isEmpty()) {
            val carriage = CARRIAGE.find(section)?.groups?.get(1)?.value
            val seat = SEAT.find(section)?.groups?.get(1)?.value
            val trainNumber = TRAIN.find(section)?.groups?.get(2)?.value
            if (trainNumber != null && (carriage != null || seat != null)) {
                result[AssignmentKey(null, trainNumber)] = SeatAssignment(carriage, seat)
            }
        }
        return result
    }

    private fun extractReference(text: String): String? =
        REFERENCE.find(text)?.groups?.get(1)?.value
            ?: GENERIC_REFERENCE.find(text)?.value

    private fun normalize(value: String): String = value
        .replace('\u00A0', ' ')
        .replace('–', '-')
        .replace('—', '-')
        .replace('−', '-')
        .replace('→', '>')
        .replace('»', '>')
        .replace(Regex("[\\r\\n\\t]+"), " ; ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun cleanStation(value: String, isDestination: Boolean): String {
        var cleaned = value
            .replace(Regex("(?i)\\b(?:origem|partida|de)\\s*:?"), " ")
            .replace(Regex("(?i)\\b(?:destino|chegada|para|ate|até)\\s*:?"), " ")
            .replace(Regex("[>]+"), " ")
            .replace(Regex("^[\\s:;/,.-]+|[\\s:;/,.-]+$"), "")
            .replace(Regex("\\s+-\\s*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        // In the common `origin - time >> destination - time` layout the
        // destination substring starts with a separator left after the first time.
        if (isDestination) cleaned = cleaned.trimStart('-', ':', ';', '/', '>', ' ')
        return cleaned.trimEnd('-', ':', ';', '/', ' ')
    }

    private fun parseDate(value: String): LocalDate? {
        val normalized = value.trim().replace('.', '-').replace('/', '-')
        val formatters = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d-M-uuuu"),
        )
        return formatters.firstNotNullOfOrNull { formatter ->
            try {
                LocalDate.parse(normalized, formatter)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    private fun parseTime(value: String): LocalTime? {
        val match = TIME.matchEntire(value.trim()) ?: return null
        return runCatching {
            LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt())
        }.getOrNull()
    }

    private fun String.toDirection(): SmsJourneyDirection =
        if (equals("Volta", ignoreCase = true) || equals("Regresso", ignoreCase = true)) {
            SmsJourneyDirection.RETURN
        } else {
            SmsJourneyDirection.OUTBOUND
        }

    private fun syntheticReference(text: String): String =
        "sms-${UUID.nameUUIDFromBytes(text.toByteArray(StandardCharsets.UTF_8))}"

    private data class AssignmentKey(
        val direction: SmsJourneyDirection?,
        val trainNumber: String,
    )

    private data class SeatAssignment(
        val carriage: String?,
        val seat: String?,
    )

    private companion object {
        val TICKET_SECTION = Regex("(?i)\\b(?:Bilhete|Ticket|Reserva|Ref(?:erencia|erência)?\\.?)\\b")
        val DIRECTION = Regex("(?i)\\b(?:Ida|Volta|Regresso)\\b")
        val DATE = Regex("\\b(?:\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}|\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{4})\\b")
        val TIME = Regex("(?i)\\b(\\d{1,2})\\s*(?:h|:)\\s*(\\d{2})\\b")

        // Service code is optional, but when present it is a short alphabetic code
        // such as AP, IC, IR, R or U. "Comboio" may precede it.
        val TRAIN = Regex("(?i)(?:\\bComboio\\s*)?(?:([\\p{L}]{1,8})\\s*[- ]*\\s*)?(\\d{2,6})\\b")

        val REFERENCE = Regex(
            "(?i)\\b(?:Bilhete|Ticket|Reserva|Ref(?:erencia|erência)?\\.?)\\s*(?:n[.ºo]*\\s*)?[:#-]?\\s*([A-Z0-9]+(?:[-/][A-Z0-9]+)+)",
        )
        val GENERIC_REFERENCE = Regex("\\b\\d{3,8}[-/]\\d{4,14}\\b")
        val PRICE = Regex("(?i)\\bPre[cç]o\\s*:?\\s*([^;]+?)(?:$|\\s*(?:;|/))")

        val CARRIAGE = Regex("(?i)\\b(?:Car(?:r(?:uagem)?)?\\.?|Carruagem)\\s*:?\\s*([\\p{L}0-9-]+)")
        val SEAT = Regex("(?i)\\b(?:Lug(?:ar)?\\.?|Assento)\\s*:?\\s*([\\p{L}0-9-]+)")
        val GENERIC_ASSIGNMENT = Regex(
            pattern = """(?i)(?:\bComboio\s*)?(?:([\p{L}]{1,8})\s+)?(\d{2,6}).{0,50}?(?:Car(?:r(?:uagem)?)?\.?|Carruagem)\s*:?\s*([\p{L}0-9-]+)?.{0,30}?(?:Lug(?:ar)?\.?|Assento)\s*:?\s*([\p{L}0-9-]+)?""",
        )
    }
}
