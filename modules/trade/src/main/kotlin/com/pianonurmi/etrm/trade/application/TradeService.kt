package com.pianonurmi.etrm.trade.application

import com.pianonurmi.etrm.marketdata.api.FxRates
import com.pianonurmi.etrm.platform.errors.DomainException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.StaleVersionException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.money.QuantityUnit
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.product.api.EnergyCarrier
import com.pianonurmi.etrm.product.api.FuturesContractQuery
import com.pianonurmi.etrm.product.api.InstrumentType
import com.pianonurmi.etrm.product.api.ProductBucketQuery
import com.pianonurmi.etrm.product.api.ProductCatalogueQuery
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.product.api.criteria.CriteriaService
import com.pianonurmi.etrm.referencedata.api.CounterpartyType
import com.pianonurmi.etrm.referencedata.api.HolderType
import com.pianonurmi.etrm.referencedata.api.LegalEntityView
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.referencedata.api.RegistryAccountView
import com.pianonurmi.etrm.trade.api.AmendTrade
import com.pianonurmi.etrm.trade.api.ApprovalFinding
import com.pianonurmi.etrm.trade.api.CaptureLeg
import com.pianonurmi.etrm.trade.api.CaptureTrade
import com.pianonurmi.etrm.trade.api.ClearingStatus
import com.pianonurmi.etrm.trade.api.ConfirmTrade
import com.pianonurmi.etrm.trade.api.DeliveryObligationChanged
import com.pianonurmi.etrm.trade.api.DeliveryObligationClosed
import com.pianonurmi.etrm.trade.api.DeliveryObligationOpened
import com.pianonurmi.etrm.trade.api.DeliveryObligationView
import com.pianonurmi.etrm.trade.api.DeliveryRecorded
import com.pianonurmi.etrm.trade.api.DeliveryScheduleView
import com.pianonurmi.etrm.trade.api.DeliveryStatus
import com.pianonurmi.etrm.trade.api.OptionExpired
import com.pianonurmi.etrm.trade.api.OptionExpiryView
import com.pianonurmi.etrm.trade.api.PriceType
import com.pianonurmi.etrm.trade.api.RecordDelivery
import com.pianonurmi.etrm.trade.api.RegulatoryTerms
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeAmended
import com.pianonurmi.etrm.trade.api.TradeApprovalCheck
import com.pianonurmi.etrm.trade.api.TradeApproved
import com.pianonurmi.etrm.trade.api.TradeCancelled
import com.pianonurmi.etrm.trade.api.TradeCaptured
import com.pianonurmi.etrm.trade.api.TradeCommand
import com.pianonurmi.etrm.trade.api.TradeConfirmed
import com.pianonurmi.etrm.trade.api.TradeFilter
import com.pianonurmi.etrm.trade.api.TradeLegView
import com.pianonurmi.etrm.trade.api.TradePage
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.api.TradeSearch
import com.pianonurmi.etrm.trade.api.TradeSettled
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeVersionView
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.trade.api.UtiSource
import com.pianonurmi.etrm.trade.domain.RegulatoryChecks
import com.pianonurmi.etrm.trade.domain.TradeAggregate
import com.pianonurmi.etrm.trade.domain.TradeRepository
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.util.Currency
import java.util.UUID

