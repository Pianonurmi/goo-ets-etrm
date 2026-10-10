package com.pianonurmi.etrm.trade.application

import com.pianonurmi.etrm.document.api.DocumentCommand
import com.pianonurmi.etrm.document.api.RenderPdf
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.IllegalStateTransitionException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.text.Csv
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.referencedata.api.CounterpartyType
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.trade.api.ConfirmTrade
import com.pianonurmi.etrm.trade.api.ConfirmationClosed
import com.pianonurmi.etrm.trade.api.ConfirmationCommand
import com.pianonurmi.etrm.trade.api.ConfirmationDirection
import com.pianonurmi.etrm.trade.api.ConfirmationMatched
import com.pianonurmi.etrm.trade.api.ConfirmationMismatched
import com.pianonurmi.etrm.trade.api.ConfirmationQuery
import com.pianonurmi.etrm.trade.api.ConfirmationSource
import com.pianonurmi.etrm.trade.api.ConfirmationStatus
import com.pianonurmi.etrm.trade.api.ConfirmationTerms
import com.pianonurmi.etrm.trade.api.ConfirmationView
import com.pianonurmi.etrm.trade.api.DeliveryTerms
import com.pianonurmi.etrm.trade.api.LegTerms
import com.pianonurmi.etrm.trade.api.PartyTerms
import com.pianonurmi.etrm.trade.api.ReceiveConfirmation
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeAmended
import com.pianonurmi.etrm.trade.api.TradeApproved
import com.pianonurmi.etrm.trade.api.TradeCaptured
import com.pianonurmi.etrm.trade.api.TradeFilter
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.trade.domain.ConfirmationMatcher
import com.pianonurmi.etrm.trade.domain.ConfirmationRepository
import com.pianonurmi.etrm.trade.domain.ConfirmationTolerance
import com.pianonurmi.etrm.trade.domain.CriterionTerms
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** Matching tolerances per product family (`etrm.trade.confirmation.tolerances.<FAMILY>.price` etc.). */
@ConfigurationProperties("etrm.trade.confirmation")
data class ConfirmationSettings(
    val tolerances: Map<ProductFamily, ConfirmationTolerance> = mapOf(
        ProductFamily.GUARANTEE_OF_ORIGIN to ConfirmationTolerance(price = BigDecimal("0.0001"), amount = BigDecimal("0.01")),
        ProductFamily.EMISSION_ALLOWANCE to ConfirmationTolerance(price = BigDecimal("0.001"), amount = BigDecimal("0.01")),
    ),
) {
    fun tolerance(family: ProductFamily): ConfirmationTolerance = tolerances[family] ?: ConfirmationTolerance()
}

@Configuration
@EnableConfigurationProperties(ConfirmationSettings::class)
class ConfirmationConfiguration

/**
 * Our confirmation is issued for every approved external trade (terms as JSON, and a PDF). The counterparty's
 * confirmation is matched field by field: a match confirms the trade as `system`; a mismatch waits for a second person
 * to override or dispute it. Amending the trade withdraws our confirmation and re-opens theirs for matching.
 */
