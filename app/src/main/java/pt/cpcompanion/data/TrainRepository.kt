package pt.cpcompanion.data

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.time.Duration
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import pt.cpcompanion.model.Station
import pt.cpcompanion.model.StationBoardEntry
import pt.cpcompanion.model.StationDetails
import pt.cpcompanion.model.StationRef
import pt.cpcompanion.model.TrainServiceEntry
import pt.cpcompanion.model.TrainStop
import pt.cpcompanion.model.TrainTrip
import pt.cpcompanion.domain.StationTimeZoneResolver
import pt.cpcompanion.network.CpApiClient

class TrainRepository(context: Context, private val api: CpApiClient) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cacheDir = File(context.filesDir, "catalog-cache").apply { mkdirs() }
    private val stationsFile = File(cacheDir, "stations-v2.json")
    private val trainsFile = File(cacheDir, "trains-v2.json")

    suspend fun cachedStations(): List<Station> = readList(stationsFile, Station.serializer())
    suspend fun cachedTrains(): List<TrainServiceEntry> = readList(trainsFile, TrainServiceEntry.serializer())

    fun catalogCacheIsStale(): Boolean = listOf(stationsFile, trainsFile).any { file ->
        !file.exists() || System.currentTimeMillis() - file.lastModified() > CATALOG_DISK_MAX_AGE_MS
    }

    suspend fun refreshStations(forceRefresh: Boolean = true): List<Station> {
        val result = api.stations(forceRefresh).map { dto ->
            Station(
                code = dto.code,
                name = dto.designation,
                latitude = dto.latitude?.toDoubleOrNull(),
                longitude = dto.longitude?.toDoubleOrNull(),
                region = dto.region,
                timeZoneId = StationTimeZoneResolver.resolve(dto.code, dto.region).id,
                railwayCodes = dto.railways,
            )
        }.distinctBy { it.code }.sortedBy { it.name.lowercase() }
        writeList(stationsFile, result, Station.serializer())
        return result
    }

    suspend fun refreshTrains(forceRefresh: Boolean = true): List<TrainServiceEntry> {
        val result = api.trains(forceRefresh).map { dto ->
            val number = dto.trainNumber.toString()
            val origin = dto.trainOrigin?.let { ref -> ref.code?.let { StationRef(it, ref.designation.orEmpty()) } }
            val destination = dto.trainDestination?.let { ref -> ref.code?.let { StationRef(it, ref.designation.orEmpty()) } }
            val serviceCode = dto.trainService?.code
            TrainServiceEntry(
                key = listOf(number, serviceCode, origin?.code, destination?.code).joinToString("|"),
                trainNumber = number,
                serviceCode = serviceCode,
                serviceName = dto.trainService?.designation,
                origin = origin,
                destination = destination,
            )
        }.distinctBy { it.key }.sortedWith(compareBy({ it.trainNumber.toLongOrNull() ?: Long.MAX_VALUE }, { it.key }))
        writeList(trainsFile, result, TrainServiceEntry.serializer())
        return result
    }

    suspend fun trip(trainNumber: String, date: LocalDate, forceRefresh: Boolean = false): TrainTrip {
        val payload = api.trip(trainNumber, date, forceRefresh)
        val dto = payload.value
        return TrainTrip(
            trainNumber = dto.trainNumber.toString(),
            serviceDate = date.toString(),
            serviceCode = dto.serviceCode?.code,
            serviceName = dto.serviceCode?.designation,
            status = dto.status,
            overallDelayMinutes = dto.delay,
            lastReportedStationCode = dto.lastStationCode,
            latitude = dto.latitude?.toDoubleOrNull(),
            longitude = dto.longitude?.toDoubleOrNull(),
            duration = dto.duration,
            hasDisruptions = dto.hasDisruptions,
            messages = dto.messages.mapNotNull { it.text ?: it.code },
            stops = dto.trainStops.map { stop ->
                TrainStop(
                    station = StationRef(stop.station.code.orEmpty(), stop.station.designation.orEmpty()),
                    scheduledArrival = stop.arrival,
                    scheduledDeparture = stop.departure,
                    expectedArrival = stop.eta,
                    expectedDeparture = stop.etd,
                    platform = stop.platform,
                    delayMinutes = stop.delay,
                    suppressionCode = stop.supression?.code,
                    suppressionDesignation = stop.supression?.designation,
                    latitude = stop.latitude?.toDoubleOrNull(),
                    longitude = stop.longitude?.toDoubleOrNull(),
                )
            },
            fetchedAtEpochMillis = payload.fetchedAtEpochMillis,
            dataStale = payload.stale,
            cooldownUntilEpochMillis = payload.cooldownUntilEpochMillis,
        )
    }

    suspend fun stationBoard(
        stationCode: String,
        date: LocalDate,
        departures: Boolean,
        start: LocalTime,
        forceRefresh: Boolean = false,
    ): List<StationBoardEntry> = api.stationBoard(
        stationCode = stationCode,
        date = date,
        view = if (departures) "DEPARTURES" else "ARRIVALS",
        start = start,
        forceRefresh = forceRefresh,
    ).stationStops.map { item ->
        StationBoardEntry(
            trainNumber = item.trainNumber.toString(),
            serviceDate = resolveBoardServiceDate(
                requestDate = date,
                requestStart = start,
                eventTime = if (departures) {
                    item.departureTime ?: item.etd ?: item.arrivalTime ?: item.eta
                } else {
                    item.arrivalTime ?: item.eta ?: item.departureTime ?: item.etd
                },
            ).toString(),
            serviceCode = item.trainService?.code,
            serviceName = item.trainService?.designation,
            origin = item.trainOrigin?.let { ref -> ref.code?.let { StationRef(it, ref.designation.orEmpty()) } },
            destination = item.trainDestination?.let { ref -> ref.code?.let { StationRef(it, ref.designation.orEmpty()) } },
            scheduledArrival = item.arrivalTime,
            scheduledDeparture = item.departureTime,
            expectedArrival = item.eta,
            expectedDeparture = item.etd,
            platform = item.platform,
            delayMinutes = item.delay,
            isCancelled = item.supression != null,
        )
    }

    suspend fun stationDetails(stationCode: String, forceRefresh: Boolean = false): StationDetails {
        val dto = api.stationDetails(stationCode, forceRefresh)
        return StationDetails(
            code = dto.code,
            name = dto.designation,
            latitude = dto.latitude,
            longitude = dto.longitude,
            trainLine = dto.trainLine,
            mobilityAccess = dto.mobilityAccess,
            address = dto.address,
            services = dto.services,
        )
    }


    private suspend fun <T> readList(file: File, serializer: KSerializer<T>): List<T> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        runCatching { json.decodeFromString(ListSerializer(serializer), file.readText()) }
            .getOrElse {
                // Preserve one diagnostic copy, then remove the unreadable active cache so each
                // startup does not create another timestamped backup of the same bytes.
                val corrupt = File(file.parentFile, "${file.name}.corrupt-latest")
                runCatching {
                    if (corrupt.exists()) corrupt.delete()
                    if (!file.renameTo(corrupt)) {
                        file.copyTo(corrupt, overwrite = true)
                        file.delete()
                    }
                }
                emptyList()
            }
    }

    private suspend fun <T> writeList(file: File, value: List<T>, serializer: KSerializer<T>) =
        withContext(Dispatchers.IO) {
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(json.encodeToString(ListSerializer(serializer), value))
            if (!temporary.renameTo(file)) {
                file.writeText(temporary.readText())
                temporary.delete()
            }
        }

    private companion object {
        const val CATALOG_DISK_MAX_AGE_MS = 24L * 60L * 60L * 1000L
    }
}


internal fun resolveBoardServiceDate(
    requestDate: LocalDate,
    requestStart: LocalTime,
    eventTime: String?,
): LocalDate {
    val parsed = eventTime?.let(::parseBoardTime) ?: return requestDate
    val backwards = Duration.between(parsed, requestStart)
    return if (parsed.isBefore(requestStart) && backwards.toHours() >= MIDNIGHT_ROLLOVER_HOURS) {
        requestDate.plusDays(1)
    } else {
        requestDate
    }
}

private fun parseBoardTime(value: String): LocalTime? {
    val match = BOARD_TIME_PATTERN.find(value.trim()) ?: return null
    return runCatching { LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt()) }.getOrNull()
}

private const val MIDNIGHT_ROLLOVER_HOURS = 6L
private val BOARD_TIME_PATTERN = Regex("(?:^|T|\\s)([01]\\d|2[0-3]):([0-5]\\d)")