@Service
@Transactional
class TradeService(
    private val repository: TradeRepository,
    private val referenceData: ReferenceDataQuery,
    private val criteria: CriteriaService,
    private val buckets: ProductBucketQuery,
    private val catalogue: ProductCatalogueQuery,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
    /** Trades with notional at or above this amount (in [thresholdCurrency]) need four-eyes approval; 0 means every trade. */
    @Value("\${etrm.trade.approval-threshold:0}") private val approvalThreshold: BigDecimal,
    private val fxRates: FxRates,
    @Value("\${etrm.trade.approval-threshold-currency:EUR}") private val thresholdCurrency: String,
    private val futures: FuturesContractQuery,
    /** Pre-trade checks of other modules, resolved when used (they read trades themselves). */
    private val checks: ObjectProvider<TradeApprovalCheck>,
) : TradeCommand, TradeQuery {
    // ----------------------------------------------------------------------------------------------------------
    // Capture and amendment
    // ----------------------------------------------------------------------------------------------------------

    private fun authorise(legalEntityId: UUID, vararg roles: Role) {
        val actor = actorProvider.current()
        actor.requireRole(*roles)
        actor.requireEntity(legalEntityId)
    }

    override fun requireTradeOperator(tradeId: UUID) {
        authorise(requireTrade(tradeId).legalEntityId, Role.MIDDLE_OFFICE, Role.BACK_OFFICE)
    }

    override fun capture(command: CaptureTrade): TradeView {
        authorise(command.legalEntityId, Role.TRADER, Role.MIDDLE_OFFICE)
        val actor = actorProvider.current().userId
        val counterparty = referenceData.requireCounterparty(command.counterpartyId)
        val trade = build(command, actor).let { it.copy(approvalFindings = runChecks(it)) }
        repository.insert(trade, actor, command.reason ?: "captured")
        val submitted = TradeAggregate(trade).submitForApproval(requiresApproval(trade))
        repository.saveVersion(submitted, actor, "submitted")
        events.publishEvent(TradeCaptured(submitted))
        openObligationsIfDue(submitted)
        if (counterparty.counterpartyType == CounterpartyType.INTERCOMPANY) captureMirror(command, submitted, counterparty.linkedLegalEntityId!!, actor)
        return submitted
    }

    private fun captureMirror(command: CaptureTrade, original: TradeView, mirrorEntityId: UUID, actor: String) {
        val mirrorBook = command.mirrorBookId ?: throw ValidationException("Intercompany trades require mirrorBookId in the counterparty legal entity")
        if (referenceData.requireBook(
                mirrorBook,
            ).legalEntityId != mirrorEntityId
        ) {
            throw ValidationException("mirrorBookId must belong to legal entity $mirrorEntityId")
        }
        val mirrorCounterparty = referenceData.counterparties().firstOrNull { it.linkedLegalEntityId == command.legalEntityId }
            ?: throw ValidationException("No intercompany counterparty record represents legal entity ${command.legalEntityId}")
        val mirrorCommand = command.copy(
            legalEntityId = mirrorEntityId,
            bookId = mirrorBook,
            counterpartyId = mirrorCounterparty.id,
            // The mirror delivers to (or receives from) the original entity's own account, from the account the original
            // names for its counterparty (held by the mirror's entity), when it names a registered one.
            legs = command.legs.mapIndexed { index, leg ->
                leg.copy(
                    side = leg.side.opposite(),
                    deliveryAccountId = original.legs[index].counterpartyAccountId,
                    counterpartyAccountId = leg.deliveryAccountId,
                    counterpartyAccountRef = null,
                )
            },
            mirrorBookId = null,
        )
        // One transaction, one UTI: the mirror reports the original's identifier.
        val mirror = build(mirrorCommand, actor).let {
            it.copy(intercompanyLinkId = original.id, regulatory = it.regulatory.copy(uti = original.regulatory.uti, utiSource = original.regulatory.utiSource))
        }
        repository.insert(mirror, actor, "intercompany mirror of ${original.tradeNumber}")
        val submitted = TradeAggregate(mirror).submitForApproval(requiresApproval(mirror))
        repository.saveVersion(submitted, actor, "submitted")
        val linked = original.copy(intercompanyLinkId = mirror.id, version = original.version + 1)
        repository.saveVersion(linked, actor, "linked to ${mirror.tradeNumber}")
        events.publishEvent(TradeCaptured(submitted))
        openObligationsIfDue(submitted)
    }

    private fun build(command: CaptureTrade, actor: String): TradeView {
        val entity = referenceData.requireLegalEntity(command.legalEntityId)
        val book = referenceData.requireBook(command.bookId)
        if (book.legalEntityId != entity.id) throw ValidationException("Book ${book.code} does not belong to legal entity ${entity.code}")
        referenceData.requireCounterparty(command.counterpartyId)
        command.brokerId?.let(referenceData::requireCounterparty)
        if (command.legs.isEmpty()) throw ValidationException("A trade needs at least one leg")
        val id = Ids.newId()
        val trade = TradeView(
            id = id,
            tradeNumber = repository.nextTradeNumber(command.tradeDate.year),
            legalEntityId = entity.id,
            bookId = book.id,
            counterpartyId = command.counterpartyId,
            productFamily = command.productFamily,
            instrumentType = command.instrumentType,
            tradeDate = command.tradeDate,
            trader = command.trader ?: actor,
            brokerId = command.brokerId,
            masterAgreementRef = command.masterAgreementRef,
            externalRef = command.externalRef,
            status = TradeStatus.DRAFT,
            version = 1,
            intercompanyLinkId = null,
            approvedBy = null,
            approvedAt = null,
            confirmedAt = null,
            confirmationRef = null,
            createdBy = actor,
            legs = optionLegsChecked(command.instrumentType, command.legs).mapIndexed { index, leg ->
                buildLeg(command.productFamily, command.legalEntityId, command.counterpartyId, index + 1, leg)
            },
            obligationsOpenAt = entity.obligationsOpenAt,
            regulatory = command.regulatory ?: RegulatoryTerms(),
        )
        return withRegulatoryTerms(trade, entity, keptUti = null)
    }

    /**
     * Checks the regulatory identifiers, completes futures from their contract (venue, clearing), and gives a
     * reportable trade our own UTI when no other party generates one. A UTI, once assigned, never changes.
     */
    private fun withRegulatoryTerms(trade: TradeView, entity: LegalEntityView, keptUti: String?): TradeView {
        var terms = trade.regulatory
        if (keptUti != null) {
            if (terms.uti != null && terms.uti != keptUti) throw ValidationException("The UTI of ${trade.tradeNumber} is $keptUti and cannot change")
            terms = terms.copy(uti = keptUti)
        }
        terms.venueMic?.let { if (!RegulatoryChecks.mic(it)) throw ValidationException("Venue $it is not an ISO 10383 MIC (use XOFF off venue)") }
        terms.uti?.let { if (!RegulatoryChecks.uti(it)) throw ValidationException("UTI $it is not an ISO 23897 identifier starting with a valid LEI") }
        terms.ccpCounterpartyId?.let {
            if (referenceData.requireCounterparty(
                    it,
                ).counterpartyType != CounterpartyType.CLEARING_HOUSE
            ) {
                throw ValidationException("The CCP must be a clearing house")
            }
        }
        val options = trade.legs.count { it.option != null }
        if (trade.instrumentType == InstrumentType.OPTION && options != trade.legs.size) {
            throw ValidationException(
                "Every leg of an option names its option terms",
            )
        }
        if (trade.instrumentType != InstrumentType.OPTION && options > 0) throw ValidationException("Only options carry option terms")
        if (trade.instrumentType == InstrumentType.FUTURE) terms = futuresTerms(trade, terms)
        if (trade.instrumentType == InstrumentType.OPTION) terms = optionTerms(trade, terms)
        if (terms.clearingStatus == ClearingStatus.CLEARED && terms.ccpCounterpartyId == null) throw ValidationException("A cleared trade names its CCP")
        val lei = entity.lei
        if (lei != null && generatesOwnUti(trade, terms)) {
            terms = terms.copy(uti = (lei + "ETRM" + trade.tradeNumber.filter(Char::isLetterOrDigit)).uppercase().take(UTI_LENGTH), utiSource = UtiSource.OWN)
        }
        return trade.copy(regulatory = terms)
    }

    private fun generatesOwnUti(trade: TradeView, terms: RegulatoryTerms): Boolean =
        terms.uti == null && trade.emirReportable && (terms.utiSource ?: UtiSource.OWN) == UtiSource.OWN

    /** An exchange future delivers once, on the contract's date, in whole lots, cleared, on the contract's venue. */
    private fun futuresTerms(trade: TradeView, terms: RegulatoryTerms): RegulatoryTerms {
        val counterparty = referenceData.requireCounterparty(trade.counterpartyId)
        if (counterparty.counterpartyType != CounterpartyType.EXCHANGE && counterparty.counterpartyType != CounterpartyType.CLEARING_HOUSE) {
            throw ValidationException("A future is traded with an exchange or clearing house, not ${counterparty.code}")
        }
        val venues = trade.legs.map { leg ->
            val code = leg.futuresContractCode ?: throw ValidationException("Leg ${leg.legNo}: a future names its futuresContractCode")
            val contract = futures.futuresContract(code)?.takeIf { it.active } ?: throw NotFoundException("FuturesContract", code)
            if (leg.allowanceProductCode != contract.allowanceProductCode) {
                throw ValidationException(
                    "Leg ${leg.legNo}: contract $code is on ${contract.allowanceProductCode}",
                )
            }
            if (leg.deliveries.size != 1 || leg.deliveries.single().deliveryDate != contract.deliveryDate) {
                throw ValidationException("Leg ${leg.legNo}: a future delivers once, on ${contract.deliveryDate}")
            }
            if (leg.quantity.remainder(contract.contractSize).signum() != 0) {
                throw ValidationException("Leg ${leg.legNo}: quantity must be whole lots of ${contract.contractSize.stripTrailingZeros().toPlainString()}")
            }
            if (leg.priceCurrency.currencyCode != contract.currency) {
                throw ValidationException(
                    "Leg ${leg.legNo}: contract $code trades in ${contract.currency}",
                )
            }
            if (trade.tradeDate.isAfter(contract.lastTradingDay)) throw ValidationException("Contract $code stopped trading on ${contract.lastTradingDay}")
            contract.venueMic
        }.toSet()
        val venue = venues.singleOrNull() ?: throw ValidationException("All legs of a future trade on one venue")
        if (terms.venueMic != null && terms.venueMic != venue) throw ValidationException("The contracts trade on $venue, not ${terms.venueMic}")
        val ccp = terms.ccpCounterpartyId ?: trade.counterpartyId.takeIf { counterparty.counterpartyType == CounterpartyType.CLEARING_HOUSE }
        return terms.copy(venueMic = venue, clearingStatus = ClearingStatus.CLEARED, ccpCounterpartyId = ccp)
    }

    /**
     * An exchange option (ADR 0047): on an active future of the leg's product and currency, in whole lots, expiring
     * between the trade date and the future's last trading day, cleared on the future's venue.
     */
    private fun optionTerms(trade: TradeView, terms: RegulatoryTerms): RegulatoryTerms {
        val counterparty = referenceData.requireCounterparty(trade.counterpartyId)
        if (counterparty.counterpartyType != CounterpartyType.EXCHANGE && counterparty.counterpartyType != CounterpartyType.CLEARING_HOUSE) {
            throw ValidationException("An option is traded with an exchange or clearing house, not ${counterparty.code}")
        }
        if (trade.productFamily != ProductFamily.EMISSION_ALLOWANCE) throw ValidationException("Options are on allowance futures")
        val venues = trade.legs.map { leg ->
            val option = leg.option!!
            val code = option.underlyingContractCode
            val contract = futures.futuresContract(code)?.takeIf { it.active } ?: throw NotFoundException("FuturesContract", code)
            if (leg.allowanceProductCode != contract.allowanceProductCode) {
                throw ValidationException("Leg ${leg.legNo}: contract $code is on ${contract.allowanceProductCode}")
            }
            if (leg.priceType != PriceType.FIXED) throw ValidationException("Leg ${leg.legNo}: an option's premium is a fixed price")
            if (leg.quantity.remainder(contract.contractSize).signum() != 0) {
                throw ValidationException("Leg ${leg.legNo}: quantity must be whole lots of ${contract.contractSize.stripTrailingZeros().toPlainString()}")
            }
            if (leg.priceCurrency.currencyCode != contract.currency) {
                throw ValidationException(
                    "Leg ${leg.legNo}: contract $code trades in ${contract.currency}",
                )
            }
            if (option.expiryDate.isBefore(trade.tradeDate)) throw ValidationException("Leg ${leg.legNo}: the option expired on ${option.expiryDate}")
            if (option.expiryDate.isAfter(contract.lastTradingDay)) {
                throw ValidationException("Leg ${leg.legNo}: an option on $code expires by its last trading day, ${contract.lastTradingDay}")
            }
            contract.venueMic
        }.toSet()
        val venue = venues.singleOrNull() ?: throw ValidationException("All legs of an option trade on one venue")
        if (terms.venueMic != null && terms.venueMic != venue) throw ValidationException("The contracts trade on $venue, not ${terms.venueMic}")
        val ccp = terms.ccpCounterpartyId ?: trade.counterpartyId.takeIf { counterparty.counterpartyType == CounterpartyType.CLEARING_HOUSE }
        return terms.copy(venueMic = venue, clearingStatus = ClearingStatus.CLEARED, ccpCounterpartyId = ccp)
    }

    /** Options name their terms on every leg, and only options do (ADR 0047). */
    private fun optionLegsChecked(instrument: InstrumentType, legs: List<CaptureLeg>): List<CaptureLeg> {
        val options = legs.count { it.option != null }
        if (instrument == InstrumentType.OPTION && options != legs.size) throw ValidationException("Every leg of an option names its option terms")
        if (instrument != InstrumentType.OPTION && options > 0) throw ValidationException("Only options carry option terms")
        return legs
    }

    /** A delivery schedule that adds up to the leg, or none for an option, whose exercise creates the future. */
    private fun checkSchedule(legNo: Int, leg: CaptureLeg) {
        val option = leg.option
        if (option != null) {
            if (leg.deliveries.isNotEmpty()) throw ValidationException("Leg $legNo: an option has no delivery schedule; exercise creates the future")
            if (option.strike.signum() <= 0) throw ValidationException("Leg $legNo: the strike must be positive")
            if (leg.futuresContractCode != null) throw ValidationException("Leg $legNo: an option names its underlying, not a futures contract")
            return
        }
        if (leg.deliveries.isEmpty()) throw ValidationException("Leg $legNo needs a delivery schedule")
        val scheduled = leg.deliveries.sumOf { it.quantity }
        if (scheduled.compareTo(leg.quantity) != 0) throw ValidationException("Leg $legNo deliveries total $scheduled but leg quantity is ${leg.quantity}")
        leg.deliveries.forEach { d ->
            if (d.quantity.signum() <= 0) throw ValidationException("Leg $legNo delivery quantities must be positive")
            if (d.deliveryDate !in d.deliveryWindow) {
                throw ValidationException("Leg $legNo delivery date ${d.deliveryDate} must lie within ${d.deliveryWindow}")
            }
        }
    }

    private fun buildLeg(family: ProductFamily, legalEntityId: UUID, counterpartyId: UUID, legNo: Int, leg: CaptureLeg): TradeLegView {
        if (leg.quantity.signum() <= 0) throw ValidationException("Leg $legNo quantity must be positive")
        // An index leg's price is its spread over the index, which may be a discount; a fixed price is never negative.
        if (leg.priceType == PriceType.FIXED && leg.price.signum() < 0) throw ValidationException("Leg $legNo price must not be negative")
        checkSchedule(legNo, leg)
        leg.deliveryAccountId?.let { accountId ->
            val account = referenceData.requireRegistryAccount(accountId)
            if (account.holderId != legalEntityId) throw ValidationException("Delivery account ${account.accountNumber} is not held by the trading entity")
        }
        val criteriaRef = when (family) {
            ProductFamily.GUARANTEE_OF_ORIGIN -> {
                val criterion = leg.criterion ?: throw ValidationException("Leg $legNo: GoO legs require delivery criteria")
                if (leg.unit != QuantityUnit.MWH) throw ValidationException("Leg $legNo: GoO quantities are in MWh")
                if (leg.quantity.stripTrailingZeros().scale() > 0) throw ValidationException("Leg $legNo: GoO quantities are whole MWh")
                criteria.store(criterion, "trade")
            }
            ProductFamily.EMISSION_ALLOWANCE -> {
                val code = leg.allowanceProductCode ?: throw ValidationException("Leg $legNo: allowance legs require allowanceProductCode")
                catalogue.allowanceProduct(code) ?: throw NotFoundException("AllowanceProduct", code)
                if (leg.unit != QuantityUnit.TCO2E) throw ValidationException("Leg $legNo: allowance quantities are in tCO2e")
                null
            }
        }
        val bucket = criteriaRef?.let { ref ->
            val firstDelivery = leg.deliveries.minOf { it.deliveryDate }
            buckets.bucketFor(ref.id, leg.criterion!!, criteria.contextFor(firstDelivery))
        }
        val counterpartyAccount = counterpartyAccount(counterpartyId, legNo, leg)
        return TradeLegView(
            id = Ids.newId(),
            legNo = legNo,
            side = leg.side,
            energyCarrier = leg.energyCarrier ?: EnergyCarrier.ELECTRICITY.takeIf { family == ProductFamily.GUARANTEE_OF_ORIGIN },
            allowanceProductCode = leg.allowanceProductCode?.uppercase(),
            criteriaId = criteriaRef?.id,
            criterion = leg.criterion,
            criteriaSummary = criteriaRef?.summary,
            bucketCode = bucket?.bucket?.code,
            bucketMatch = bucket?.kind,
            quantity = leg.quantity,
            unit = leg.unit,
            price = leg.price,
            priceCurrency = Currency.getInstance(leg.priceCurrency.uppercase()),
            priceType = leg.priceType,
            priceIndex = leg.priceIndex,
            deliveryAccountId = leg.deliveryAccountId,
            counterpartyAccountRef = counterpartyAccount?.let { "${it.registryCode}:${it.accountNumber}" } ?: leg.counterpartyAccountRef,
            counterpartyAccountId = counterpartyAccount?.id,
            deliveries = leg.deliveries.sortedBy { it.deliveryDate }.mapIndexed { i, d ->
                DeliveryScheduleView(Ids.newId(), i + 1, d.deliveryWindow, d.deliveryDate, d.quantity, BigDecimal.ZERO, d.paymentDate, DeliveryStatus.PENDING)
            },
            futuresContractCode = leg.futuresContractCode?.uppercase(),
            upi = checkedUpi(legNo, leg.upi),
            isin = checkedIsin(legNo, leg.isin),
            option = leg.option?.let { it.copy(underlyingContractCode = it.underlyingContractCode.trim().uppercase()) },
        )
    }

    private fun checkedUpi(legNo: Int, upi: String?): String? =
        upi?.uppercase()?.also { if (!RegulatoryChecks.upi(it)) throw ValidationException("Leg $legNo: UPI $it is not 12 characters") }

    private fun checkedIsin(legNo: Int, isin: String?): String? =
        isin?.uppercase()?.also { if (!RegulatoryChecks.isin(it)) throw ValidationException("Leg $legNo: ISIN $it has a wrong format or check digit") }

    /**
     * The counterparty's registry account for a leg: the explicit account (which must belong to the counterparty, or
     * for intercompany counterparties to the represented legal entity, and be active), or the account a textual
     * reference resolves to. Free-text references that match no registered account stay text.
     */
    private fun counterpartyAccount(counterpartyId: UUID, legNo: Int, leg: CaptureLeg): RegistryAccountView? {
        val counterparty = referenceData.requireCounterparty(counterpartyId)

        fun belongs(account: RegistryAccountView) = when (account.holderType) {
            HolderType.COUNTERPARTY -> account.holderId == counterparty.id
            HolderType.LEGAL_ENTITY -> account.holderId == counterparty.linkedLegalEntityId
        }
        leg.counterpartyAccountId?.let { id ->
            val account = referenceData.requireRegistryAccount(id)
            if (!belongs(
                    account,
                )
            ) {
                throw ValidationException("Leg $legNo: account ${account.accountNumber} does not belong to counterparty ${counterparty.code}")
            }
            if (!account.active) throw ValidationException("Leg $legNo: counterparty account ${account.accountNumber} is inactive")
            return account
        }
        return leg.counterpartyAccountRef?.let(referenceData::registryAccountByRef)?.takeIf { belongs(it) && it.active }
    }

    override fun amend(tradeId: UUID, command: AmendTrade): TradeView {
        val actor = actorProvider.current().userId
        val current = locked(tradeId)
        authorise(current.legalEntityId, Role.TRADER, Role.MIDDLE_OFFICE)
        if (current.version != command.expectedVersion) throw StaleVersionException("Trade", tradeId, command.expectedVersion, current.version)
        val next = TradeAggregate(current).amend(amendedState(current, command, runChecks = true))
        // An intercompany trade and its mirror change together or not at all: the mirror is worked out first, so a
        // refusal leaves both as they were.
        val mirror = current.intercompanyLinkId?.let(::locked)
        val mirrorNext = mirror?.let { amendedMirror(current, next, it, command) }
        saveAmended(current, next, actor, command.reason)
        if (mirror != null && mirrorNext != null) saveAmended(mirror, mirrorNext, actor, "${command.reason} (follows ${current.tradeNumber})")
        return next
    }

    /** The trade as the amendment would leave it, before the lifecycle moves it back to approval. */
    private fun amendedState(current: TradeView, command: AmendTrade, runChecks: Boolean): TradeView {
        val legs = command.legs?.let { optionLegsChecked(current.instrumentType, it) }?.mapIndexed {
            index,
            leg,
            ->
            buildLeg(current.productFamily, current.legalEntityId, current.counterpartyId, index + 1, leg)
        } ?: current.legs
        return current.copy(
            tradeDate = command.tradeDate ?: current.tradeDate,
            brokerId = command.brokerId ?: current.brokerId,
            masterAgreementRef = command.masterAgreementRef ?: current.masterAgreementRef,
            externalRef = command.externalRef ?: current.externalRef,
            legs = legs,
            regulatory = command.regulatory ?: current.regulatory,
        ).let { withRegulatoryTerms(it, referenceData.requireLegalEntity(current.legalEntityId), keptUti = current.regulatory.uti) }
            .let { if (runChecks) it.copy(approvalFindings = runChecks(it)) else it }
    }

    private fun saveAmended(current: TradeView, next: TradeView, actor: String, reason: String) {
        val wasFirm = current.obligationsOpen
        save(next, actor, reason)
        events.publishEvent(TradeAmended(next, current.version))
        if (wasFirm) obligationsOf(current).forEach { events.publishEvent(DeliveryObligationClosed(it, "amended")) }
    }

    /**
     * The mirror of an intercompany trade after the same amendment, with the sides turned round. The source's delivery
     * account is the mirror's counterparty account, and the source's counterparty account (held by the mirror's entity)
     * the mirror's delivery account: an account the amendment changed on [amended] follows to the mirror, one it left as
     * it was keeps the mirror's own value. A leg added by the amendment takes the source's accounts, as at capture. It is
     * worked out like any amendment and, like the mirror at capture, without pre-trade checks. When the mirror cannot take
     * the change (it has deliveries, an option leg decided, or is in a state that forbids it), the one-sided change is
     * refused.
     */
    private fun amendedMirror(source: TradeView, amended: TradeView, mirror: TradeView, command: AmendTrade): TradeView = try {
        val mirrorCommand = command.copy(
            expectedVersion = mirror.version,
            legs = command.legs?.mapIndexed { index, leg ->
                val own = mirror.legs.getOrNull(index)
                val before = source.legs.getOrNull(index)
                val after = amended.legs[index]
                val deliveryKept = own != null && before != null && after.deliveryAccountId == before.deliveryAccountId
                val counterpartyKept = own != null && before != null && after.counterpartyAccountId == before.counterpartyAccountId
                leg.copy(
                    side = leg.side.opposite(),
                    deliveryAccountId = if (counterpartyKept) own.deliveryAccountId else after.counterpartyAccountId,
                    counterpartyAccountId = if (deliveryKept) own.counterpartyAccountId else after.deliveryAccountId,
                    counterpartyAccountRef = if (deliveryKept) own.counterpartyAccountRef else null,
                )
            },
        )
        TradeAggregate(mirror).amend(amendedState(mirror, mirrorCommand, runChecks = false))
    } catch (e: DomainException) {
        throw ValidationException(
            "Trade ${source.tradeNumber} cannot be amended on its own: its intercompany mirror ${mirror.tradeNumber} refuses the change (${e.message})",
        )
    }

    // ----------------------------------------------------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------------------------------------------------

    override fun approve(tradeId: UUID): TradeView {
        val actor = actorProvider.current().userId
        val current = locked(tradeId)
        authorise(current.legalEntityId, Role.MIDDLE_OFFICE, Role.RISK)
        if (current.requiresRiskApproval) actorProvider.current().requireRole(Role.RISK)
        val next = TradeAggregate(current).approve(actor, clock.instant())
        save(next, actor, "approved")
        events.publishEvent(TradeApproved(next))
        openObligationsIfDue(next)
        // The other side of an intercompany trade may have been confirmed while this one waited for approval: the
        // confirmation reached nothing then, so it is taken over now.
        val linked = next.intercompanyLinkId?.let(::requireTrade)?.takeIf { it.status == TradeStatus.CONFIRMED && it.confirmationRef != null }
        if (linked != null) return confirmAs(next.id, ConfirmTrade(linked.confirmationRef!!, "INTERCOMPANY", linked.confirmedAt), actor)
        return next
    }

    override fun reject(tradeId: UUID, reason: String): TradeView {
        val current = locked(tradeId)
        authorise(current.legalEntityId, Role.MIDDLE_OFFICE, Role.RISK)
        val next = TradeAggregate(current).reject()
        save(next, actorProvider.current().userId, "rejected: $reason")
        return next
    }

    override fun confirm(tradeId: UUID, command: ConfirmTrade): TradeView {
        authorise(requireTrade(tradeId).legalEntityId, Role.MIDDLE_OFFICE, Role.BACK_OFFICE)
        return confirmAs(tradeId, command, actorProvider.current().userId)
    }

    /** Confirmation on behalf of [actor], e.g. `system` when the counterparty's confirmation matched ours. */
    fun confirmAs(tradeId: UUID, command: ConfirmTrade, actor: String): TradeView {
        val current = locked(tradeId)
        val next = TradeAggregate(current).confirm(command.confirmationRef, command.confirmedAt ?: clock.instant())
        save(next, actor, "confirmed via ${command.method}")
        events.publishEvent(TradeConfirmed(next))
        if (!current.obligationsOpen) obligationsOf(next).forEach { events.publishEvent(DeliveryObligationOpened(it)) }
        next.intercompanyLinkId?.let { linkedId ->
            val linked = locked(linkedId)
            if (linked.status == TradeStatus.APPROVED) confirmAs(linkedId, command, actor)
        }
        return next
    }

    override fun cancel(tradeId: UUID, reason: String): TradeView {
        val current = locked(tradeId)
        authorise(current.legalEntityId, Role.TRADER, Role.MIDDLE_OFFICE)
        val next = TradeAggregate(current).cancel()
        // The mirror of an intercompany trade is cancelled with it, or the cancellation is refused: one entity must not
        // keep the obligation, exposure and position the other has cancelled.
        val mirror = current.intercompanyLinkId?.let(::locked)?.takeIf { it.status != TradeStatus.CANCELLED }
        val mirrorNext = mirror?.let { cancelledMirror(current, it) }
        val actor = actorProvider.current().userId
        saveCancelled(current, next, actor, reason)
        if (mirror != null && mirrorNext != null) saveCancelled(mirror, mirrorNext, actor, "$reason (follows ${current.tradeNumber})")
        return next
    }

    private fun cancelledMirror(source: TradeView, mirror: TradeView): TradeView = try {
        TradeAggregate(mirror).cancel()
    } catch (e: DomainException) {
        throw ValidationException(
            "Trade ${source.tradeNumber} cannot be cancelled on its own: its intercompany mirror ${mirror.tradeNumber} refuses it (${e.message})",
        )
    }

    private fun saveCancelled(current: TradeView, next: TradeView, actor: String, reason: String) {
        save(next, actor, "cancelled: $reason")
        events.publishEvent(TradeCancelled(next, reason))
        if (current.obligationsOpen) {
            obligationsOf(current).filter {
                it.status != DeliveryStatus.DELIVERED
            }.forEach { events.publishEvent(DeliveryObligationClosed(it, "cancelled")) }
        }
    }

    override fun recordDelivery(tradeId: UUID, command: RecordDelivery): TradeView {
        val actor = actorProvider.current().userId
        val current = locked(tradeId)
        if (command.idempotencyKey != null && repository.deliveryRecorded(command.idempotencyKey)) return current
        val (next, schedule) = TradeAggregate(current).recordDelivery(command.scheduleId, command.quantity)
        save(next, actor, "delivery ${command.quantity} on ${command.deliveredOn}")
        val deliveryId = repository.appendDeliveryRecord(
            command.scheduleId,
            command.quantity,
            command.deliveredOn,
            command.reference,
            command.idempotencyKey,
            actor,
        )
        val obligation = obligationsOf(next).first { it.scheduleId == schedule.id }
        events.publishEvent(DeliveryRecorded(next, deliveryId, obligation, command.quantity, command.deliveredOn, command.reference))
        if (schedule.status == DeliveryStatus.DELIVERED) {
            events.publishEvent(DeliveryObligationClosed(obligation, "delivered"))
        } else {
            events.publishEvent(DeliveryObligationChanged(obligation))
        }
        return next
    }

    /**
     * Captures, approves and confirms a trade the system makes on another's behalf: the future an option is exercised
     * into (ADR 0047). No pre-trade checks or approval: the exercise is not a decision.
     */
    fun captureConfirmed(command: CaptureTrade, reason: String, confirmationRef: String): TradeView {
        val trade = build(command, SYSTEM_USER)
        repository.insert(trade, SYSTEM_USER, reason)
        val approved = TradeAggregate(trade).submitForApproval(requiresApproval = false)
        repository.saveVersion(approved, SYSTEM_USER, "approved: $reason")
        events.publishEvent(TradeCaptured(approved))
        openObligationsIfDue(approved)
        return confirmAs(approved.id, ConfirmTrade(confirmationRef, "SYSTEM", clock.instant()), SYSTEM_USER)
    }

    /** Records an option leg's outcome at expiry; the trade closes once every leg is decided. */
    fun recordExpiry(tradeId: UUID, legNo: Int, expiry: OptionExpiryView, future: TradeView?): TradeView {
        val next = TradeAggregate(locked(tradeId)).expire(legNo, expiry)
        save(next, SYSTEM_USER, "leg $legNo ${expiry.outcome.name.lowercase()} at ${expiry.settlementPrice.toPlainString()}")
        events.publishEvent(OptionExpired(next, legNo, expiry, future))
        return next
    }

    override fun markSettled(tradeId: UUID): TradeView {
        val next = TradeAggregate(locked(tradeId)).markSettled()
        save(next, actorProvider.current().userId, "settled")
        events.publishEvent(TradeSettled(next))
        return next
    }

    // ----------------------------------------------------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    override fun trade(id: UUID): TradeView? = repository.find(id)

    @Transactional(readOnly = true)
    override fun requireTrade(id: UUID): TradeView = repository.find(id) ?: throw NotFoundException("Trade", id)

    @Transactional(readOnly = true)
    override fun byNumber(tradeNumber: String): TradeView? = repository.byNumber(tradeNumber)

    @Transactional(readOnly = true)
    override fun trades(filter: TradeFilter): List<TradeView> = repository.list(filter)

    @Transactional(readOnly = true)
    override fun visibleTrades(filter: TradeFilter): List<TradeView> {
        val actor = actorProvider.current()
        filter.legalEntityId?.let { actor.requireEntity(it) }
        return repository.list(filter.copy(legalEntityIds = actor.entityFilter))
    }

    @Transactional(readOnly = true)
    override fun searchTrades(search: TradeSearch): TradePage {
        val actor = actorProvider.current()
        search.legalEntityId?.let { actor.requireEntity(it) }
        return repository.search(search.copy(legalEntityIds = actor.entityFilter))
    }

    @Transactional(readOnly = true)
    override fun requireVisibleTrade(id: UUID): TradeView = requireTrade(id).also { actorProvider.current().requireEntity(it.legalEntityId) }

    @Transactional(readOnly = true)
    override fun versions(tradeId: UUID): List<TradeVersionView> = repository.versions(tradeId)

    @Transactional(readOnly = true)
    override fun openObligations(legalEntityId: UUID, side: Side?, productFamily: ProductFamily?): List<DeliveryObligationView> =
        repository.openObligations(legalEntityId, side, productFamily)

    @Transactional(readOnly = true)
    override fun obligation(scheduleId: UUID): DeliveryObligationView? = repository.obligation(scheduleId)

    // ----------------------------------------------------------------------------------------------------------

    /**
     * Whether a trade needs a second person's approval: its notional, converted at the trade date's rate, against the
     * threshold. A trade whose currency cannot be converted is treated as above it.
     */
    private fun requiresApproval(trade: TradeView): Boolean {
        if (approvalThreshold.signum() == 0 || trade.requiresRiskApproval) return true
        val converted = trade.legs.map { leg ->
            fxRates.rate(trade.tradeDate, leg.priceCurrency.currencyCode, thresholdCurrency)?.let { leg.notional * it.rate } ?: return true
        }
        return converted.fold(BigDecimal.ZERO, BigDecimal::add) >= approvalThreshold
    }

    private fun runChecks(trade: TradeView): List<ApprovalFinding> = checks.orderedStream().toList().flatMap { it.check(trade) }

    private fun locked(id: UUID): TradeView = repository.lock(id) ?: throw NotFoundException("Trade", id)

    private fun save(trade: TradeView, actor: String, reason: String?) {
        if (!repository.saveVersion(trade, actor, reason)) throw StaleVersionException("Trade", trade.id, trade.version - 1, -1)
    }

    /** Approved trades of entities whose obligations open at approval oblige delivery from now on. */
    private fun openObligationsIfDue(trade: TradeView) {
        if (trade.status == TradeStatus.APPROVED && trade.obligationsOpen) obligationsOf(trade).forEach { events.publishEvent(DeliveryObligationOpened(it)) }
    }

    private fun obligationsOf(trade: TradeView): List<DeliveryObligationView> = trade.legs.flatMap { leg ->
        leg.deliveries.map { d ->
            DeliveryObligationView(
                scheduleId = d.id,
                tradeId = trade.id,
                tradeNumber = trade.tradeNumber,
                legId = leg.id,
                legalEntityId = trade.legalEntityId,
                bookId = trade.bookId,
                counterpartyId = trade.counterpartyId,
                side = leg.side,
                productFamily = trade.productFamily,
                energyCarrier = leg.energyCarrier,
                allowanceProductCode = leg.allowanceProductCode,
                criteriaId = leg.criteriaId,
                criterion = leg.criterion,
                quantity = d.quantity,
                deliveredQuantity = d.deliveredQuantity,
                deliveryWindow = d.deliveryWindow,
                deliveryDate = d.deliveryDate,
                deliveryAccountId = leg.deliveryAccountId,
                counterpartyAccountRef = leg.counterpartyAccountRef,
                counterpartyAccountId = leg.counterpartyAccountId,
                price = leg.price,
                priceCurrency = leg.priceCurrency,
                priceType = leg.priceType,
                status = d.status,
                priceIndex = leg.priceIndex,
                bucketCode = leg.bucketCode,
                bucketMatch = leg.bucketMatch,
            )
        }
    }

    private companion object {
        const val UTI_LENGTH = 52
        const val SYSTEM_USER = "system"
    }
}
