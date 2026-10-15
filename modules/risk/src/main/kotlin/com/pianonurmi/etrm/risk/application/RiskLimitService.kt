package com.pianonurmi.etrm.risk.application

import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.SecondPerson
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.product.api.ProductBucketQuery
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.risk.api.BreachStatus
import com.pianonurmi.etrm.risk.api.CreditSupportRequested
import com.pianonurmi.etrm.risk.api.CreditSupportStatus
import com.pianonurmi.etrm.risk.api.CreditSupportView
import com.pianonurmi.etrm.risk.api.LimitActivated
import com.pianonurmi.etrm.risk.api.LimitBreachResolved
import com.pianonurmi.etrm.risk.api.LimitChangeRequested
import com.pianonurmi.etrm.risk.api.LimitStatus
import com.pianonurmi.etrm.risk.api.LimitType
import com.pianonurmi.etrm.risk.api.LimitUtilisationView
import com.pianonurmi.etrm.risk.api.RequestCreditSupport
import com.pianonurmi.etrm.risk.api.RequestLimit
import com.pianonurmi.etrm.risk.api.RiskCommand
import com.pianonurmi.etrm.risk.api.RiskLimitView
import com.pianonurmi.etrm.risk.domain.RiskRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Risk limits and credit support under four eyes (ADR 0022): they take effect only after a second person in risk
 * approves them in the inbox.
 */
