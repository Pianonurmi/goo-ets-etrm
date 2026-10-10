package com.pianonurmi.etrm.registry.application

import com.pianonurmi.etrm.registry.adapter.http.RegistryAdapterProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.util.UUID

/** Dispatch and confirmation behaviour of registry instructions (`etrm.registry.dispatch.*`). */
@ConfigurationProperties(prefix = "etrm.registry.dispatch")
data class RegistryDispatchSettings(
    /** Instructions that do not come from an approved allocation plan (and manual retries) need a second person. */
    val unplannedNeedsApproval: Boolean = true,
    val lease: Duration = Duration.ofMinutes(5),
    val retryBackoff: Duration = Duration.ofMinutes(1),
    val maxAttempts: Int = 5,
    val batchSize: Int = 20,
    /** Treat certificates leaving the source account in a holdings statement as confirmation of the instruction. */
    val confirmFromStatements: Boolean = true,
    /** How long an instruction may stay with the registry before it is reported overdue, per channel. */
    val confirmationSla: Map<String, Duration> = mapOf(
        "MANUAL" to Duration.ofHours(48),
        "FILE" to Duration.ofHours(72),
        "API" to Duration.ofHours(4),
        "SIMULATOR" to Duration.ofHours(1),
    ),
) {
    fun sla(channel: String): Duration = confirmationSla[channel] ?: Duration.ofHours(48)

    fun backoff(attempts: Int): Duration = retryBackoff.multipliedBy(attempts.coerceIn(1, 60).toLong())
}

/** Starts dispatching an outbox instruction once the creating transaction has committed. */
fun interface DispatchNudge {
    fun afterCommit(instructionId: UUID)

    companion object {
        val NONE = DispatchNudge { }
    }
}

@Configuration
@EnableConfigurationProperties(RegistryDispatchSettings::class, RegistryAdapterProperties::class)
class RegistryConfiguration {
    @Bean
    @Qualifier("registryDispatchExecutor")
    fun registryDispatchExecutor(): TaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 2
        maxPoolSize = 4
        queueCapacity = 1_000
        setThreadNamePrefix("registry-dispatch-")
        initialize()
    }
}

@Component
class AfterCommitDispatchNudge(
    @Lazy private val dispatcher: RegistryDispatcher,
    @Qualifier("registryDispatchExecutor") private val executor: TaskExecutor,
) : DispatchNudge {
    override fun afterCommit(instructionId: UUID) {
        val run = Runnable { dispatcher.dispatchDue() }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = executor.execute(run)
                },
            )
        } else {
            executor.execute(run)
        }
    }
}
