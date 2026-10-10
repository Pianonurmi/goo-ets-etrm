package com.pianonurmi.etrm.registry.application

import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.registry.api.TransferRoute
import com.pianonurmi.etrm.registry.api.TransferRouteCommand
import com.pianonurmi.etrm.registry.api.TransferRouteQuery
import com.pianonurmi.etrm.registry.api.UpsertTransferRoute
import com.pianonurmi.etrm.registry.domain.TransferRouteRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * Default route rules: within one registry transfers are free and next-day; between two AIB hub members the
 * transfer goes through the hub at the configured fee; anything else is infeasible. Explicit overrides win.
 */
@Service
@Transactional(readOnly = true)
class TransferRouteService(
    private val repository: TransferRouteRepository,
    private val referenceData: ReferenceDataQuery,
    @Value("\${etrm.registry.hub-fee-per-mwh:0.05}") private val hubFeePerMwh: BigDecimal,
    @Value("\${etrm.registry.hub-expected-days:3}") private val hubExpectedDays: Int,
) : TransferRouteQuery, TransferRouteCommand {
    override fun route(fromRegistryCode: String, toRegistryCode: String): TransferRoute {
        val from = fromRegistryCode.uppercase()
        val to = toRegistryCode.uppercase()
        repository.overrides().firstOrNull { it.fromRegistryCode == from && it.toRegistryCode == to }?.let { return it }
        return defaultRoute(from, to)
    }

    override fun routesFrom(fromRegistryCode: String): Map<String, TransferRoute> {
        val from = fromRegistryCode.uppercase()
        val overrides = repository.overrides().filter { it.fromRegistryCode == from }.associateBy { it.toRegistryCode }
        return referenceData.registries().associate { registry -> registry.code to (overrides[registry.code] ?: defaultRoute(from, registry.code)) }
    }

    @Transactional
    override fun upsert(command: UpsertTransferRoute): TransferRoute {
        val route =
            TransferRoute(command.fromRegistryCode.uppercase(), command.toRegistryCode.uppercase(), command.feasible, command.feePerMwh, command.expectedDays)
        repository.upsert(route)
        return route
    }

    private fun defaultRoute(from: String, to: String): TransferRoute {
        if (from == to) return TransferRoute(from, to, feasible = true, feePerMwh = BigDecimal.ZERO, expectedDays = 1)
        val source = referenceData.registry(from)
        val target = referenceData.registry(to)
        val viaHub = source != null && target != null && source.hubMember && target.hubMember && source.registryType == target.registryType
        return if (viaHub) {
            TransferRoute(from, to, feasible = true, feePerMwh = hubFeePerMwh, expectedDays = hubExpectedDays, restrictions = mapOf("via" to "AIB_HUB"))
        } else {
            TransferRoute(from, to, feasible = false, feePerMwh = BigDecimal.ZERO, expectedDays = 0, restrictions = mapOf("reason" to "no hub route"))
        }
    }
}
