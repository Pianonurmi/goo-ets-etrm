package com.pianonurmi.etrm.allocation

import com.pianonurmi.etrm.allocation.api.SupplyTier
import com.pianonurmi.etrm.allocation.application.AllocationHeadroomSettings
import com.pianonurmi.etrm.allocation.application.FutureSupplyProvider
import com.pianonurmi.etrm.allocation.application.PortfolioFlowCache
import com.pianonurmi.etrm.allocation.application.PreparedFlow
import com.pianonurmi.etrm.allocation.application.ProblemBuilder
import com.pianonurmi.etrm.allocation.domain.AllocationPlanRepository
import com.pianonurmi.etrm.allocation.domain.ObligationRepository
import com.pianonurmi.etrm.inventory.api.HoldingStamp
import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.inventory.api.LotSnapshot
import com.pianonurmi.etrm.marketdata.api.GooMarks
import com.pianonurmi.etrm.product.api.criteria.Criterion
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionOperations
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Proves the single flight of [PortfolioFlowCache] (`buildOnce`): readers that meet on a cold cache with the same stamp
 * share one build through the in-flight future instead of building one each, and a build that fails never leaves a
 * waiter hanging. Builds are counted at the lot snapshot, which every build reads once. The first build is held inside
 * that read until every other reader is parked in `buildOnce`, so the test proves the waiting rather than hoping for
 * an interleaving.
 */
class PortfolioFlowCacheConcurrencyTest {
    private val pool = Executors.newFixedThreadPool(READERS)
    private val clock = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC)
    private val entity = EngineFixtures.entity

    private val stamps = AtomicInteger()
    private val builds = AtomicInteger()
    private val building = CountDownLatch(1)
    private val release = CountDownLatch(1)

    /** Throws on the first snapshot when set; later ones succeed. */
    @Volatile
    private var failFirstBuild = false

    @AfterEach
    fun stop() {
        release.countDown()
        pool.shutdownNow()
    }

    private fun cache(): PortfolioFlowCache {
        val bucket = EngineFixtures.bucket("HYDRO_ANY", Criterion.energySource("HYDRO"), 1)
        val buckets = FixturePorts.buckets(LocalDate.of(2026, 11, 1)).also { every { it.buckets(any()) } returns listOf(bucket) }
        val lot = EngineFixtures.lot("FI", "HYDRO", 1_000)
        val snapshot = LotSnapshot(entity, 0, listOf(lot))
        val lotQuery = mockk<LotQuery> {
            every { holdingStamp(entity) } answers {
                stamps.incrementAndGet()
                HoldingStamp(1, 1, 1_000)
            }
            // A reader that builds after another has left an index behind patches it; nothing has changed.
            every { liveVersions(entity) } returns mapOf(lot.id to lot.version)
            every { lotsById(any()) } returns emptyList()
            every { snapshot(entity) } answers {
                val nth = builds.incrementAndGet()
                if (nth == 1) {
                    building.countDown()
                    check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "the held build was never released" }
                    if (failFirstBuild) error("the first build fails")
                }
                snapshot
            }
        }
        val obligations = mockk<ObligationRepository> {
            every { bySource(any()) } returns null
            every { open(entity) } returns listOf(EngineFixtures.obligation(Criterion.energySource("HYDRO"), 100))
            every { openStamp(entity) } returns "1"
        }
        val plans = mockk<AllocationPlanRepository> { every { byEntity(entity, any()) } returns emptyList() }
        val builder = ProblemBuilder(FixturePorts.Criteria, buckets, FixturePorts.routes(), GooMarks { _, _, _ -> BigDecimal("1.50") })
        return PortfolioFlowCache(
            obligations,
            plans,
            builder,
            buckets,
            lotQuery,
            clock,
            TransactionOperations.withoutTransaction(),
            AllocationHeadroomSettings(warmOnMiss = false),
            // No purchases or forecasts: the held tier is all there is.
            mockk<FutureSupplyProvider> { every { supply(any(), any()) } returns emptyList() },
        )
    }

    /** Starts [READERS] readers released together by a barrier; each returns its flow or the failure. */
    private fun readers(cache: PortfolioFlowCache, threads: MutableList<Thread>): List<Future<Result<PreparedFlow>>> {
        val barrier = CyclicBarrier(READERS)
        return List(READERS) {
            pool.submit<Result<PreparedFlow>> {
                synchronized(threads) { threads += Thread.currentThread() }
                barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                runCatching { cache.prepare(entity, tier = SupplyTier.HELD) }
            }
        }
    }

    /**
     * Waits until the first build is inside the snapshot read and the other readers are parked in `buildOnce` on its
     * future: each has read the stamp (so it is committed to the same stamp) and is blocked, not building.
     */
    private fun awaitOthersParked(threads: List<Thread>) {
        check(building.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "no build started" }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (parked(threads) < READERS - 1) {
            check(System.nanoTime() < deadline) { "only ${parked(threads)} of ${READERS - 1} readers are waiting on the build" }
            Thread.onSpinWait()
            Thread.yield()
        }
    }

    private fun parked(threads: List<Thread>): Int = synchronized(threads) { threads.toList() }.count { thread ->
        thread.state == Thread.State.WAITING && thread.stackTrace.any { it.methodName == "buildOnce" }
    }

    @Test
    fun `readers that meet on a cold cache with one stamp share a single build`() {
        val cache = cache()
        val threads = mutableListOf<Thread>()
        val results = readers(cache, threads)
        awaitOthersParked(threads)
        builds.get() shouldBe 1

        release.countDown()
        val flows = results.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getOrThrow() }

        flows shouldHaveSize READERS
        flows.forEach { it shouldBeSameInstanceAs flows.first() }
        builds.get() shouldBe 1
        stamps.get() shouldBe READERS
        // The result is cached for the stamp: a later reader builds nothing.
        cache.prepare(entity) shouldBeSameInstanceAs flows.first()
        builds.get() shouldBe 1
        cache.close()
    }

    @Test
    fun `when the build fails the waiting readers do not hang and build for themselves`() {
        failFirstBuild = true
        val cache = cache()
        val threads = mutableListOf<Thread>()
        val results = readers(cache, threads)
        awaitOthersParked(threads)

        release.countDown()
        val outcomes = results.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }

        // One reader ran the failed build; every other one stopped waiting and built its own flow.
        outcomes.count { it.isFailure } shouldBe 1
        outcomes.single { it.isFailure }.exceptionOrNull()!!.message shouldBe "the first build fails"
        val flows = outcomes.mapNotNull { it.getOrNull() }
        flows shouldHaveSize READERS - 1
        builds.get() shouldBeGreaterThanOrEqualTo 2
        // The failed flight left nothing behind: the next reader is served from the cache or builds, and does not wait.
        val later = AtomicReference<PreparedFlow>()
        val next = pool.submit { later.set(cache.prepare(entity)) }
        try {
            next.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw AssertionError("a reader after the failed build also failed", e)
        }
        later.get().stamp shouldBe flows.first().stamp
        cache.close()
    }

    private companion object {
        const val READERS = 8
        const val TIMEOUT_SECONDS = 15L
    }
}
