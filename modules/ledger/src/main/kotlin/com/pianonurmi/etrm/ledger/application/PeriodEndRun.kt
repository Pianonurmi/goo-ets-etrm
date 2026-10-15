package com.pianonurmi.etrm.ledger.application

import com.pianonurmi.etrm.allocation.api.AllocationQuery
import com.pianonurmi.etrm.allocation.api.ObligationQuery
import com.pianonurmi.etrm.clearing.api.ClearingQuery
import com.pianonurmi.etrm.etscompliance.api.EmissionKind
import com.pianonurmi.etrm.etscompliance.api.EmissionStatus
import com.pianonurmi.etrm.etscompliance.api.EtsComplianceQuery
import com.pianonurmi.etrm.inventory.api.LotFilter
import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.LotView
import com.pianonurmi.etrm.ledger.api.JournalEntryView
import com.pianonurmi.etrm.ledger.api.MtmPolicyView
import com.pianonurmi.etrm.ledger.api.MtmTreatment
import com.pianonurmi.etrm.ledger.api.NrvPolicyView
import com.pianonurmi.etrm.ledger.api.PeriodEndCommand
import com.pianonurmi.etrm.ledger.api.PeriodEndCompleted
import com.pianonurmi.etrm.ledger.api.PeriodEndComponent
import com.pianonurmi.etrm.ledger.api.PeriodEndComponentKind
import com.pianonurmi.etrm.ledger.api.PeriodEndQuery
import com.pianonurmi.etrm.ledger.api.PeriodEndRunView
import com.pianonurmi.etrm.ledger.api.PeriodStatus
import com.pianonurmi.etrm.ledger.api.PolicyChangeRequested
import com.pianonurmi.etrm.ledger.api.PolicyChangeStatus
import com.pianonurmi.etrm.ledger.api.PolicyChangeView
import com.pianonurmi.etrm.ledger.api.PolicyKind
import com.pianonurmi.etrm.ledger.api.PostingFacts
import com.pianonurmi.etrm.ledger.api.PostingRuleView
import com.pianonurmi.etrm.ledger.api.PostingSide
import com.pianonurmi.etrm.ledger.api.RequestPolicyChange
import com.pianonurmi.etrm.ledger.domain.CostLayerRepository
import com.pianonurmi.etrm.ledger.domain.CostingMethod
import com.pianonurmi.etrm.ledger.domain.EmissionFigures
import com.pianonurmi.etrm.ledger.domain.JournalRepository
import com.pianonurmi.etrm.ledger.domain.PeriodEndMath
import com.pianonurmi.etrm.ledger.domain.PeriodEndRepository
import com.pianonurmi.etrm.ledger.domain.PeriodRepository
import com.pianonurmi.etrm.ledger.domain.RuleAccounts
import com.pianonurmi.etrm.marketdata.api.CurveQuery
import com.pianonurmi.etrm.marketdata.api.FxRates
import com.pianonurmi.etrm.marketdata.api.IndexFixings
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.SecondPerson
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.product.api.ProductBucketQuery
import com.pianonurmi.etrm.product.api.ProductCatalogueQuery
import com.pianonurmi.etrm.product.api.criteria.CriteriaService
import com.pianonurmi.etrm.referencedata.api.AllowanceClassification
import com.pianonurmi.etrm.referencedata.api.AllowanceCostingMethod
import com.pianonurmi.etrm.referencedata.api.BookType
import com.pianonurmi.etrm.referencedata.api.FreeAllocationPolicy
import com.pianonurmi.etrm.referencedata.api.LegalEntityView
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.valuation.api.ResultStatus
import com.pianonurmi.etrm.valuation.api.ValuationQuery
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Period-end measurements and postings (ADR 0021). Every component ([PeriodEndStep], in order) is measured as the
 * balance it should have at the end of the period and posted as the difference to what is booked, so running the
 * period end again posts only what changed. MtM and FX revaluation are reversed on the first day of the next period.
 */
@Service
@Transactional
class PeriodEndRun(
    private val ledger: LedgerService,
    private val periods: PeriodRepository,
    private val repository: PeriodEndRepository,
    private val referenceData: ReferenceDataQuery,
    private val valuations: ValuationQuery,
    private val steps: List<PeriodEndStep>,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    fun run(legalEntityId: UUID, period: YearMonth): PeriodEndRunView {
        val actor = actorProvider.current()
        actor.requireRole(Role.ACCOUNTANT)
        actor.requireEntity(legalEntityId)
        val entity = referenceData.requireLegalEntity(legalEntityId)
        if (status(legalEntityId, period) == PeriodStatus.CLOSED) throw ValidationException("Period $period is hard-closed")
        if (status(legalEntityId, period.plusMonths(1)) != PeriodStatus.OPEN) {
            throw ValidationException("Period ${period.plusMonths(1)} must be open: MtM and FX revaluation reverse on its first day")
        }
        val lastBusinessDay = PeriodEndMath.lastBusinessDay(period)
        val valuation = valuations.official(legalEntityId, period.atEndOfMonth(), orEarlier = true)
            ?.takeIf { !it.valuationDate.isBefore(lastBusinessDay) }
            ?: throw ValidationException("No official valuation of $lastBusinessDay for ${entity.code}; sign off the valuation run first")
        ledger.lockEntity(legalEntityId)
        ledger.ensureChartOfAccounts(legalEntityId)
        val ctx = PeriodEndContext(entity, period, Ids.newId(), ledger.rules(legalEntityId), valuation.id)
        steps.forEach { it.apply(ctx) }
        val run = PeriodEndRunView(
            ctx.runId,
            legalEntityId,
            period.toString(),
            repository.nextRunSeq(legalEntityId, period.toString()),
            valuation.id,
            ctx.components,
            ctx.entries.map { it.id },
            ctx.warnings,
            actor.userId,
            clock.instant(),
        )
        repository.insertRun(run)
        events.publishEvent(PeriodEndCompleted(run))
        return run
    }

    private fun status(legalEntityId: UUID, period: YearMonth): PeriodStatus = periods.find(legalEntityId, period.toString())?.status ?: PeriodStatus.OPEN
}

/** The module's period-end command API over the run and the policy changes, each in its own transaction. */
@Service
class PeriodEndCommands(private val run: PeriodEndRun, private val policies: AccountingPolicyService) : PeriodEndCommand {
    override fun run(legalEntityId: UUID, period: YearMonth): PeriodEndRunView = run.run(legalEntityId, period)

    override fun requestPolicyChange(command: RequestPolicyChange): PolicyChangeView = policies.requestPolicyChange(command)
}
