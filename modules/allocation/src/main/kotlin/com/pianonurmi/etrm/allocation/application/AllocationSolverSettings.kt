package com.pianonurmi.etrm.allocation.application

import com.pianonurmi.etrm.allocation.domain.ExactSolverSettings
import com.pianonurmi.etrm.allocation.domain.ValuePolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * ```yaml
 * etrm.allocation.exact:
 *   time-limit: 10s     # all phases of one exact solve together
 *   max-pairs: 200000   # (class, obligation) pairs above which exact hands the problem to greedy
 *   workers: 4          # CP-SAT search threads
 * ```
 */
@ConfigurationProperties(prefix = "etrm.allocation.exact")
data class AllocationSolverSettings(
    val timeLimit: Duration = Duration.ofSeconds(10),
    val maxPairs: Int = 200_000,
    val workers: Int = 4,
) {
    fun toDomain() = ExactSolverSettings(timeLimit, maxPairs, workers)
}

/**
 * ```yaml
 * etrm.allocation.value:
 *   liquidity-horizon-days: 60   # lots expiring within this many days of delivery are worth proportionally less
 * ```
 */
@ConfigurationProperties(prefix = "etrm.allocation.value")
data class AllocationValueSettings(val liquidityHorizonDays: Long = 60) {
    fun toDomain() = ValuePolicy(liquidityHorizonDays)
}
