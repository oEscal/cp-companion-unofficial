package pt.cpcompanion.data

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import pt.cpcompanion.network.CpApiClient

class TrainRepository(context: Context, private val api: CpApiClient) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cacheDir = File(context.filesDir, "catalog-cache").apply { mkdirs() }
    private val stationsFile = File(cacheDir, "stations.json")
    private val trainsFile = File(cacheDir, "trains.json")

    suspend fun cachedStations(): List<Station> = readList(stationsFile, Station.serializer())
    suspend fun cachedTrains(): List<TrainServiceEntry> = readList(trainsFile, TrainServiceEntry.serializer())

    suspend fun refreshStations(): List<Station> {
        val result = api.stations().map { dto ->
            Station(
                code = dto.code,
                name = dto.designation,
                latitude = dto.latitude?.toDoubleOrNull(),
                longitude = dto.longitude?.toDoubleOrNull(),
                region = dto.region,
                railwayCodes = dto.railways,
            )
        }.distinctBy { it.code }.sortedBy { it.name.lowercase() }
        writeList(stationsFile, result, Station.serializer())
        return result
    }

    suspend fun refreshTrains(): List<TrainServiceEntry> {
        val result = api.trains().map { dto ->
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

    suspend fun trip(trainNumber: String, date: LocalDate): TrainTrip {
        val dto = api.trip(trainNumber, date)
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
                    isCancelled = stop.supression != null,
                    latitude = stop.latitude?.toDoubleOrNull(),
                    longitude = stop.longitude?.toDoubleOrNull(),
                )
            },
            fetchedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    suspend fun stationBoard(
        stationCode: String,
        date: LocalDate,
        departures: Boolean,
        start: LocalTime,
    ): List<StationBoardEntry> = api.stationBoard(
        stationCode = stationCode,
        date = date,
        view = if (departures) "DEPARTURES" else "ARRIVALS",
        start = start,
    ).stationStops.map { item ->
        StationBoardEntry(
            trainNumber = item.trainNumber.toString(),
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

    suspend fun stationDetails(stationCode: String): StationDetails {
        val dto = api.stationDetails(stationCode)
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

    private suspend fun <T> readList(file: File, serializer: kotlinx.serialization.KSerializer<T>): List<T> =
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext emptyList()
            runCatching {
                json.decodeFromString(ListSerializer(serializer), file.readText())
            }.getOrDefault(emptyList())
        }

    private suspend fun <T> writeList(
        file: File,
        value: List<T>,
        serializer: kotlinx.serialization.KSerializer<T>,
    ) = withContext(Dispatchers.IO) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(json.encodeToString(ListSerializer(serializer), value))
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText())
            temporary.delete()
        }
    }
}
