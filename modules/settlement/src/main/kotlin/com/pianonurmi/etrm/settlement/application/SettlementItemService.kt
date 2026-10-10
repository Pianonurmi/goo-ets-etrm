package com.pianonurmi.etrm.settlement.application

import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.DomainException
import com.pianonurmi.etrm.platform.errors.IllegalStateTransitionException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.referencedata.api.PartyDetailsQuery
import com.pianonurmi.etrm.referencedata.api.PartyType
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.settlement.api.FixPrice
import com.pianonurmi.etrm.settlement.api.ProposeVatRule
import com.pianonurmi.etrm.settlement.api.SettlementDirection
import com.pianonurmi.etrm.settlement.api.SettlementItemCommand
import com.pianonurmi.etrm.settlement.api.SettlementItemCreated
import com.pianonurmi.etrm.settlement.api.SettlementItemFilter
import com.pianonurmi.etrm.settlement.api.SettlementItemNeedsAttention
import com.pianonurmi.etrm.settlement.api.SettlementItemQuery
import com.pianonurmi.etrm.settlement.api.SettlementItemStatus
import com.pianonurmi.etrm.settlement.api.SettlementItemView
import com.pianonurmi.etrm.settlement.api.VatDetermination
import com.pianonurmi.etrm.settlement.api.VatRuleApproved
import com.pianonurmi.etrm.settlement.api.VatRuleCommand
import com.pianonurmi.etrm.settlement.api.VatRuleProposed
import com.pianonurmi.etrm.settlement.api.VatRuleQuery
import com.pianonurmi.etrm.settlement.api.VatRuleStatus
import com.pianonurmi.etrm.settlement.api.VatRuleView
import com.pianonurmi.etrm.settlement.api.VatTreatment
import com.pianonurmi.etrm.settlement.domain.Amounts
import com.pianonurmi.etrm.settlement.domain.PriceFixingView
import com.pianonurmi.etrm.settlement.domain.SettlementItemRepository
import com.pianonurmi.etrm.settlement.domain.VatRuleRepository
import com.pianonurmi.etrm.settlement.domain.VatRules
import com.pianonurmi.etrm.trade.api.DeliveryRecorded
import com.pianonurmi.etrm.trade.api.PriceType
import com.pianonurmi.etrm.trade.api.Side
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** VAT rules are data: proposed as DRAFT, approved by a second person, retired rather than changed. */
@Service
@Transactional
class VatRuleService(
    private val repository: VatRuleRepository,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
) : VatRuleCommand, VatRuleQuery {
    override fun propose(command: ProposeVatRule): VatRuleView {
        val actor = actorProvider.current()
        actor.requireRole(Role.ACCOUNTANT, Role.BACK_OFFICE)
        val code = command.code.trim().uppercase()
        if (!Regex("^[A-Z0-9-]{2,40}$").matches(code)) throw ValidationException("Rule code $code is not valid")
        if (command.productFamily != "*") {
            runCatching {
                ProductFamily.valueOf(command.productFamily)
            }.getOrElse { throw ValidationException("Unknown product family") }
        }
        if (command.supplierCountry != "*" && !Regex(
                "^[A-Z]{2}$",
            ).matches(command.supplierCountry)
        ) {
            throw ValidationException("Supplier country must be ISO or *")
        }
        if (command.treatment != VatTreatment.STANDARD && command.rate.signum() != 0) throw ValidationException("Only standard-rated rules have a rate")
        if (command.treatment == VatTreatment.STANDARD && command.rate.signum() <= 0) throw ValidationException("A standard rate must be positive")
        if (command.treatment.reverseCharge && command.legalText.isNullOrBlank()) {
            throw ValidationException(
                "Reverse charge rules need the legal text printed on invoices",
            )
        }
        val rule = VatRuleView(
            id = Ids.newId(),
            code = code,
            version = repository.nextVersion(code),
            description = command.description.trim(),
            productFamily = command.productFamily,
            supplierCountry = command.supplierCountry,
            scope = command.scope,
            treatment = command.treatment,
            rate = command.rate,
            validFrom = command.validFrom,
            validTo = command.validTo,
            legalText = command.legalText?.trim(),
            requiresSignOff = false,
            status = VatRuleStatus.DRAFT,
            createdBy = actor.userId,
            approvedBy = null,
        )
        repository.insert(rule)
        events.publishEvent(VatRuleProposed(rule))
        return rule
    }

    override fun approve(ruleId: UUID, by: String): VatRuleView {
        val rule = repository.find(ruleId, forUpdate = true) ?: throw NotFoundException("VatRule", ruleId)
        if (rule.status != VatRuleStatus.DRAFT) throw IllegalStateTransitionException("VatRule ${rule.code}", rule.status.name, "APPROVED")
        if (rule.createdBy == by) throw SegregationOfDutiesException("A VAT rule cannot be approved by the person who proposed it")
        val approved = rule.copy(status = VatRuleStatus.APPROVED, approvedBy = by)
        repository.update(approved)
        events.publishEvent(VatRuleApproved(approved))
        return approved
    }

    override fun retire(ruleId: UUID, reason: String): VatRuleView {
        actorProvider.current().requireRole(Role.ACCOUNTANT, Role.BACK_OFFICE)
        val rule = repository.find(ruleId, forUpdate = true) ?: throw NotFoundException("VatRule", ruleId)
        if (rule.status == VatRuleStatus.RETIRED) return rule
        val retired = rule.copy(status = VatRuleStatus.RETIRED)
        repository.update(retired)
        return retired
    }

    @Transactional(readOnly = true)
    override fun rules(includeRetired: Boolean): List<VatRuleView> = repository.all().filter { includeRetired || it.status != VatRuleStatus.RETIRED }

    @Transactional(readOnly = true)
    override fun determine(productFamily: ProductFamily, supplierCountry: String, customerCountry: String, taxPointDate: LocalDate): VatDetermination =
        VatRules.select(repository.all(), productFamily, supplierCountry.uppercase(), customerCountry.uppercase(), taxPointDate)
}

