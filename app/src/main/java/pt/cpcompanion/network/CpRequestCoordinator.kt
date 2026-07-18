package pt.cpcompanion.network

import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CoordinatedResponse<T : Any>(
    val value: T,
    val fetchedAtEpochMillis: Long,
    val stale: Boolean,
    val cooldownUntilEpochMillis: Long? = null,
)

/**
 * Process-wide CP request budget, single-flight deduplication, short-lived response cache,
 * and shared HTTP-429 cooldown.
 */
class CpRequestCoordinator internal constructor(
    private val rateLimitEnabled: Boolean = true,
) {
    private data class CacheEntry(
        val value: Any,
        val fetchedAtEpochMillis: Long,
        val expiresAtEpochMillis: Long,
    )

    private data class SharedResult(
        val value: Any,
        val fetchedAtEpochMillis: Long,
        val stale: Boolean,
        val cooldownUntilEpochMillis: Long? = null,
    )

    private data class Lookup(
        val fresh: CacheEntry?,
        val stale: CacheEntry?,
        val deferred: CompletableDeferred<SharedResult>?,
        val owner: Boolean,
    )

    private val stateMutex = Mutex()
    private val rateMutex = Mutex()
    private val cache = LinkedHashMap<String, CacheEntry>(32, 0.75f, true)
    private val inFlight = mutableMapOf<String, CompletableDeferred<SharedResult>>()
    private val counters = ConcurrentHashMap<String, Long>()

    private val cooldownUntilEpochMillis = AtomicLong(0L)

    private var tokens = BUCKET_CAPACITY.toDouble()
    private var lastRefillNanos = System.nanoTime()

    suspend fun <T : Any> execute(
        key: String,
        ttlMillis: Long,
        forceRefresh: Boolean = false,
        request: suspend () -> T,
    ): T = executeWithMetadata(key, ttlMillis, forceRefresh, request).value

    suspend fun <T : Any> executeWithMetadata(
        key: String,
        ttlMillis: Long,
        forceRefresh: Boolean = false,
        request: suspend () -> T,
    ): CoordinatedResponse<T> {
        val now = System.currentTimeMillis()
        val lookup = stateMutex.withLock {
            pruneCacheLocked(now)
            val cached = cache[key]
            if (!forceRefresh && cached != null && cached.expiresAtEpochMillis > now) {
                Lookup(fresh = cached, stale = cached, deferred = null, owner = false)
            } else {
                val existing = inFlight[key]
                if (existing != null) {
                    Lookup(fresh = null, stale = cached, deferred = existing, owner = false)
                } else {
                    val created = CompletableDeferred<SharedResult>()
                    inFlight[key] = created
                    Lookup(fresh = null, stale = cached, deferred = created, owner = true)
                }
            }
        }

        lookup.fresh?.let { cached ->
            @Suppress("UNCHECKED_CAST")
            return CoordinatedResponse(
                value = cached.value as T,
                fetchedAtEpochMillis = cached.fetchedAtEpochMillis,
                stale = false,
            )
        }

        val staleCandidate = lookup.stale
        val shared = checkNotNull(lookup.deferred)
        if (!lookup.owner) return shared.await().toResponse()

        try {
            val cooldown = cooldownUntilEpochMillis.get()
            if (cooldown > System.currentTimeMillis()) {
                staleCandidate?.let { cached ->
                    val result = SharedResult(
                        value = cached.value,
                        fetchedAtEpochMillis = cached.fetchedAtEpochMillis,
                        stale = true,
                        cooldownUntilEpochMillis = cooldown,
                    )
                    shared.complete(result)
                    return result.toResponse()
                }
                throw ApiCooldownException(cooldown, key)
            }

            acquirePermit()
            counters.compute(counterKey(key)) { _, count -> (count ?: 0L) + 1L }
            val value = request()
            val fetchedAt = System.currentTimeMillis()
            stateMutex.withLock {
                if (ttlMillis > 0L) {
                    cache[key] = CacheEntry(
                        value = value,
                        fetchedAtEpochMillis = fetchedAt,
                        expiresAtEpochMillis = fetchedAt + ttlMillis,
                    )
                    trimCacheLocked()
                }
            }
            val result = SharedResult(value, fetchedAt, stale = false)
            shared.complete(result)
            return result.toResponse()
        } catch (error: Throwable) {
            if (error is ApiHttpException && error.statusCode == 429) {
                val directed = error.retryAfterEpochMillis
                val fallback = System.currentTimeMillis() + DEFAULT_429_COOLDOWN_MS
                val jitteredRetry = (directed ?: fallback) + Random.nextLong(1_000L, 5_001L)
                val effectiveCooldown = cooldownUntilEpochMillis.updateAndGet { current ->
                    maxOf(current, jitteredRetry)
                }
                staleCandidate?.let { cached ->
                    val result = SharedResult(
                        value = cached.value,
                        fetchedAtEpochMillis = cached.fetchedAtEpochMillis,
                        stale = true,
                        cooldownUntilEpochMillis = effectiveCooldown,
                    )
                    shared.complete(result)
                    return result.toResponse()
                }
            }
            shared.completeExceptionally(error)
            throw error
        } finally {
            stateMutex.withLock { inFlight.remove(key, shared) }
        }
    }

    fun cooldownUntil(): Long? = cooldownUntilEpochMillis.get().takeIf { it > System.currentTimeMillis() }

    fun requestCounters(): Map<String, Long> = counters.toSortedMap()

    suspend fun clear(prefix: String? = null) {
        stateMutex.withLock {
            if (prefix == null) cache.clear() else cache.keys.removeAll { it.startsWith(prefix) }
        }
    }


    internal suspend fun cacheEntryCount(): Int = stateMutex.withLock { cache.size }

    private fun pruneCacheLocked(now: Long) {
        cache.entries.removeAll { (_, entry) -> now - entry.fetchedAtEpochMillis > MAX_STALE_CACHE_AGE_MS }
    }

    private fun trimCacheLocked() {
        trimPrefixLocked("travel:/trains/", MAX_TRIP_CACHE_ENTRIES)
        trimPrefixLocked("travel:/stations/", MAX_BOARD_CACHE_ENTRIES)
        while (cache.size > MAX_TOTAL_CACHE_ENTRIES) {
            val eldest = cache.entries.iterator()
            if (!eldest.hasNext()) break
            eldest.next()
            eldest.remove()
        }
    }

    private fun trimPrefixLocked(prefix: String, maximum: Int) {
        var count = cache.keys.count { it.startsWith(prefix) }
        if (count <= maximum) return
        val iterator = cache.entries.iterator()
        while (iterator.hasNext() && count > maximum) {
            val entry = iterator.next()
            if (entry.key.startsWith(prefix)) {
                iterator.remove()
                count -= 1
            }
        }
    }

    private fun counterKey(key: String): String = when {
        key.startsWith("travel:/trains/") && key.contains("/timetable/") -> "travel:trip"
        key.startsWith("travel:/stations/") && key.contains("/timetable/") -> "travel:station-board"
        key == "travel:/stations" -> "travel:stations-catalog"
        key == "travel:/trains" -> "travel:trains-catalog"
        key.startsWith("stations:/stations/infos/") -> "stations:details"
        else -> key.substringBefore('?')
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> SharedResult.toResponse(): CoordinatedResponse<T> = CoordinatedResponse(
        value = value as T,
        fetchedAtEpochMillis = fetchedAtEpochMillis,
        stale = stale,
        cooldownUntilEpochMillis = cooldownUntilEpochMillis,
    )

    private suspend fun acquirePermit() {
        if (!rateLimitEnabled) return
        while (true) {
            val waitMs = rateMutex.withLock {
                val now = System.nanoTime()
                val elapsedSeconds = (now - lastRefillNanos).coerceAtLeast(0L) / 1_000_000_000.0
                tokens = min(BUCKET_CAPACITY.toDouble(), tokens + elapsedSeconds * TOKENS_PER_SECOND)
                lastRefillNanos = now
                if (tokens >= 1.0) {
                    tokens -= 1.0
                    0L
                } else {
                    (((1.0 - tokens) / TOKENS_PER_SECOND) * 1000.0).toLong().coerceAtLeast(50L)
                }
            }
            if (waitMs == 0L) return
            delay(waitMs + Random.nextLong(25L, 151L))
        }
    }

    private companion object {
        const val BUCKET_CAPACITY = 8
        const val TOKENS_PER_SECOND = 0.5 // 30 requests/minute after the initial burst.
        const val DEFAULT_429_COOLDOWN_MS = 2L * 60L * 1000L
        const val MAX_TRIP_CACHE_ENTRIES = 100
        const val MAX_BOARD_CACHE_ENTRIES = 50
        const val MAX_TOTAL_CACHE_ENTRIES = 180
        const val MAX_STALE_CACHE_AGE_MS = 24L * 60L * 60L * 1000L
    }
}
