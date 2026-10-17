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

/** MtM and NRV policies: a change takes effect when a second person in accounting approves it in the inbox. */
@Service
@Transactional
class AccountingPolicyService(
    private val repository: PeriodEndRepository,
    private val referenceData: ReferenceDataQuery,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) : PeriodEndQuery {
    fun requestPolicyChange(command: RequestPolicyChange): PolicyChangeView {
        val actor = actorProvider.current()
        actor.requireRole(Role.ACCOUNTANT)
        command.legalEntityId?.let { referenceData.requireLegalEntity(it) }
        command.legalEntityId?.let(actor::requireEntity)
        if (command.reason.isBlank()) throw ValidationException("A policy change needs a reason")
        when (command.kind) {
            PolicyKind.MTM -> if (command.bookType == null || command.treatment == null) {
                throw ValidationException(
                    "An MtM policy names a book type and a treatment",
                )
            }
            PolicyKind.NRV -> {
                val values = listOfNotNull(command.gooCostToSellPerMwh, command.allowanceCostToSell)
                if (values.size != 2 || command.expiryDays == null || command.currency.isNullOrBlank()) {
                    throw ValidationException("An NRV policy names costs to sell for certificates and allowances, expiry days and a currency")
                }
                if (values.any { it.signum() < 0 } || command.expiryDays < 0) throw ValidationException("Costs to sell and expiry days cannot be negative")
            }
        }
        val change = PolicyChangeView(
            Ids.newId(),
            command.legalEntityId,
            command.kind,
            command,
            PolicyChangeStatus.PENDING,
            actor.userId,
            clock.instant(),
            null,
            null,
        )
        repository.insertChange(change)
        events.publishEvent(PolicyChangeRequested(change))
        return change
    }

    /** From the approval task: the change applies and counts as signed off by the approver. */
    fun applyPolicyChange(changeId: UUID, by: String) {
        val change = pending(changeId)
        SecondPerson.require(by, change.requestedBy) { "A policy change is approved by someone other than who requested it" }
        val now = clock.instant()
        val c = change.change
        when (change.kind) {
            PolicyKind.MTM -> repository.upsertMtmPolicy(
                MtmPolicyView(
                    Ids.newId(),
                    c.legalEntityId,
                    checkNotNull(c.bookType) { "MtM policy change $changeId has no book type" },
                    checkNotNull(c.treatment) { "MtM policy change $changeId has no treatment" },
                    by,
                    now,
                ),
            )
            PolicyKind.NRV -> repository.upsertNrvPolicy(
                NrvPolicyView(
                    Ids.newId(),
                    c.legalEntityId,
                    checkNotNull(c.gooCostToSellPerMwh) { "NRV policy change $changeId has no GoO cost to sell" },
                    checkNotNull(c.allowanceCostToSell) { "NRV policy change $changeId has no allowance cost to sell" },
                    checkNotNull(c.expiryDays) { "NRV policy change $changeId has no expiry days" },
                    checkNotNull(c.currency) { "NRV policy change $changeId has no currency" }.uppercase(),
                    by,
                    now,
                ),
            )
        }
        repository.decideChange(changeId, PolicyChangeStatus.APPLIED, by, now)
    }

    fun rejectPolicyChange(changeId: UUID, by: String) {
        pending(changeId)
        repository.decideChange(changeId, PolicyChangeStatus.REJECTED, by, clock.instant())
    }

    private fun pending(changeId: UUID): PolicyChangeView {
        val change = repository.change(changeId, lock = true) ?: throw NotFoundException("PolicyChange", changeId)
        if (change.status != PolicyChangeStatus.PENDING) throw ValidationException("Policy change $changeId is ${change.status}")
        return change
    }

    override fun mtmPolicies(legalEntityId: UUID?): List<MtmPolicyView> {
        legalEntityId?.let(actorProvider.current()::requireEntity)
        return repository.mtmPolicies().filter { it.legalEntityId == null || it.legalEntityId == legalEntityId }
    }

    override fun nrvPolicy(legalEntityId: UUID?): NrvPolicyView {
        legalEntityId?.let(actorProvider.current()::requireEntity)
        val all = repository.nrvPolicies()
        return all.firstOrNull { legalEntityId != null && it.legalEntityId == legalEntityId } ?: all.first { it.legalEntityId == null }
    }

    override fun policyChanges(status: PolicyChangeStatus?): List<PolicyChangeView> =
        repository.changes(status).filter { actorProvider.current().canAccess(it.legalEntityId) }

    override fun runs(legalEntityId: UUID, period: YearMonth?): List<PeriodEndRunView> {
        actorProvider.current().requireEntity(legalEntityId)
        return repository.runs(legalEntityId, period?.toString())
    }
}
