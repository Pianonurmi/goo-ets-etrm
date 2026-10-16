package com.pianonurmi.etrm.registry.application

import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.registry.api.RegistryCircuitCommand
import com.pianonurmi.etrm.registry.api.RegistryCircuitQuery
import com.pianonurmi.etrm.registry.api.RegistryCircuitView
import com.pianonurmi.etrm.registry.domain.GatewayResult
import com.pianonurmi.etrm.registry.domain.RegistryCircuitRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * The dispatch circuit per registry (increment 16 M3). A transport failure (the registry did not answer, or answered
 * that it did not take the request: [GatewayResult.Retry], [GatewayResult.Unknown]) counts against the registry;
 * any other answer resets the count. Paused, the registry's instructions wait, except one probe after the
 * cool-down. Unknown outcomes are never resent (ADR 0012): the circuit only decides whether pending instructions go.
 */
@Service
class RegistryCircuits(
    private val repository: RegistryCircuitRepository,
    private val settings: RegistryCircuitSettings,
    private val actorProvider: ActorProvider,
    private val clock: Clock,
) : RegistryCircuitQuery, RegistryCircuitCommand {
    private val log = LoggerFactory.getLogger(RegistryCircuits::class.java)

    /** What dispatch may send now: [paused] registries send nothing, [probing] ones (past their cool-down) one instruction. */
    data class Gate(val paused: Set<String>, val probing: Set<String>)

    @Transactional(readOnly = true)
    fun gate(now: Instant): Gate {
        val open = repository.all().filter { it.paused }
        return Gate(
            paused = open.map { it.registryCode }.toSet(),
            probing = open.filter { it.probeAfter?.isAfter(now) == false }.map { it.registryCode }.toSet(),
        )
    }

    /** Records a dispatch outcome against the instruction's registry, in the caller's transaction. */
    fun record(registryCode: String, result: GatewayResult) {
        val now = clock.instant()
        val failure = when (result) {
            is GatewayResult.Retry -> result.reason
            is GatewayResult.Unknown -> result.reason
            else -> null
        }
        if (failure == null) {
            if (repository.answered(registryCode, now)) log.info("Registry {} answered again; its dispatch resumes", registryCode)
            return
        }
        val circuit = repository.failure(registryCode, failure, now, settings.failures, now.plus(settings.coolDown))
        if (circuit.paused) {
            log.warn(
                "Registry {} paused after {} transport failures in a row ({}); next probe at {}",
                registryCode,
                circuit.consecutiveFailures,
                failure,
                circuit.probeAfter,
            )
        }
    }

    @Transactional(readOnly = true)
    override fun circuits(): List<RegistryCircuitView> = repository.all()

    @Transactional
    override fun resume(registryCode: String): RegistryCircuitView {
        val actor = actorProvider.current()
        val circuit = repository.resume(registryCode, actor.userId, clock.instant()) ?: throw NotFoundException("RegistryCircuit", registryCode)
        log.info("Registry {} resumed by {}", registryCode, actor.userId)
        return circuit
    }

    fun pausedCount(): Int = repository.all().count { it.paused }
}
