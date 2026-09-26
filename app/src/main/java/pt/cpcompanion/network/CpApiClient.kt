package pt.cpcompanion.network

import java.io.IOException
import java.net.SocketTimeoutException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
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
import okhttp3.Response
import pt.cpcompanion.data.AppStores
import pt.cpcompanion.model.CachedCpFrontendConfig
import pt.cpcompanion.model.CpFrontendConfig
import pt.cpcompanion.model.StationBoardResponseDto
import pt.cpcompanion.model.StationDetailsDto
import pt.cpcompanion.model.StationDto
import pt.cpcompanion.model.TrainCatalogDto
import pt.cpcompanion.model.TrainTripDto

class ApiConfigurationException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

open class ApiHttpException(
    val statusCode: Int,
    val retryAfterEpochMillis: Long? = null,
    val endpointFamily: String? = null,
    val requestKey: String? = null,
    message: String,
) : IOException(message)

class ApiCooldownException(
    val retryAtEpochMillis: Long,
    val cooldownRequestKey: String,
) : IOException("CP rate limit is active until ${formatRetryTime(retryAtEpochMillis)}")

data class ApiPayload<T : Any>(
    val value: T,
    val fetchedAtEpochMillis: Long,
    val stale: Boolean,
    val cooldownUntilEpochMillis: Long? = null,
)