@Service
@Transactional
class RiskLimitService(
    private val repository: RiskRepository,
    private val referenceData: ReferenceDataQuery,
    private val buckets: ProductBucketQuery,
    private val measurement: LimitMeasurement,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) : RiskCommand {
    override fun requestLimit(command: RequestLimit): RiskLimitView {
        val actor = actorProvider.current()
        actor.requireRole(Role.RISK)
        command.legalEntityId?.let { referenceData.requireLegalEntity(it) }
        command.legalEntityId?.let(actor::requireEntity)
        validate(command)
        val limit = RiskLimitView(
            Ids.newId(),
            command.legalEntityId,
            command.type,
            command.scope.copy(allowanceProductCode = command.scope.allowanceProductCode?.uppercase()),
            command.amount.setScale(2, RoundingMode.HALF_EVEN),
            command.currency?.uppercase(),
            command.warningPct,
            command.validFrom ?: LocalDate.now(clock),
            command.validTo,
            LimitStatus.PENDING_APPROVAL,
            command.replaces,
            command.reason.trim(),
            actor.userId,
            clock.instant(),
            null,
            null,
        )
        repository.insertLimit(limit)
        events.publishEvent(LimitChangeRequested(limit))
        return limit
    }

    /** Part of the module's command API; the measuring itself is [LimitMeasurement]'s. */
    override fun measure(legalEntityId: UUID): List<LimitUtilisationView> = measurement.measure(legalEntityId)

    private fun validate(command: RequestLimit) {
        if (command.reason.isBlank()) throw ValidationException("A limit change needs a reason")
        if (command.amount.signum() < 0) throw ValidationException("A limit cannot be negative")
        if (command.warningPct !in 1..PERCENT) throw ValidationException("The warning level is a percentage from 1 to 100")
        if (command.validTo != null && command.validFrom != null && !command.validTo.isAfter(command.validFrom)) {
            throw ValidationException("validTo must follow validFrom")
        }
        validateScope(command)
        if (command.type != LimitType.CREDIT_EXPOSURE && command.legalEntityId == null) throw ValidationException("Only credit limits apply across entities")
        command.scope.bookId?.let { book ->
            if (referenceData.requireBook(book).legalEntityId != command.legalEntityId) throw ValidationException("The book belongs to another legal entity")
        }
        command.replaces?.let { id ->
            val replaced = repository.limit(id) ?: throw NotFoundException("RiskLimit", id)
            if (replaced.status != LimitStatus.ACTIVE || replaced.type != command.type) {
                throw ValidationException("Only an active limit of the same type can be replaced")
            }
        }
    }

    /** What each limit type needs in its scope and currency. */
    private fun validateScope(command: RequestLimit) {
        when (command.type) {
            LimitType.CREDIT_EXPOSURE -> {
                val counterparty = command.scope.counterpartyId ?: throw ValidationException("A credit limit names its counterparty (or group parent)")
                referenceData.requireCounterparty(counterparty)
                if (command.currency.isNullOrBlank()) throw ValidationException("A credit limit has a currency")
            }
            LimitType.STOP_LOSS_MTD -> if (command.currency.isNullOrBlank()) throw ValidationException("A stop loss has a currency")
            LimitType.MARGIN_LIQUIDITY -> if (command.currency.isNullOrBlank()) throw ValidationException("A margin liquidity limit has a currency")
            LimitType.POSITION_NET -> if (command.scope.productFamily == null) throw ValidationException("A position limit names a product family")
            LimitType.TENOR_MAX_MONTHS, LimitType.COMPLIANCE_SHORT -> Unit
            LimitType.GOO_CONCENTRATION -> {
                val code = command.scope.bucketCode ?: throw ValidationException("A concentration limit names its product bucket")
                if (buckets.buckets(true).none { it.code == code }) throw ValidationException("No active product bucket $code")
                requireEurOrMwh(command)
            }
            LimitType.GOO_EXPIRY -> {
                val days = command.scope.horizonDays ?: throw ValidationException("An expiry limit has a horizon in days")
                if (days !in 1..MAX_HORIZON_DAYS) throw ValidationException("The horizon is 1 to $MAX_HORIZON_DAYS days")
                if (!command.currency.isNullOrBlank()) throw ValidationException("An expiry limit is in MWh")
            }
            LimitType.GOO_BASIS -> requireEurOrMwh(command)
        }
        command.scope.productionYear?.let { year ->
            if (command.type !in GOO_LIMITS) throw ValidationException("Only GoO limits are set per vintage")
            if (year !in MIN_VINTAGE..MAX_VINTAGE) throw ValidationException("The vintage is a year from $MIN_VINTAGE to $MAX_VINTAGE")
        }
        if (command.type in GOO_LIMITS && command.scope.bookId != null) {
            // Certificates serve obligations across books: the flow nets the whole entity, it cannot be split by book.
            throw ValidationException("GoO limits apply to the whole legal entity, not to a book")
        }
    }

    private fun requireEurOrMwh(command: RequestLimit) {
        if (!command.currency.isNullOrBlank() && command.currency != EUR) throw ValidationException("A GoO limit is in MWh, or in EUR")
    }

    /** From the approval task: the limit becomes active, replacing the one it names. */
    fun approveLimit(id: UUID, by: String): RiskLimitView {
        val limit = repository.limit(id, lock = true) ?: throw NotFoundException("RiskLimit", id)
        if (limit.status != LimitStatus.PENDING_APPROVAL) throw ValidationException("Limit $id is ${limit.status}")
        SecondPerson.require(by, limit.requestedBy) { "A limit is approved by someone other than who requested it" }
        val now = clock.instant()
        limit.replaces?.let { replaced ->
            repository.setLimitStatus(replaced, LimitStatus.RETIRED, null, null)
            // A breach of the replaced limit ends with it; the new limit is measured on its own.
            repository.openBreach(replaced)?.let { breach ->
                val closed = breach.copy(closedAt = now, status = BreachStatus.CLOSED)
                repository.updateBreach(closed)
                events.publishEvent(LimitBreachResolved(closed, repository.limit(replaced)!!))
            }
        }
        repository.setLimitStatus(id, LimitStatus.ACTIVE, by, now)
        val active = limit.copy(status = LimitStatus.ACTIVE, approvedBy = by, approvedAt = now)
        events.publishEvent(LimitActivated(active))
        return active
    }

    fun rejectLimit(id: UUID) {
        val limit = repository.limit(id, lock = true) ?: throw NotFoundException("RiskLimit", id)
        if (limit.status != LimitStatus.PENDING_APPROVAL) throw ValidationException("Limit $id is ${limit.status}")
        repository.setLimitStatus(id, LimitStatus.REJECTED, null, null)
    }

    override fun requestCreditSupport(command: RequestCreditSupport): CreditSupportView {
        val actor = actorProvider.current()
        actor.requireRole(Role.RISK, Role.BACK_OFFICE)
        command.legalEntityId?.let(actor::requireEntity)
        referenceData.requireCounterparty(command.counterpartyId)
        if (command.amount.signum() <= 0) throw ValidationException("Credit support amount must be positive")
        val support = CreditSupportView(
            Ids.newId(),
            command.legalEntityId,
            command.counterpartyId,
            command.kind,
            command.amount,
            command.currency.uppercase(),
            command.validFrom,
            command.validTo,
            command.reference,
            CreditSupportStatus.PENDING_APPROVAL,
            actor.userId,
            clock.instant(),
            null,
            null,
        )
        repository.insertCreditSupport(support)
        events.publishEvent(CreditSupportRequested(support))
        return support
    }

    fun approveCreditSupport(id: UUID, by: String): CreditSupportView {
        val support = repository.creditSupport(id, lock = true) ?: throw NotFoundException("CreditSupport", id)
        if (support.status != CreditSupportStatus.PENDING_APPROVAL) throw ValidationException("Credit support $id is ${support.status}")
        SecondPerson.require(by, support.requestedBy) { "Credit support is approved by someone other than who recorded it" }
        val now = clock.instant()
        repository.setCreditSupportStatus(id, CreditSupportStatus.ACTIVE, by, now)
        return support.copy(status = CreditSupportStatus.ACTIVE, approvedBy = by, approvedAt = now)
    }

    fun rejectCreditSupport(id: UUID) {
        val support = repository.creditSupport(id, lock = true) ?: throw NotFoundException("CreditSupport", id)
        if (support.status != CreditSupportStatus.PENDING_APPROVAL) throw ValidationException("Credit support $id is ${support.status}")
        repository.setCreditSupportStatus(id, CreditSupportStatus.REJECTED, null, null)
    }

    private companion object {
        const val MAX_HORIZON_DAYS = 730
        const val MIN_VINTAGE = 2000
        const val MAX_VINTAGE = 2100
    }
}