@Service
@Transactional
class ConfirmationService(
    private val repository: ConfirmationRepository,
    private val trades: TradeService,
    private val referenceData: ReferenceDataQuery,
    private val documents: DocumentCommand,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
    private val settings: ConfirmationSettings = ConfirmationSettings(),
) : ConfirmationCommand, ConfirmationQuery {
    override fun issue(tradeId: UUID): ConfirmationView {
        val trade = trades.requireTrade(tradeId)
        if (trade.status !in ISSUABLE) throw IllegalStateTransitionException("Trade ${trade.tradeNumber}", trade.status.name, "confirmation issued")
        val terms = terms(trade)
        val current = repository.forTrade(tradeId).firstOrNull { it.direction == ConfirmationDirection.OURS && it.status == ConfirmationStatus.ISSUED }
        if (current != null) {
            if (current.terms == terms) return current
            repository.update(current.copy(status = ConfirmationStatus.SUPERSEDED, note = "terms changed in version ${trade.version}"))
        }
        val id = Ids.newId()
        val document = documents.renderPdf(
            RenderPdf(
                kind = "TRADE_CONFIRMATION",
                ownerType = "TRADE_CONFIRMATION",
                ownerRef = id,
                legalEntityId = trade.legalEntityId,
                title = "Confirmation ${trade.tradeNumber}",
                fileName = "${trade.tradeNumber}-v${trade.version}-confirmation.pdf",
                template = "trade-confirmation",
                model = pdfModel(trade, terms),
            ),
        )
        val ours = ConfirmationView(
            id = id,
            tradeId = trade.id,
            tradeNumber = trade.tradeNumber,
            tradeVersion = trade.version,
            direction = ConfirmationDirection.OURS,
            source = ConfirmationSource.GENERATED,
            reference = "${trade.tradeNumber}/v${trade.version}",
            terms = terms,
            status = ConfirmationStatus.ISSUED,
            differences = emptyList(),
            matchedAgainst = null,
            documentId = document.id,
            createdBy = actorProvider.current().userId,
            createdAt = clock.instant(),
            decidedBy = null,
            note = null,
        )
        repository.insert(ours)
        // A confirmation from the counterparty that arrived before ours (or before an amendment) is matched now.
        repository.forTrade(tradeId).filter { it.direction == ConfirmationDirection.THEIRS && it.status == ConfirmationStatus.RECEIVED }
            .forEach { match(it, ours) }
        return ours
    }

    override fun receive(command: ReceiveConfirmation): ConfirmationView {
        if (command.reference.isBlank()) throw ValidationException("The counterparty's confirmation reference is required")
        if (command.terms.legs.isEmpty()) throw ValidationException("A confirmation needs at least one leg")
        val trade = findTrade(command)
        repository.theirs(trade.id, command.reference.trim())?.let { return it }
        repository.forTrade(trade.id)
            .filter { it.direction == ConfirmationDirection.THEIRS && it.status in OPEN_THEIRS }
            .forEach { close(it.copy(status = ConfirmationStatus.SUPERSEDED, note = "superseded by ${command.reference.trim()}")) }
        val theirs = ConfirmationView(
            id = Ids.newId(),
            tradeId = trade.id,
            tradeNumber = trade.tradeNumber,
            tradeVersion = trade.version,
            direction = ConfirmationDirection.THEIRS,
            source = command.source,
            reference = command.reference.trim(),
            terms = command.terms,
            status = ConfirmationStatus.RECEIVED,
            differences = emptyList(),
            matchedAgainst = null,
            documentId = null,
            createdBy = actorProvider.current().userId,
            createdAt = clock.instant(),
            decidedBy = null,
            note = null,
        )
        repository.insert(theirs)
        val ours = currentOurs(trade.id)
        return when {
            ours != null -> match(theirs, ours)
            // Issuing ours matches every confirmation of theirs that is waiting, this one included.
            trade.status in ISSUABLE -> issue(trade.id).let { repository.find(theirs.id)!! }
            else -> theirs
        }
    }

    override fun receiveCsv(counterpartyId: UUID, content: String): List<ConfirmationView> {
        val rows = Csv.parse(content.trim())
        if (rows.size < 2) throw ValidationException("The file has no confirmation lines")
        val header = rows.first().map { it.trim().lowercase() }
        val lines = rows.drop(1).filter { row -> row.any { it.isNotBlank() } }.mapIndexed { i, row ->
            if (row.size != header.size) throw ValidationException("Line ${i + 2} has ${row.size} fields; the header has ${header.size}")
            header.zip(row.map(String::trim)).toMap()
        }
        return lines.groupBy { it.required("confirmation_ref") }.map { (reference, group) ->
            receive(ReceiveConfirmation(csvTerms(group), reference, counterpartyId, ConfirmationSource.CSV))
        }
    }

    /** Second person accepts the counterparty's confirmation despite the differences; the trade is confirmed. */
    fun override(confirmationId: UUID, by: String, note: String?): ConfirmationView {
        val theirs = openMismatch(confirmationId)
        if (theirs.createdBy == by) throw SegregationOfDutiesException("The person who received the confirmation may not override it")
        val overridden = close(theirs.copy(status = ConfirmationStatus.OVERRIDDEN, decidedBy = by, note = note))
        confirmTrade(theirs, "OVERRIDE", by)
        return overridden
    }

    /** The differences are real: the confirmation is disputed and the trade stays unconfirmed. */
    fun dispute(confirmationId: UUID, by: String, note: String?): ConfirmationView =
        close(openMismatch(confirmationId).copy(status = ConfirmationStatus.DISPUTED, decidedBy = by, note = note))

    @Transactional(readOnly = true)
    override fun confirmation(id: UUID): ConfirmationView? = repository.find(id)

    @Transactional(readOnly = true)
    override fun forTrade(tradeId: UUID): List<ConfirmationView> = repository.forTrade(tradeId)

    // ----------------------------------------------------------------------------------------------------------
    // Trade lifecycle
    // ----------------------------------------------------------------------------------------------------------

    @EventListener
    fun on(event: TradeApproved) = issueIfExternal(event.trade)

    /** Trades below the approval threshold are approved on capture. */
    @EventListener
    fun on(event: TradeCaptured) {
        if (event.trade.status == TradeStatus.APPROVED) issueIfExternal(event.trade)
    }

    /** An amendment withdraws our confirmation; the counterparty's is matched again against the next one. */
    @EventListener
    fun on(event: TradeAmended) {
        repository.forTrade(event.trade.id).forEach {
            when {
                it.direction == ConfirmationDirection.OURS && it.status == ConfirmationStatus.ISSUED ->
                    repository.update(it.copy(status = ConfirmationStatus.SUPERSEDED, note = "trade amended to version ${event.trade.version}"))
                it.direction == ConfirmationDirection.THEIRS && it.status in setOf(ConfirmationStatus.MATCHED, ConfirmationStatus.MISMATCHED) -> {
                    val reopened = it.copy(status = ConfirmationStatus.RECEIVED, differences = emptyList(), matchedAgainst = null)
                    repository.update(reopened)
                    events.publishEvent(ConfirmationClosed(reopened))
                }
            }
        }
    }

    private fun issueIfExternal(trade: TradeView) {
        if (referenceData.requireCounterparty(trade.counterpartyId).counterpartyType == CounterpartyType.INTERCOMPANY) return
        issue(trade.id)
    }

    // ----------------------------------------------------------------------------------------------------------

    private fun match(theirs: ConfirmationView, ours: ConfirmationView): ConfirmationView {
        val differences = ConfirmationMatcher.compare(ours.terms, theirs.terms, settings.tolerance(ours.terms.productFamily))
        val matched = ConfirmationMatcher.matches(differences)
        val next = theirs.copy(
            status = if (matched) ConfirmationStatus.MATCHED else ConfirmationStatus.MISMATCHED,
            differences = differences,
            matchedAgainst = ours.id,
        )
        repository.update(next)
        if (matched) {
            events.publishEvent(ConfirmationMatched(next))
            confirmTrade(next, "MATCHED", "system")
        } else {
            events.publishEvent(ConfirmationMismatched(next))
        }
        return next
    }

    private fun confirmTrade(theirs: ConfirmationView, method: String, actor: String) {
        val trade = trades.requireTrade(theirs.tradeId)
        if (trade.confirmedAt == null) trades.confirmAs(trade.id, ConfirmTrade(theirs.reference!!, method), actor)
    }

    private fun close(view: ConfirmationView): ConfirmationView {
        repository.update(view)
        events.publishEvent(ConfirmationClosed(view))
        return view
    }

    private fun openMismatch(id: UUID): ConfirmationView {
        val view = repository.find(id, forUpdate = true) ?: throw NotFoundException("TradeConfirmation", id)
        if (view.status != ConfirmationStatus.MISMATCHED) throw IllegalStateTransitionException("Confirmation ${view.reference}", view.status.name, "decided")
        return view
    }

    private fun currentOurs(tradeId: UUID): ConfirmationView? =
        repository.forTrade(tradeId).firstOrNull { it.direction == ConfirmationDirection.OURS && it.status == ConfirmationStatus.ISSUED }

    private fun findTrade(command: ReceiveConfirmation): TradeView {
        val terms = command.terms
        val trade = terms.tradeRef?.let { trades.byNumber(it.trim()) }
            ?: terms.counterpartyTradeRef?.let { ref ->
                val counterparty = command.counterpartyId ?: throw ValidationException("Name the counterparty or quote our trade number")
                trades.trades(TradeFilter(counterpartyId = counterparty, limit = 1000)).firstOrNull { it.externalRef == ref.trim() }
            }
            ?: throw NotFoundException("Trade", terms.tradeRef ?: terms.counterpartyTradeRef ?: "unreferenced confirmation")
        if (command.counterpartyId != null && command.counterpartyId != trade.counterpartyId) {
            throw ValidationException("Trade ${trade.tradeNumber} is not with counterparty ${command.counterpartyId}")
        }
        if (trade.status == TradeStatus.CANCELLED) throw ConflictException("Trade ${trade.tradeNumber} is cancelled")
        return trade
    }

    /** Our terms of the trade, read the same way the counterparty states theirs. */
    private fun terms(trade: TradeView): ConfirmationTerms {
        val entity = referenceData.requireLegalEntity(trade.legalEntityId)
        val counterparty = referenceData.requireCounterparty(trade.counterpartyId)
        val us = PartyTerms(entity.name, entity.lei)
        val them = PartyTerms(counterparty.name, counterparty.lei)
        return ConfirmationTerms(
            tradeRef = trade.tradeNumber,
            counterpartyTradeRef = trade.externalRef,
            tradeDate = trade.tradeDate,
            productFamily = trade.productFamily,
            masterAgreement = trade.masterAgreementRef,
            legs = trade.legs.map { leg ->
                val ourAccount = leg.deliveryAccountId?.let(referenceData::registryAccount)?.let { "${it.registryCode}:${it.accountNumber}" }
                val selling = leg.side == Side.SELL
                LegTerms(
                    legNo = leg.legNo,
                    seller = if (selling) us else them,
                    buyer = if (selling) them else us,
                    product = leg.allowanceProductCode ?: leg.energyCarrier?.name ?: trade.productFamily.name,
                    quantity = leg.quantity,
                    unit = leg.unit.name,
                    price = leg.price,
                    currency = leg.priceCurrency.currencyCode,
                    amount = (leg.quantity * leg.price).setScale(2, RoundingMode.HALF_EVEN),
                    deliveries = leg.deliveries.map { DeliveryTerms(it.deliveryDate, it.quantity, it.paymentDate) },
                    sellerAccount = if (selling) ourAccount else leg.counterpartyAccountRef,
                    buyerAccount = if (selling) leg.counterpartyAccountRef else ourAccount,
                    attributes = CriterionTerms.attributes(leg.criterion),
                )
            },
        )
    }

    private fun csvTerms(lines: List<Map<String, String>>): ConfirmationTerms {
        val first = lines.first()
        val price = first.decimal("price")
        val quantity = lines.sumOf { it.decimal("quantity") }
        return ConfirmationTerms(
            tradeRef = first.optional("our_trade_ref"),
            counterpartyTradeRef = first.optional("counterparty_trade_ref"),
            tradeDate = LocalDate.parse(first.required("trade_date")),
            productFamily = ProductFamily.valueOf(first.required("product_family").uppercase()),
            masterAgreement = first.optional("master_agreement"),
            legs = listOf(
                LegTerms(
                    seller = PartyTerms(first.optional("seller_name"), first.optional("seller_lei")),
                    buyer = PartyTerms(first.optional("buyer_name"), first.optional("buyer_lei")),
                    product = first.required("product"),
                    quantity = quantity,
                    unit = first.required("unit"),
                    price = price,
                    currency = first.required("currency"),
                    amount = first.optional("amount")?.let { lines.sumOf { l -> l.decimal("amount") } }
                        ?: (quantity * price).setScale(2, RoundingMode.HALF_EVEN),
                    deliveries = lines.map {
                        DeliveryTerms(LocalDate.parse(it.required("delivery_date")), it.decimal("quantity"), it.optional("payment_date")?.let(LocalDate::parse))
                    },
                    sellerAccount = first.optional("seller_account"),
                    buyerAccount = first.optional("buyer_account"),
                    attributes = first.filterKeys { it.startsWith("attr_") }.filterValues { it.isNotBlank() }.mapKeys { it.key.removePrefix("attr_") },
                ),
            ),
        )
    }

    private fun pdfModel(trade: TradeView, terms: ConfirmationTerms): Map<String, Any?> = mapOf(
        "title" to "Trade confirmation ${trade.tradeNumber}",
        "tradeNumber" to trade.tradeNumber,
        "version" to trade.version,
        "tradeDate" to trade.tradeDate.toString(),
        "productFamily" to trade.productFamily.name,
        "masterAgreement" to (terms.masterAgreement ?: "—"),
        "counterpartyTradeRef" to (terms.counterpartyTradeRef ?: "—"),
        "issuedAt" to clock.instant().toString(),
        "legs" to terms.legs.zip(trade.legs).map { (leg, tradeLeg) ->
            mapOf(
                "legNo" to leg.legNo,
                "seller" to party(leg.seller),
                "buyer" to party(leg.buyer),
                "product" to leg.product,
                "quantity" to leg.quantity.toPlainString(),
                "unit" to leg.unit,
                "price" to leg.price.toPlainString(),
                "currency" to leg.currency,
                "amount" to leg.amount.toPlainString(),
                "sellerAccount" to (leg.sellerAccount ?: "—"),
                "buyerAccount" to (leg.buyerAccount ?: "—"),
                "criteria" to (tradeLeg.criteriaSummary ?: ""),
                "attributes" to leg.attributes.entries.sortedBy { it.key }.map { mapOf("key" to it.key, "value" to it.value) },
                "deliveries" to leg.deliveries.map {
                    mapOf("date" to it.deliveryDate.toString(), "quantity" to it.quantity.toPlainString(), "paymentDate" to (it.paymentDate?.toString() ?: "—"))
                },
            )
        },
    )

    private fun party(p: PartyTerms): String = listOfNotNull(p.name, p.lei?.let { "LEI $it" }).joinToString(", ")

    private fun Map<String, String>.required(column: String): String =
        this[column]?.takeIf { it.isNotBlank() } ?: throw ValidationException("Column $column is required")

    private fun Map<String, String>.optional(column: String): String? = this[column]?.takeIf { it.isNotBlank() }

    private fun Map<String, String>.decimal(column: String): BigDecimal = try {
        BigDecimal(required(column))
    } catch (_: NumberFormatException) {
        throw ValidationException("Column $column is not a number: ${this[column]}")
    }

    private companion object {
        val ISSUABLE = setOf(TradeStatus.APPROVED, TradeStatus.CONFIRMED, TradeStatus.PARTIALLY_DELIVERED, TradeStatus.DELIVERED, TradeStatus.SETTLED)
        val OPEN_THEIRS = setOf(ConfirmationStatus.RECEIVED, ConfirmationStatus.MATCHED, ConfirmationStatus.MISMATCHED)
    }
}