class CpApiClient(
    private val stores: AppStores,
    private val coordinator: CpRequestCoordinator,
) {
    private enum class ApiFamily { TRAVEL, STATIONS }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val configurationMutex = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .writeTimeout(7, TimeUnit.SECONDS)
        .callTimeout(9, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun warmUpConfiguration(forceRefresh: Boolean = false): CpFrontendConfig =
        withContext(Dispatchers.IO) { configuration(forceRefresh) }

    suspend fun stations(forceRefresh: Boolean = false): List<StationDto> = get(
        family = ApiFamily.TRAVEL,
        path = "/stations",
        ttlMillis = CATALOG_TTL_MS,
        forceRefresh = forceRefresh,
        decoder = { json.decodeFromString(ListSerializer(StationDto.serializer()), it) },
    )

    suspend fun trains(forceRefresh: Boolean = false): List<TrainCatalogDto> = get(
        family = ApiFamily.TRAVEL,
        path = "/trains",
        ttlMillis = CATALOG_TTL_MS,
        forceRefresh = forceRefresh,
        decoder = { json.decodeFromString(ListSerializer(TrainCatalogDto.serializer()), it) },
    )

    suspend fun trip(trainNumber: String, date: LocalDate, forceRefresh: Boolean = false): ApiPayload<TrainTripDto> = getPayload(
        family = ApiFamily.TRAVEL,
        path = "/trains/${safeSegment(trainNumber)}/timetable/$date",
        ttlMillis = TRIP_TTL_MS,
        forceRefresh = forceRefresh,
        decoder = { json.decodeFromString(TrainTripDto.serializer(), it) },
    )

    suspend fun stationBoard(
        stationCode: String,
        date: LocalDate,
        view: String,
        start: LocalTime,
        forceRefresh: Boolean = false,
    ): StationBoardResponseDto = get(
        family = ApiFamily.TRAVEL,
        path = "/stations/${safeSegment(stationCode)}/timetable/$date",
        query = mapOf(
            "view" to view,
            "start" to "%02d:%02d".format(Locale.ROOT, start.hour, start.minute),
        ),
        ttlMillis = BOARD_TTL_MS,
        forceRefresh = forceRefresh,
        decoder = { json.decodeFromString(StationBoardResponseDto.serializer(), it) },
    )

    suspend fun stationDetails(stationCode: String, forceRefresh: Boolean = false): StationDetailsDto = get(
        family = ApiFamily.STATIONS,
        path = "/stations/infos/${safeSegment(stationCode)}",
        ttlMillis = DETAILS_TTL_MS,
        forceRefresh = forceRefresh,
        decoder = { json.decodeFromString(StationDetailsDto.serializer(), it) },
    )

    fun cooldownUntilEpochMillis(): Long? = coordinator.cooldownUntil()
    fun debugRequestCounters(): Map<String, Long> = coordinator.requestCounters()

    private suspend fun <T : Any> get(
        family: ApiFamily,
        path: String,
        query: Map<String, String> = emptyMap(),
        ttlMillis: Long,
        forceRefresh: Boolean,
        decoder: (String) -> T,
    ): T {
        val payload = getPayload(family, path, query, ttlMillis, forceRefresh, decoder)
        if (payload.stale) {
            throw ApiCooldownException(
                retryAtEpochMillis = payload.cooldownUntilEpochMillis ?: System.currentTimeMillis() + 5_000L,
                cooldownRequestKey = buildRequestKey(family, path, query),
            )
        }
        return payload.value
    }

    private suspend fun <T : Any> getPayload(
        family: ApiFamily,
        path: String,
        query: Map<String, String> = emptyMap(),
        ttlMillis: Long,
        forceRefresh: Boolean,
        decoder: (String) -> T,
    ): ApiPayload<T> = withContext(Dispatchers.IO) {
        val requestKey = buildRequestKey(family, path, query)
        val response = coordinator.executeWithMetadata(requestKey, ttlMillis, forceRefresh) {
            executeWithConfigurationRetry(family, path, query, requestKey, decoder)
        }
        ApiPayload(
            value = response.value,
            fetchedAtEpochMillis = response.fetchedAtEpochMillis,
            stale = response.stale,
            cooldownUntilEpochMillis = response.cooldownUntilEpochMillis,
        )
    }

    private suspend fun <T> executeWithConfigurationRetry(
        family: ApiFamily,
        path: String,
        query: Map<String, String>,
        requestKey: String,
        decoder: (String) -> T,
    ): T {
        val initial = configuration(forceRefresh = false)
        return try {
            execute(initial, family, path, query, requestKey, decoder)
        } catch (error: ApiHttpException) {
            if (error.statusCode != 401 && error.statusCode != 403) throw error
            val refreshed = configuration(forceRefresh = true)
            execute(refreshed, family, path, query, requestKey, decoder)
        }
    }

    private fun <T> execute(
        config: CpFrontendConfig,
        family: ApiFamily,
        path: String,
        query: Map<String, String>,
        requestKey: String,
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

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw responseException(response, family, requestKey)
                if (body.isBlank()) throw IOException("CP returned an empty response")
                return runCatching { decoder(body) }
                    .getOrElse { throw IOException("The CP response could not be parsed", it) }
            }
        } catch (error: SocketTimeoutException) {
            throw SocketTimeoutException("CP request timed out for $requestKey").also { it.initCause(error) }
        }
    }

    private suspend fun configuration(forceRefresh: Boolean): CpFrontendConfig = configurationMutex.withLock {
        val cached = stores.cpFrontendConfig.value
        if (!forceRefresh && cached != null && !cached.isExpired()) return@withLock cached.config
        try {
            val fresh = fetchFrontendConfiguration()
            stores.saveCpFrontendConfig(CachedCpFrontendConfig(fresh, System.currentTimeMillis()))
            fresh
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            cached?.config ?: throw ApiConfigurationException(
                "Could not load CP service configuration. Check the network connection and try again.",
                error,
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
            if (!response.isSuccessful) throw responseException(response, ApiFamily.TRAVEL, "frontend-config")
            if (body.isBlank()) throw IOException("CP configuration was empty")
            val config = runCatching { json.decodeFromString(CpFrontendConfig.serializer(), body) }
                .getOrElse { throw IOException("CP configuration could not be parsed", it) }
            validate(config)
            return config
        }
    }

    private fun responseException(response: Response, family: ApiFamily, requestKey: String): ApiHttpException {
        val retryAt = if (response.code == 429) parseRetryAfter(response.header("Retry-After")) else null
        val message = if (response.code == 429) {
            "CP rate limited requests${retryAt?.let { "; retrying after ${formatRetryTime(it)}" }.orEmpty()}"
        } else {
            "CP returned HTTP ${response.code}"
        }
        return ApiHttpException(
            statusCode = response.code,
            retryAfterEpochMillis = retryAt,
            endpointFamily = family.name,
            requestKey = requestKey,
            message = message,
        )
    }

    private fun parseRetryAfter(value: String?): Long? {
        val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        text.toLongOrNull()?.let { seconds ->
            return System.currentTimeMillis() +
                seconds.coerceIn(1L, MAX_RETRY_AFTER_SECONDS) * 1000L
        }
        return runCatching {
            ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull()?.coerceIn(
            System.currentTimeMillis() + 1_000L,
            System.currentTimeMillis() + MAX_RETRY_AFTER_SECONDS * 1000L,
        )
    }

    private fun buildUrl(baseUrl: String, path: String, query: Map<String, String>): HttpUrl {
        val base = baseUrl.trimEnd('/').toHttpUrl()
        require(base.isHttps) { "Unexpected non-HTTPS CP API URL" }
        require(isAllowedHost(base.host) && base.port == 443) { "Unexpected CP API host or port" }
        require(base.username.isEmpty() && base.password.isEmpty()) { "Unexpected credentials in CP API URL" }
        require(base.query == null && base.fragment == null) { "Unexpected query or fragment in CP API URL" }
        val builder = base.newBuilder()
        path.trim('/').split('/').filter(String::isNotBlank).forEach(builder::addPathSegment)
        query.toSortedMap().forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    private fun validate(config: CpFrontendConfig) {
        validateServiceUrl(config.travelApiUrl, "/cp/services/travel-api")
        validateServiceUrl(config.stationsApiUrl, "/cp/services/stations-api")
        if (listOf(config.xcck, config.xccs, config.travelApiKey, config.stationsApiKey).any(String::isBlank)) {
            throw ApiConfigurationException("CP supplied incomplete service configuration")
        }
    }

    private fun validateServiceUrl(value: String, expectedPathPrefix: String) {
        val url = runCatching { value.toHttpUrl() }
            .getOrElse { throw ApiConfigurationException("CP supplied an invalid service URL") }
        if (!url.isHttps) throw ApiConfigurationException("CP supplied a non-HTTPS service URL")
        if (!isAllowedHost(url.host) || url.port != 443) {
            throw ApiConfigurationException("CP supplied an unexpected service host or port")
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
            throw ApiConfigurationException("CP supplied an unsafe service URL")
        }
        val actualPath = url.encodedPath.trimEnd('/')
        val expectedPath = expectedPathPrefix.trimEnd('/')
        if (actualPath != expectedPath && !actualPath.startsWith("$expectedPath/")) {
            throw ApiConfigurationException("CP supplied an unexpected service path")
        }
    }

    private fun isAllowedHost(host: String): Boolean = host == "api-gateway.cp.pt"

    private fun buildRequestKey(family: ApiFamily, path: String, query: Map<String, String>): String =
        "${family.name.lowercase()}:$path" + query.toSortedMap().entries.joinToString(
            prefix = if (query.isEmpty()) "" else "?",
            separator = "&",
        ) { "${it.key}=${it.value}" }

    private fun CachedCpFrontendConfig.isExpired(): Boolean =
        System.currentTimeMillis() - fetchedAtEpochMillis >= CONFIG_MAX_AGE_MILLIS

    private fun safeSegment(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9-]{1,32}"))) { "Invalid path parameter" }
        return value
    }

    private companion object {
        const val CP_ORIGIN = "https://www.cp.pt"
        const val CONFIG_URL = "$CP_ORIGIN/fe-config.json"
        const val USER_AGENT = "CP-Companion-Android/1.0.0"
        const val MAX_RETRY_AFTER_SECONDS = 24L * 60L * 60L
        const val CONFIG_MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L
        const val CATALOG_TTL_MS = 6L * 60L * 60L * 1000L
        const val TRIP_TTL_MS = 8_000L
        const val BOARD_TTL_MS = 15_000L
        const val DETAILS_TTL_MS = 24L * 60L * 60L * 1000L
    }
}

private fun formatRetryTime(epochMillis: Long): String = DateTimeFormatter.ofPattern("HH:mm")
    .format(java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault()))