/**
 * One settlement item per recorded delivery. Creating it never fails the delivery: an item whose price is not fixed,
 * whose VAT cannot be determined or whose customer lacks a VAT number is parked as NEEDS_ATTENTION.
 */
@Service
@Transactional
class SettlementItemService(
    private val repository: SettlementItemRepository,
    private val vat: VatRuleService,
    private val referenceData: ReferenceDataQuery,
    private val parties: PartyDetailsQuery,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
) : SettlementItemCommand, SettlementItemQuery {
    private val log = LoggerFactory.getLogger(SettlementItemService::class.java)

    @EventListener
    fun on(event: DeliveryRecorded) {
        val o = event.obligation
        val leg = event.trade.legs.firstOrNull { it.id == o.legId }
        val product = o.allowanceProductCode ?: "Guarantees of origin (${o.energyCarrier?.name ?: "ELECTRICITY"})"
        val draft = SettlementItemView(
            id = Ids.newId(),
            deliveryId = event.deliveryId,
            tradeId = o.tradeId,
            tradeNumber = o.tradeNumber,
            scheduleId = o.scheduleId,
            legalEntityId = o.legalEntityId,
            counterpartyId = o.counterpartyId,
            direction = if (o.side == Side.SELL) SettlementDirection.RECEIVABLE else SettlementDirection.PAYABLE,
            productFamily = o.productFamily,
            description = "$product, trade ${o.tradeNumber}, delivered ${event.deliveredOn}",
            quantity = event.quantity,
            unit = leg?.unit?.name ?: if (o.productFamily == ProductFamily.EMISSION_ALLOWANCE) "TCO2E" else "MWH",
            unitPrice = if (o.priceType == PriceType.FIXED) o.price else null,
            currency = o.priceCurrency.currencyCode,
            netAmount = null,
            deliveredOn = event.deliveredOn,
            vatRuleCode = null,
            vatRuleVersion = null,
            vatTreatment = null,
            vatRate = null,
            vatAmount = null,
            status = SettlementItemStatus.NEEDS_ATTENTION,
            problem = null,
            invoiceId = null,
            reference = event.reference,
            version = 1,
        )
        val item = evaluate(draft)
        if (!repository.insert(item)) return
        events.publishEvent(SettlementItemCreated(item))
        if (item.status == SettlementItemStatus.NEEDS_ATTENTION) {
            log.warn("Settlement item for delivery {} of {} needs attention: {}", event.deliveryId, o.tradeNumber, item.problem)
            events.publishEvent(SettlementItemNeedsAttention(item))
        }
    }

    override fun fixPrice(command: FixPrice): List<SettlementItemView> {
        val actor = actorProvider.current()
        actor.requireRole(Role.MIDDLE_OFFICE, Role.BACK_OFFICE)
        if (command.price.signum() < 0) throw ValidationException("Price must not be negative")
        val items = repository.forSchedule(command.scheduleId)
        val currency = items.firstOrNull()?.currency
            ?: throw NotFoundException("Delivered schedule", command.scheduleId)
        repository.upsertPriceFixing(PriceFixingView(command.scheduleId, items.first().tradeId, command.price, currency, command.note, actor.userId))
        return items.filter { it.status == SettlementItemStatus.NEEDS_ATTENTION }.map { reevaluate(it.id) }
    }

    override fun reevaluate(itemId: UUID): SettlementItemView {
        val current = repository.find(itemId, forUpdate = true) ?: throw NotFoundException("SettlementItem", itemId)
        if (current.status != SettlementItemStatus.NEEDS_ATTENTION && current.status != SettlementItemStatus.OPEN) {
            throw ConflictException("Item $itemId is ${current.status}")
        }
        val next = evaluate(current).copy(version = current.version + 1)
        repository.update(next)
        return next
    }

    @Transactional(readOnly = true)
    override fun items(filter: SettlementItemFilter): List<SettlementItemView> = repository.find(filter)

    @Transactional(readOnly = true)
    override fun item(id: UUID): SettlementItemView? = repository.find(id)

    /** Prices the item and determines its VAT; anything missing parks it with the reason. */
    private fun evaluate(item: SettlementItemView): SettlementItemView = try {
        val price = item.unitPrice ?: repository.priceFixing(item.scheduleId)?.price
            ?: return item.needs("The price of trade ${item.tradeNumber} is not fixed yet")
        val net = Amounts.money(item.quantity * price)
        val supply = supplyOf(item)
        val determination = vat.determine(item.productFamily, supply.supplierCountry, supply.customerCountry, item.deliveredOn)
        val rule = determination.rule ?: return item.needs(determination.problem!!).copy(unitPrice = price, netAmount = net)
        if (rule.treatment == VatTreatment.REVERSE_CHARGE_CROSS_BORDER && supply.customerVatNumber == null) {
            return item.needs("The customer has no VAT number; cross-border reverse charge needs one").copy(unitPrice = price, netAmount = net)
        }
        item.copy(
            unitPrice = price,
            netAmount = net,
            vatRuleCode = rule.code,
            vatRuleVersion = rule.version,
            vatTreatment = rule.treatment,
            vatRate = rule.rate,
            vatAmount = if (rule.treatment == VatTreatment.STANDARD) Amounts.vat(net, rule.rate) else BigDecimal.ZERO.setScale(2),
            status = SettlementItemStatus.OPEN,
            problem = null,
        )
    } catch (e: DomainException) {
        item.needs(e.message ?: e.code)
    }

    private fun SettlementItemView.needs(problem: String) = copy(status = SettlementItemStatus.NEEDS_ATTENTION, problem = problem)

    private data class Supply(val supplierCountry: String, val customerCountry: String, val customerVatNumber: String?)

    /** Seller and buyer of the delivery: our entity sells on receivables and buys on payables. */
    private fun supplyOf(item: SettlementItemView): Supply {
        val entity = referenceData.requireLegalEntity(item.legalEntityId)
        val counterparty = referenceData.requireCounterparty(item.counterpartyId)
        val selling = item.direction == SettlementDirection.RECEIVABLE
        val customerCountry = if (selling) counterparty.country else entity.country
        val customerVat = if (selling) {
            parties.vatRegistration(PartyType.COUNTERPARTY, counterparty.id, customerCountry, item.deliveredOn)?.vatNumber ?: counterparty.vatNumber
        } else {
            parties.vatRegistration(PartyType.LEGAL_ENTITY, entity.id, customerCountry, item.deliveredOn)?.vatNumber ?: entity.vatNumber
        }
        return Supply(if (selling) entity.country else counterparty.country, customerCountry, customerVat)
    }
}
