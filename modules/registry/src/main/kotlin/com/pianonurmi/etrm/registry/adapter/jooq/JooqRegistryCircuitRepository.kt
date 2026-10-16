package com.pianonurmi.etrm.registry.adapter.jooq

import com.pianonurmi.etrm.registry.adapter.jooq.generated.tables.RegistryCircuit.REGISTRY_CIRCUIT
import com.pianonurmi.etrm.registry.adapter.jooq.generated.tables.records.RegistryCircuitRecord
import com.pianonurmi.etrm.registry.api.RegistryCircuitView
import com.pianonurmi.etrm.registry.domain.RegistryCircuitRepository
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class JooqRegistryCircuitRepository(private val dsl: DSLContext) : RegistryCircuitRepository {
    override fun all(): List<RegistryCircuitView> =
        dsl.selectFrom(REGISTRY_CIRCUIT).where(REGISTRY_CIRCUIT.CONSECUTIVE_FAILURES.gt(0).or(REGISTRY_CIRCUIT.OPENED_AT.isNotNull))
            .orderBy(REGISTRY_CIRCUIT.REGISTRY_CODE).fetch(::view)

    override fun find(registryCode: String): RegistryCircuitView? =
        dsl.selectFrom(REGISTRY_CIRCUIT).where(REGISTRY_CIRCUIT.REGISTRY_CODE.eq(registryCode)).fetchOne()?.let(::view)

    override fun failure(registryCode: String, reason: String, at: Instant, threshold: Int, probeAfter: Instant): RegistryCircuitView {
        // The count after this failure decides, in one statement, so two dispatchers cannot both miss the threshold.
        val failures = REGISTRY_CIRCUIT.CONSECUTIVE_FAILURES.add(1)
        val opens = failures.ge(threshold)
        return dsl.insertInto(REGISTRY_CIRCUIT)
            .set(REGISTRY_CIRCUIT.REGISTRY_CODE, registryCode)
            .set(REGISTRY_CIRCUIT.CONSECUTIVE_FAILURES, 1)
            .set(REGISTRY_CIRCUIT.LAST_FAILURE, reason)
            .set(REGISTRY_CIRCUIT.OPENED_AT, if (threshold <= 1) at else null)
            .set(REGISTRY_CIRCUIT.PROBE_AFTER, if (threshold <= 1) probeAfter else null)
            .set(REGISTRY_CIRCUIT.UPDATED_AT, at)
            .onConflict(REGISTRY_CIRCUIT.REGISTRY_CODE).doUpdate()
            .set(REGISTRY_CIRCUIT.CONSECUTIVE_FAILURES, failures)
            .set(REGISTRY_CIRCUIT.LAST_FAILURE, reason)
            .set(REGISTRY_CIRCUIT.OPENED_AT, DSL.`when`(opens, DSL.coalesce(REGISTRY_CIRCUIT.OPENED_AT, DSL.value(at))))
            .set(REGISTRY_CIRCUIT.PROBE_AFTER, DSL.`when`(opens, DSL.value(probeAfter)))
            .set(REGISTRY_CIRCUIT.UPDATED_AT, at)
            .returning()
            .fetchSingle()
            .let(::view)
    }

    override fun answered(registryCode: String, at: Instant): Boolean {
        val wasOpen = dsl.select(REGISTRY_CIRCUIT.OPENED_AT).from(REGISTRY_CIRCUIT)
            .where(REGISTRY_CIRCUIT.REGISTRY_CODE.eq(registryCode)).forUpdate().fetchOne()
            ?: return false
        dsl.update(REGISTRY_CIRCUIT)
            .set(REGISTRY_CIRCUIT.CONSECUTIVE_FAILURES, 0)
            .setNull(REGISTRY_CIRCUIT.OPENED_AT)
            .setNull(REGISTRY_CIRCUIT.PROBE_AFTER)
            .set(REGISTRY_CIRCUIT.UPDATED_AT, at)
            .where(REGISTRY_CIRCUIT.REGISTRY_CODE.eq(registryCode), REGISTRY_CIRCUIT.CONSECUTIVE_FAILURES.gt(0).or(REGISTRY_CIRCUIT.OPENED_AT.isNotNull))
            .execute()
        return wasOpen.value1() != null
    }

    override fun resume(registryCode: String, by: String, at: Instant): RegistryCircuitView? =
        dsl.update(REGISTRY_CIRCUIT)
            .set(REGISTRY_CIRCUIT.CONSECUTIVE_FAILURES, 0)
            .setNull(REGISTRY_CIRCUIT.OPENED_AT)
            .setNull(REGISTRY_CIRCUIT.PROBE_AFTER)
            .set(REGISTRY_CIRCUIT.RESUMED_BY, by)
            .set(REGISTRY_CIRCUIT.RESUMED_AT, at)
            .set(REGISTRY_CIRCUIT.UPDATED_AT, at)
            .where(REGISTRY_CIRCUIT.REGISTRY_CODE.eq(registryCode))
            .returning()
            .fetchOne()
            ?.let(::view)

    private fun view(r: RegistryCircuitRecord) = RegistryCircuitView(
        registryCode = r.registryCode,
        paused = r.openedAt != null,
        consecutiveFailures = r.consecutiveFailures,
        lastFailure = r.lastFailure,
        pausedSince = r.openedAt,
        probeAfter = r.probeAfter,
        resumedBy = r.resumedBy,
        resumedAt = r.resumedAt,
    )
}
