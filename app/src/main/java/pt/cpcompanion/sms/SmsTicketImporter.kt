package pt.cpcompanion.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.BaseColumns
import android.provider.Telephony
import androidx.core.content.ContextCompat
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import pt.cpcompanion.model.Station
import pt.cpcompanion.model.Ticket
import pt.cpcompanion.model.TicketDirection
import pt.cpcompanion.model.TicketSource

class SmsTicketImporter(
    context: Context,
    private val parser: SmsTicketParser = SmsTicketParser(),
) {
    private val appContext = context.applicationContext

    @Synchronized
    fun importInbox(
        stations: List<Station>,
        stopAtMessageId: String? = null,
        stopAtReceivedAtEpochMillis: Long? = null,
        maxMessages: Int = 2_000,
    ): SmsImportResult {
        check(
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED,
        ) { "SMS permission has not been granted" }

        val messages = mutableListOf<SmsSourceMessage>()
        val projection = arrayOf(
            BaseColumns._ID,
            Telephony.TextBasedSmsColumns.ADDRESS,
            Telephony.TextBasedSmsColumns.BODY,
            Telephony.TextBasedSmsColumns.DATE,
        )
        // Sender IDs are carrier-dependent and can be rewritten to an
        // alphanumeric alias or short code. Read a bounded recent window, then
        // immediately validate sender/body and parse locally. Nothing leaves
        // the device.
        val sort = "${Telephony.TextBasedSmsColumns.DATE} DESC"

        val numericCheckpoint = stopAtMessageId?.toLongOrNull()
        val selection = when {
            stopAtReceivedAtEpochMillis != null && numericCheckpoint != null ->
                "(${Telephony.TextBasedSmsColumns.DATE} > ?) OR " +
                    "(${Telephony.TextBasedSmsColumns.DATE} = ? AND ${BaseColumns._ID} > ?)"
            stopAtReceivedAtEpochMillis != null -> "${Telephony.TextBasedSmsColumns.DATE} > ?"
            numericCheckpoint != null -> "${BaseColumns._ID} > ?"
            else -> null
        }
        val selectionArgs = when {
            stopAtReceivedAtEpochMillis != null && numericCheckpoint != null -> arrayOf(
                stopAtReceivedAtEpochMillis.toString(),
                stopAtReceivedAtEpochMillis.toString(),
                numericCheckpoint.toString(),
            )
            stopAtReceivedAtEpochMillis != null -> arrayOf(stopAtReceivedAtEpochMillis.toString())
            numericCheckpoint != null -> arrayOf(numericCheckpoint.toString())
            else -> null
        }
        appContext.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sort,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(BaseColumns._ID)
            val senderColumn = cursor.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.ADDRESS)
            val bodyColumn = cursor.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.BODY)
            val dateColumn = cursor.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.DATE)
            while (cursor.moveToNext() && messages.size < maxMessages) {
                val messageId = cursor.getLong(idColumn).toString()
                // The provider query normally excludes the checkpoint. Keep
                // this fallback for devices that ignore the selection.
                if (messageId == stopAtMessageId) break
                messages += SmsSourceMessage(
                    id = messageId,
                    sender = cursor.getString(senderColumn).orEmpty(),
                    body = cursor.getString(bodyColumn).orEmpty(),
                    receivedAtEpochMillis = cursor.getLong(dateColumn),
                    source = TicketSource.SMS_INBOX,
                )
            }
        }
        return importMessages(messages, stations).copy(
            newestScannedInboxMessageId = messages.firstOrNull()?.id,
            newestScannedInboxReceivedAtEpochMillis = messages.firstOrNull()?.receivedAtEpochMillis,
        )
    }

    fun importSharedText(text: String, stations: List<Station>): SmsImportResult =
        importText(text, stations, TicketSource.SHARED_SMS, stableId(text))

    fun importNotificationText(
        text: String,
        stations: List<Station>,
        notificationKey: String,
        receivedAtEpochMillis: Long = System.currentTimeMillis(),
    ): SmsImportResult = importText(
        text = text,
        stations = stations,
        source = TicketSource.SMS_NOTIFICATION,
        sourceId = notificationKey,
        receivedAtEpochMillis = receivedAtEpochMillis,
    )

    private fun importText(
        text: String,
        stations: List<Station>,
        source: TicketSource,
        sourceId: String,
        receivedAtEpochMillis: Long = System.currentTimeMillis(),
    ): SmsImportResult = importMessages(
        messages = listOf(
            SmsSourceMessage(
                id = sourceId,
                sender = CP_SENDER,
                body = text,
                receivedAtEpochMillis = receivedAtEpochMillis,
                source = source,
            ),
        ),
        stations = stations,
    )

    fun resolveTicketStations(ticket: Ticket, stations: List<Station>): Ticket {
        if (ticket.originStationCode.isNotBlank() && ticket.destinationStationCode.isNotBlank()) return ticket
        val stationIndex = stationIndex(stations)
        val origin = ticket.originName?.let { stationIndex[normalizeStationName(it)]?.distinctBy(Station::code)?.singleOrNull() }
        val destination = ticket.destinationName?.let { stationIndex[normalizeStationName(it)]?.distinctBy(Station::code)?.singleOrNull() }
        return ticket.copy(
            originStationCode = ticket.originStationCode.ifBlank { origin?.code.orEmpty() },
            destinationStationCode = ticket.destinationStationCode.ifBlank { destination?.code.orEmpty() },
            originName = origin?.name ?: ticket.originName,
            destinationName = destination?.name ?: ticket.destinationName,
        )
    }

    private fun importMessages(messages: List<SmsSourceMessage>, stations: List<Station>): SmsImportResult {
        val stationIndex = stationIndex(stations)
        val tickets = mutableListOf<Ticket>()
        val issues = mutableListOf<String>()
        var candidates = 0

        val expandedMessages = expandMultipartCandidates(messages)
        expandedMessages.forEach { source ->
            val parsed = parser.parse(source.body) ?: return@forEach
            if (!isCpSender(source.sender) && !hasCpTicketMarkers(source.body)) return@forEach
            candidates += 1
            parsed.legs.forEach { leg ->
                val origin = stationIndex[normalizeStationName(leg.originName)]?.distinctBy(Station::code)?.singleOrNull()
                val destination = stationIndex[normalizeStationName(leg.destinationName)]?.distinctBy(Station::code)?.singleOrNull()
                if (origin == null || destination == null) {
                    val unresolved = buildList {
                        if (origin == null) add(leg.originName)
                        if (destination == null) add(leg.destinationName)
                    }.joinToString(" and ")
                    issues += "Could not yet match station $unresolved for train ${leg.trainNumber}"
                }

                val departure = LocalDateTime.of(leg.serviceDate, leg.departureTime).atZone(CP_TIME_ZONE)
                var arrival = LocalDateTime.of(leg.serviceDate, leg.arrivalTime).atZone(CP_TIME_ZONE)
                if (arrival.isBefore(departure)) arrival = arrival.plusDays(1)

                tickets += Ticket(
                    id = deterministicTicketId(parsed.ticketReference, leg),
                    trainNumber = leg.trainNumber,
                    serviceLabel = leg.serviceCode.ifBlank { null },
                    serviceDate = leg.serviceDate.toString(),
                    originStationCode = origin?.code.orEmpty(),
                    destinationStationCode = destination?.code.orEmpty(),
                    originName = origin?.name ?: leg.originName,
                    destinationName = destination?.name ?: leg.destinationName,
                    scheduledDepartureEpochMillis = departure.toInstant().toEpochMilli(),
                    scheduledArrivalEpochMillis = arrival.toInstant().toEpochMilli(),
                    carriage = leg.carriage,
                    seat = leg.seat,
                    reference = parsed.ticketReference,
                    source = source.source,
                    direction = when (leg.direction) {
                        SmsJourneyDirection.OUTBOUND -> TicketDirection.OUTBOUND
                        SmsJourneyDirection.RETURN -> TicketDirection.RETURN
                    },
                    sourceMessageId = source.id,
                    createdAtEpochMillis = source.receivedAtEpochMillis,
                )
            }
        }

        return SmsImportResult(
            messagesScanned = messages.size,
            candidateMessages = candidates,
            tickets = tickets.distinctBy(Ticket::id),
            issues = issues.distinct(),
        )
    }


    private fun expandMultipartCandidates(messages: List<SmsSourceMessage>): List<SmsSourceMessage> {
        if (messages.size < 2) return messages
        val ordered = messages.sortedBy { it.receivedAtEpochMillis }
        val expanded = ordered.toMutableList()

        // Android normally reassembles multipart SMS, but some devices/providers
        // expose individual parts. Try adjacent pairs and triples received close
        // together. Ticket IDs are deterministic, so successful duplicates collapse.
        for (index in ordered.indices) {
            for (size in 2..3) {
                val parts = ordered.subList(index, (index + size).coerceAtMost(ordered.size))
                if (parts.size != size) continue
                if (parts.last().receivedAtEpochMillis - parts.first().receivedAtEpochMillis > 120_000) continue
                if (parts.map { normalizeSender(it.sender) }.distinct().size != 1) continue
                expanded += SmsSourceMessage(
                    id = parts.joinToString("+") { it.id },
                    sender = parts.first().sender,
                    body = parts.joinToString(" ") { it.body },
                    receivedAtEpochMillis = parts.last().receivedAtEpochMillis,
                    source = parts.first().source,
                )
            }
        }
        return expanded.distinctBy { it.id to it.body }
    }

    private fun hasCpTicketMarkers(body: String): Boolean {
        val normalized = body.lowercase()
        val ticketWord = normalized.contains("bilhete") || normalized.contains("ticket") || normalized.contains("reserva")
        val journeyWord = normalized.contains("comboio") || normalized.contains("ida") ||
            normalized.contains("volta") || normalized.contains("regresso")
        return ticketWord && journeyWord
    }

    private fun isCpSender(value: String): Boolean {
        val normalized = normalizeSender(value)
        return normalized == "cp" ||
            normalized == "cpinfo" ||
            normalized == "cpcomboios" ||
            normalized == "comboiosdeportugal" ||
            normalized.startsWith("cpcomboios")
    }

    private fun normalizeSender(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "")

    private fun deterministicTicketId(
        reference: String,
        leg: ParsedSmsTicketLeg,
    ): String {
        val raw = listOf(
            "cp-sms",
            reference,
            leg.direction.name,
            leg.serviceDate.toString(),
            leg.trainNumber,
        ).joinToString(":")
        return UUID.nameUUIDFromBytes(raw.toByteArray(StandardCharsets.UTF_8)).toString()
    }

    private fun stableId(text: String): String =
        UUID.nameUUIDFromBytes(text.toByteArray(StandardCharsets.UTF_8)).toString()

    private fun stationIndex(stations: List<Station>): Map<String, List<Station>> = stations
        .flatMap { station -> stationAliases(station.name).map { alias -> alias to station } }
        .groupBy({ it.first }, { it.second })

    private fun stationAliases(name: String): Set<String> {
        val normalized = normalizeStationName(name)
        val withoutSuffix = normalized
            .removeSuffix(" estacao")
            .removeSuffix(" station")
            .removeSuffix(" gare")
            .trim()
        val reverseAliases = CURATED_STATION_ALIASES
            .filterValues { it == normalized }
            .keys
        return (setOf(normalized, withoutSuffix) + reverseAliases).filter(String::isNotBlank).toSet()
    }

    private fun normalizeStationName(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
        return CURATED_STATION_ALIASES[normalized] ?: normalized
    }

    private data class SmsSourceMessage(
        val id: String,
        val sender: String,
        val body: String,
        val receivedAtEpochMillis: Long,
        val source: TicketSource,
    )

    private companion object {
        const val CP_SENDER = "CP"
        val CP_TIME_ZONE: ZoneId = ZoneId.of("Europe/Lisbon")
        val CURATED_STATION_ALIASES = mapOf(
            "oriente" to "lisboa oriente",
            "santa apolonia" to "lisboa santa apolonia",
            "campanha" to "porto campanha",
            "coimbra b" to "coimbra b",
            "coimbra-b" to "coimbra b",
        )
    }
}

data class SmsImportResult(
    val messagesScanned: Int,
    val candidateMessages: Int,
    val tickets: List<Ticket>,
    val issues: List<String>,
    val newestScannedInboxMessageId: String? = null,
    val newestScannedInboxReceivedAtEpochMillis: Long? = null,
) {
    val newestImportedInboxMessageId: String? = tickets
        .asSequence()
        .filter { it.source == TicketSource.SMS_INBOX }
        .maxByOrNull { it.createdAtEpochMillis }
        ?.sourceMessageId
        // Multipart candidates use a synthetic "oldest+…+newest" ID. The
        // newest physical SMS is the cursor checkpoint for the next scan.
        ?.substringAfterLast('+')
}
