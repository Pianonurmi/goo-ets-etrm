package com.pianonurmi.etrm.risk.application

import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.risk.api.BreachStatus
import com.pianonurmi.etrm.risk.api.LimitBreachResolved
import com.pianonurmi.etrm.risk.api.LimitBreachView
import com.pianonurmi.etrm.risk.api.LimitBreached
import com.pianonurmi.etrm.risk.api.LimitType
import com.pianonurmi.etrm.risk.api.LimitUtilisationView
import com.pianonurmi.etrm.risk.api.RiskLimitView
import com.pianonurmi.etrm.risk.domain.RiskRepository
import com.pianonurmi.etrm.risk.domain.Utilisation
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Measurement (ADR 0022): stores each counterparty's current exposure and each limit's utilisation, and opens or
 * closes breaches. Each limit type has its [LimitMeasurer].
 */
@Service
@Transactional
class LimitMeasurement(
    private val repository: RiskRepository,
    private val referenceData: ReferenceDataQuery,
    private val exposures: CreditExposureService,
    measurers: List<LimitMeasurer>,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    private val measurers: Map<LimitType, LimitMeasurer> = measurers.flatMap { measurer -> measurer.types.map { it to measurer } }.toMap()

    init {
        val missing = LimitType.entries.filterNot { it in this.measurers }
        require(missing.isEmpty()) { "No measurer for limit types $missing" }
    }

    /** Measures exposures and every active limit of the entity now, for a person in risk. */
    fun measure(legalEntityId: UUID): List<LimitUtilisationView> {
        actorProvider.current().requireRole(Role.RISK)
        actorProvider.current().requireEntity(legalEntityId)
        return measureEntity(legalEntityId)
    }

    /** Exposures and limits of one entity, for jobs and listeners (no role check). */
    fun measureEntity(legalEntityId: UUID): List<LimitUtilisationView> {
        val entity = referenceData.requireLegalEntity(legalEntityId)
        val today = LocalDate.now(clock)
        exposures.measureExposures(entity, today)
        return repository.activeLimits(legalEntityId, today).map { evaluate(it, today) }
    }

    /**
     * One limit against the current figures; group-wide credit limits use the exposures stored per entity. An overload
     * rather than a default date: called through the transactional proxy, a default would read the proxy's own
     * (unset) clock.
     */
    fun evaluate(limit: RiskLimitView): LimitUtilisationView = evaluate(limit, LocalDate.now(clock))

    fun evaluate(limit: RiskLimitView, today: LocalDate): LimitUtilisationView {
        val measure = measurers.getValue(limit.type).measure(limit, today)
        val last = repository.utilisation(limit.id)?.value ?: BigDecimal.ZERO
        val now = clock.instant()
        val utilisation = when {
            measure.notMeasured -> Utilisation.notMeasured(limit.id, last, limit, measure.detail, now, measure.gaps)
            // Something was left out, so the figure is a floor: the last value measured stays when it was higher.
            measure.gaps.isFloor -> Utilisation.of(limit.id, measure.value.max(last), limit, measure.detail, now, measure.gaps)
            // An assumption stands in for a figure (an option at delta one): the value is the conservative one, marked.
            else -> Utilisation.of(limit.id, measure.value, limit, measure.detail, now, measure.gaps)
        }
        repository.upsertUtilisation(utilisation)
        trackBreach(limit, utilisation)
        return utilisation
    }

    private fun trackBreach(limit: RiskLimitView, utilisation: LimitUtilisationView) {
        val open = repository.openBreach(limit.id)
        when {
            utilisation.status.breached && open == null -> {
                val breach = LimitBreachView(Ids.newId(), limit.id, utilisation.measuredAt, utilisation.value, utilisation.value, null, BreachStatus.OPEN)
                repository.insertBreach(breach)
                events.publishEvent(LimitBreached(breach, limit, utilisation))
            }
            utilisation.status.breached && open != null && utilisation.value > open.peakValue ->
                repository.updateBreach(open.copy(peakValue = utilisation.value))
            !utilisation.status.breached && open != null -> {
                val closed = open.copy(closedAt = utilisation.measuredAt, status = BreachStatus.CLOSED)
                repository.updateBreach(closed)
                events.publishEvent(LimitBreachResolved(closed, limit))
            }
        }
    }
}
