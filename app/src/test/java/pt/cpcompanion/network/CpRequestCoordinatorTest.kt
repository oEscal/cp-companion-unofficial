package pt.cpcompanion.network

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CpRequestCoordinatorTest {
    @Test
    fun identicalConcurrentRequestsUseOneUpstreamCall() = runBlocking {
        val coordinator = CpRequestCoordinator()
        val calls = AtomicInteger()
        val first = async {
            coordinator.execute("travel:/trip/1", ttlMillis = 1_000L) {
                calls.incrementAndGet()
                delay(50)
                "response"
            }
        }
        val second = async {
            coordinator.execute("travel:/trip/1", ttlMillis = 1_000L) {
                calls.incrementAndGet()
                "other"
            }
        }

        assertEquals("response", first.await())
        assertEquals("response", second.await())
        assertEquals(1, calls.get())
    }

    @Test
    fun validCachedResponseRemainsAvailableDuringGlobalCooldown() = runBlocking {
        val coordinator = CpRequestCoordinator()
        coordinator.execute("travel:/trip/cached", ttlMillis = 60_000L) { "cached" }
        runCatching {
            coordinator.execute("travel:/trip/rate-limited", ttlMillis = 0L) {
                throw ApiHttpException(
                    statusCode = 429,
                    retryAfterEpochMillis = System.currentTimeMillis() + 60_000L,
                    endpointFamily = "TRAVEL",
                    requestKey = "travel:/trip/rate-limited",
                    message = "rate limited",
                )
            }
        }

        assertEquals(
            "cached",
            coordinator.execute("travel:/trip/cached", ttlMillis = 60_000L) { "unexpected" },
        )
    }
    @Test
    fun expiredCachedResponseIsServedDuringGlobalCooldown() = runBlocking {
        val coordinator = CpRequestCoordinator()
        coordinator.execute("travel:/trip/stale", ttlMillis = 1L) { "last-known" }
        delay(10L)
        runCatching {
            coordinator.execute("travel:/trip/rate-limited", ttlMillis = 0L) {
                throw ApiHttpException(
                    statusCode = 429,
                    retryAfterEpochMillis = System.currentTimeMillis() + 60_000L,
                    endpointFamily = "TRAVEL",
                    requestKey = "travel:/trip/rate-limited",
                    message = "rate limited",
                )
            }
        }

        val response = coordinator.executeWithMetadata(
            "travel:/trip/stale",
            ttlMillis = 1L,
            forceRefresh = true,
        ) { "unexpected" }
        assertEquals("last-known", response.value)
        assertEquals(true, response.stale)
    }

    @Test
    fun first429UsesExpiredCachedResponseImmediately() = runBlocking {
        val coordinator = CpRequestCoordinator()
        val original = coordinator.executeWithMetadata("travel:/trip/first-429", ttlMillis = 1L) { "last-known" }
        delay(10L)

        val response = coordinator.executeWithMetadata<String>(
            "travel:/trip/first-429",
            ttlMillis = 1L,
            forceRefresh = true,
        ) {
            throw ApiHttpException(
                statusCode = 429,
                retryAfterEpochMillis = System.currentTimeMillis() + 60_000L,
                endpointFamily = "TRAVEL",
                requestKey = "travel:/trip/first-429",
                message = "rate limited",
            )
        }

        assertEquals("last-known", response.value)
        assertEquals(true, response.stale)
        assertEquals(original.fetchedAtEpochMillis, response.fetchedAtEpochMillis)
    }


    @Test
    fun concurrent429ResponsesCannotShortenTheGlobalCooldown() = runBlocking {
        val coordinator = CpRequestCoordinator(rateLimitEnabled = false)
        val started = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val longerRetry = System.currentTimeMillis() + 120_000L

        fun request(key: String, retryAt: Long) = async {
            runCatching {
                coordinator.execute<String>(key, ttlMillis = 0L) {
                    started.incrementAndGet()
                    release.await()
                    throw ApiHttpException(
                        statusCode = 429,
                        retryAfterEpochMillis = retryAt,
                        endpointFamily = "TRAVEL",
                        requestKey = key,
                        message = "rate limited",
                    )
                }
            }
        }

        val longer = request("travel:/trip/longer", longerRetry)
        val shorter = request("travel:/trip/shorter", System.currentTimeMillis() + 10_000L)
        while (started.get() < 2) delay(1L)
        release.complete(Unit)
        longer.await()
        shorter.await()

        assertTrue(checkNotNull(coordinator.cooldownUntil()) >= longerRetry)
    }

    @Test
    fun tripCacheIsBounded() = runBlocking {
        val coordinator = CpRequestCoordinator(rateLimitEnabled = false)
        repeat(140) { index ->
            coordinator.execute<String>("travel:/trains/$index/timetable/2026-07-11", ttlMillis = 60_000L) { "trip-$index" }
        }

        assertEquals(100, coordinator.cacheEntryCount())
    }

    @Test
    fun countersAreAggregatedByEndpointFamily() = runBlocking {
        val coordinator = CpRequestCoordinator()
        coordinator.execute("travel:/trains/1/timetable/2026-07-11", ttlMillis = 0L) { "one" }
        coordinator.execute("travel:/trains/2/timetable/2026-07-11", ttlMillis = 0L) { "two" }

        assertEquals(2L, coordinator.requestCounters()["travel:trip"])
    }

}
