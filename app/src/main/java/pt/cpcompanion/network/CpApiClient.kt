package pt.cpcompanion.network

import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import pt.cpcompanion.data.AppStores
import pt.cpcompanion.model.CachedCpFrontendConfig
import pt.cpcompanion.model.CpFrontendConfig
import pt.cpcompanion.model.StationBoardResponseDto
import pt.cpcompanion.model.StationDetailsDto
import pt.cpcompanion.model.StationDto
import pt.cpcompanion.model.TrainCatalogDto
import pt.cpcompanion.model.TrainTripDto

class ApiConfigurationException(message: String) : IllegalStateException(message)
class ApiHttpException(val statusCode: Int, message: String) : IOException(message)

class CpApiClient(private val stores: AppStores) {
    private enum class ApiFamily { TRAVEL, STATIONS }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val configurationMutex = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .writeTimeout(7, TimeUnit.SECONDS)
        .callTimeout(9, TimeUnit.SECONDS)
        .build()

    suspend fun warmUpConfiguration(forceRefresh: Boolean = false): CpFrontendConfig =
        withContext(Dispatchers.IO) { configuration(forceRefresh) }

    suspend fun stations(): List<StationDto> = get(
        family = ApiFamily.TRAVEL,
        path = "/stations",
        decoder = { json.decodeFromString(ListSerializer(StationDto.serializer()), it) },
    )

    suspend fun trains(): List<TrainCatalogDto> = get(
        family = ApiFamily.TRAVEL,
        path = "/trains",
        decoder = { json.decodeFromString(ListSerializer(TrainCatalogDto.serializer()), it) },
    )

    suspend fun trip(trainNumber: String, date: LocalDate): TrainTripDto = get(
        family = ApiFamily.TRAVEL,
        path = "/trains/${safeSegment(trainNumber)}/timetable/$date",
        decoder = { json.decodeFromString(TrainTripDto.serializer(), it) },
    )

    suspend fun stationBoard(
        stationCode: String,
        date: LocalDate,
        view: String,
        start: LocalTime,
    ): StationBoardResponseDto = get(
        family = ApiFamily.TRAVEL,
        path = "/stations/${safeSegment(stationCode)}/timetable/$date",
        query = mapOf(
            "view" to view,
            "start" to "%02d:%02d".format(start.hour, start.minute),
        ),
        decoder = { json.decodeFromString(StationBoardResponseDto.serializer(), it) },
    )

    suspend fun stationDetails(stationCode: String): StationDetailsDto = get(
        family = ApiFamily.STATIONS,
        path = "/stations/infos/${safeSegment(stationCode)}",
        decoder = { json.decodeFromString(StationDetailsDto.serializer(), it) },
    )

    private suspend fun <T> get(
        family: ApiFamily,
        path: String,
        query: Map<String, String> = emptyMap(),
        decoder: (String) -> T,
    ): T = withContext(Dispatchers.IO) {
        executeWithConfigurationRetry(family, path, query, decoder)
    }

    private suspend fun <T> executeWithConfigurationRetry(
        family: ApiFamily,
        path: String,
        query: Map<String, String>,
        decoder: (String) -> T,
    ): T {
        val initial = configuration(forceRefresh = false)
        return try {
            execute(initial, family, path, query, decoder)
        } catch (error: ApiHttpException) {
            if (error.statusCode != 401 && error.statusCode != 403) throw error
            val refreshed = configuration(forceRefresh = true)
            execute(refreshed, family, path, query, decoder)
        }
    }

    private fun <T> execute(
        config: CpFrontendConfig,
        family: ApiFamily,
        path: String,
        query: Map<String, String>,
        decoder: (String) -> T,
    ): T {
        val baseUrl = when (family) {
            ApiFamily.TRAVEL -> config.travelApiUrl
            ApiFamily.STATIONS -> config.stationsApiUrl
        }
        val apiKey = when (family) {
            ApiFamily.TRAVEL -> config.travelApiKey
            ApiFamily.STATIONS -> config.stationsApiKey
        }

        val url = buildUrl(baseUrl, path, query)
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("x-api-key", apiKey)
            .header("x-cp-connect-id", config.xcck)
            .header("x-cp-connect-secret", config.xccs)
            .header("Origin", CP_ORIGIN)
            .header("Referer", "$CP_ORIGIN/")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiHttpException(response.code, "CP returned HTTP ${response.code}")
            }
            if (body.isBlank()) throw IOException("CP returned an empty response")
            return runCatching { decoder(body) }
                .getOrElse { throw IOException("The CP response could not be parsed", it) }
        }
    }

    private suspend fun configuration(forceRefresh: Boolean): CpFrontendConfig =
        configurationMutex.withLock {
            val cached = stores.cpFrontendConfig.value
            if (!forceRefresh && cached != null && !cached.isExpired()) return@withLock cached.config

            try {
                val fresh = fetchFrontendConfiguration()
                stores.saveCpFrontendConfig(
                    CachedCpFrontendConfig(
                        config = fresh,
                        fetchedAtEpochMillis = System.currentTimeMillis(),
                    ),
                )
                fresh
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                cached?.config ?: throw ApiConfigurationException(
                    "Could not load CP service configuration. Check the network connection and try again. " +
                        (error.message ?: ""),
                )
            }
        }

    private fun fetchFrontendConfiguration(): CpFrontendConfig {
        val request = Request.Builder()
            .url(CONFIG_URL)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiHttpException(response.code, "CP configuration returned HTTP ${response.code}")
            }
            if (body.isBlank()) throw IOException("CP configuration was empty")
            val config = runCatching { json.decodeFromString(CpFrontendConfig.serializer(), body) }
                .getOrElse { throw IOException("CP configuration could not be parsed", it) }
            validate(config)
            return config
        }
    }

    private fun buildUrl(baseUrl: String, path: String, query: Map<String, String>): HttpUrl {
        val builder = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
        path.trim('/').split('/').filter(String::isNotBlank).forEach(builder::addPathSegment)
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    private fun validate(config: CpFrontendConfig) {
        listOf(config.travelApiUrl, config.stationsApiUrl).forEach { value ->
            val url = runCatching { value.toHttpUrl() }
                .getOrElse { throw ApiConfigurationException("CP supplied an invalid service URL") }
            if (!url.isHttps) throw ApiConfigurationException("CP supplied a non-HTTPS service URL")
        }
        if (listOf(
                config.xcck,
                config.xccs,
                config.travelApiKey,
                config.stationsApiKey,
            ).any(String::isBlank)
        ) {
            throw ApiConfigurationException("CP supplied incomplete service configuration")
        }
    }

    private fun CachedCpFrontendConfig.isExpired(): Boolean =
        System.currentTimeMillis() - fetchedAtEpochMillis >= CONFIG_MAX_AGE_MILLIS

    private fun safeSegment(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9-]{1,32}"))) { "Invalid path parameter" }
        return value
    }

    private companion object {
        const val CP_ORIGIN = "https://www.cp.pt"
        const val CONFIG_URL = "$CP_ORIGIN/fe-config.json"
        const val USER_AGENT = "CP-Companion-Android/0.3"
        const val CONFIG_MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L
    }
}
