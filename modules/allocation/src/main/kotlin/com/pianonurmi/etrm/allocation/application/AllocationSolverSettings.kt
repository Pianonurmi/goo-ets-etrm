package com.pianonurmi.etrm.allocation.application

import com.pianonurmi.etrm.allocation.domain.ExactSolverSettings
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
