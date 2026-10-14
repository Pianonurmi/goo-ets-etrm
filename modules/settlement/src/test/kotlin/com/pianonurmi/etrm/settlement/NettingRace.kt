package com.pianonurmi.etrm.settlement

import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.settlement.api.NettingSettled
import com.pianonurmi.etrm.settlement.application.NettingService
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two threads settle the same agreed netting statement, each in a transaction of its own (the services in the settlement
 * tests carry no transaction proxy, so [TransactionTemplate] stands in for it and `TestDatabase.dsl` joins it).
 * Proves the statement's row lock (`NettingRepository.find(id, forUpdate = true)`, taken by `NettingService.settle`):
 * [held] keeps the first settlement inside its transaction, after all its work and before commit, until the second one
 * is seen blocked on the statement row in `pg_stat_activity`, so it shows blocking and not luck; [together] releases
 * both at once through a barrier. [settled] counts the `NettingSettled` events, [refusals] the failures.
 */
internal class NettingRace(private val service: (ApplicationEventPublisher) -> NettingService) {
    class Result(val settled: Int, val refusals: List<Throwable>)

    private val transactions = TransactionTemplate(DataSourceTransactionManager(TestDatabase.dataSource))

    fun held(statementId: UUID, paymentId: UUID): Result {
        val published = AtomicInteger()
        val reached = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val first = service(
            ApplicationEventPublisher {
                if (it is NettingSettled && published.incrementAndGet() == 1) {
                    reached.countDown()
                    check(proceed.await(SECONDS, TimeUnit.SECONDS)) { "the held settlement was never released" }
                }
            },
        )
        val second = service(ApplicationEventPublisher { if (it is NettingSettled) published.incrementAndGet() })
        val pool = Executors.newFixedThreadPool(2)
        try {
            val holding = pool.submit<kotlin.Result<Any?>> { runCatching { transactions.execute { first.settle(statementId, paymentId) } } }
            check(reached.await(SECONDS, TimeUnit.SECONDS)) { "the first settlement never reached its commit" }
            val late = pool.submit<kotlin.Result<Any?>> { runCatching { transactions.execute { second.settle(statementId, paymentId) } } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SECONDS)
            while (backendsWaitingOnStatements() < 1) {
                check(System.nanoTime() < deadline) { "the second settlement is not waiting for the statement row" }
                Thread.onSpinWait()
                Thread.yield()
            }
            check(!late.isDone) { "the second settlement finished while the first still held the statement" }
            proceed.countDown()
            val outcomes = listOf(holding, late).map { it.get(SECONDS, TimeUnit.SECONDS) }
            return Result(published.get(), outcomes.mapNotNull { it.exceptionOrNull() }).also {
                check(outcomes[0].isSuccess) { "the first settlement failed: ${outcomes[0].exceptionOrNull()}" }
            }
        } finally {
            proceed.countDown()
            pool.shutdownNow()
        }
    }

    fun together(statementId: UUID, paymentId: UUID): Result {
        val published = AtomicInteger()
        val settling = service(ApplicationEventPublisher { if (it is NettingSettled) published.incrementAndGet() })
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val outcomes = List(2) {
                pool.submit<kotlin.Result<Any?>> {
                    barrier.await(SECONDS, TimeUnit.SECONDS)
                    runCatching { transactions.execute { settling.settle(statementId, paymentId) } }
                }
            }.map { it.get(SECONDS, TimeUnit.SECONDS) }
            return Result(published.get(), outcomes.mapNotNull { it.exceptionOrNull() })
        } finally {
            pool.shutdownNow()
        }
    }

    private fun backendsWaitingOnStatements(): Int = TestDatabase.dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock' AND query ILIKE '%netting_statement%'",
            ).use {
                it.next()
                it.getInt(1)
            }
        }
    }

    private companion object {
        const val SECONDS = 20L
    }
}
