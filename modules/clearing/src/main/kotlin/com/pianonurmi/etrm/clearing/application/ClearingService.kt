package com.pianonurmi.etrm.clearing.application

import com.pianonurmi.etrm.clearing.api.ClearingAccountView
import com.pianonurmi.etrm.clearing.api.ClearingCommand
import com.pianonurmi.etrm.clearing.api.ClearingQuery
import com.pianonurmi.etrm.clearing.api.CreateClearingAccount
import com.pianonurmi.etrm.clearing.api.MarginLineView
import com.pianonurmi.etrm.clearing.api.MarginRunView
import com.pianonurmi.etrm.clearing.api.VariationMarginComputed
import com.pianonurmi.etrm.clearing.domain.ClearingRepository
import com.pianonurmi.etrm.clearing.domain.VariationMargin
import com.pianonurmi.etrm.marketdata.api.InstrumentKind
import com.pianonurmi.etrm.marketdata.api.MarketDataQuery
import com.pianonurmi.etrm.marketdata.api.QuoteType
import com.pianonurmi.etrm.platform.crypto.HashChain
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.product.api.FuturesContractQuery
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.referencedata.api.CounterpartyType
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeFilter
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeView
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

/**
 * Clearing accounts and the daily variation margin of the futures cleared through them (ADR 0046). A future is
 * margined from its trade date to its contract's last trading day; the margin of a day is computed once per set of
 * inputs, and a change of trades or prices gives a new version that supersedes the earlier one.
 */
@Service
@Transactional
class ClearingService(
    private val repository: ClearingRepository,
    private val trades: TradeQuery,
    private val futures: FuturesContractQuery,
    private val marketData: MarketDataQuery,
    private val referenceData: ReferenceDataQuery,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) : ClearingCommand, ClearingQuery {
    override fun createAccount(command: CreateClearingAccount): ClearingAccountView {
        val actor = actorProvider.current()
        actor.requireRole(Role.BACK_OFFICE, Role.MIDDLE_OFFICE)
        actor.requireEntity(command.legalEntityId)
        referenceData.requireLegalEntity(command.legalEntityId)
        val house = referenceData.requireCounterparty(command.clearingHouseId)
        if (house.counterpartyType != CounterpartyType.CLEARING_HOUSE) throw ValidationException("${house.code} is not a clearing house")
        val code = command.code.trim().uppercase()
        if (!code.matches(Regex("[A-Z0-9_-]+"))) throw ValidationException("Account codes use letters, digits, hyphens and underscores: $code")
        if (command.clearingMember.isBlank()) throw ValidationException("Name the clearing member the account is held through")
        if (repository.accounts(null).any { it.code == code }) throw ConflictException("Clearing account $code already exists")
        if (repository.accounts(command.legalEntityId).any { it.active && it.clearingHouseId == house.id }) {
            throw ConflictException("The entity already has an account at ${house.code}")
        }
        val account = ClearingAccountView(
            id = Ids.newId(),
            code = code,
            legalEntityId = command.legalEntityId,
            clearingHouseId = house.id,
            clearingMember = command.clearingMember.trim(),
            currency = Currency.getInstance(command.currency.uppercase()).currencyCode,
            active = true,
            createdBy = actor.userId,
            createdAt = clock.instant(),
        )
        repository.insertAccount(account)
        return account
    }

    override fun computeMargin(accountId: UUID, businessDate: LocalDate): MarginRunView {
        val actor = actorProvider.current()
        actor.requireRole(Role.BACK_OFFICE, Role.MIDDLE_OFFICE, Role.SYSTEM)
        val account = repository.account(accountId) ?: throw NotFoundException("ClearingAccount", accountId)
        actor.requireEntity(account.legalEntityId)
        if (businessDate.dayOfWeek == DayOfWeek.SATURDAY || businessDate.dayOfWeek == DayOfWeek.SUNDAY) {
            throw ValidationException("$businessDate is not a business day")
        }
        val lines = lines(account, businessDate)
        val total = lines.fold(BigDecimal.ZERO.setScale(2)) { sum, l -> sum + l.variationMargin }
        val hash = HashChain.next(
            null,
            Json.canonical(
                mapOf(
                    "account" to account.code,
                    "date" to businessDate.toString(),
                    "lines" to lines.map {
                        listOf(it.contractCode, it.positionBefore.toPlainString(), it.tradedQuantity.toPlainString(), it.variationMargin.toPlainString())
                    },
                ),
            ),
        )
        val current = repository.currentRun(account.id, businessDate, lock = true)
        if (current != null && current.inputsHash == hash) return current
        val now = clock.instant()
        current?.let { repository.supersede(it.id, now) }
        val run = MarginRunView(
            id = Ids.newId(),
            accountId = account.id,
            legalEntityId = account.legalEntityId,
            businessDate = businessDate,
            version = repository.nextVersion(account.id, businessDate),
            currency = account.currency,
            variationMargin = total,
            lines = lines,
            inputsHash = hash,
            computedBy = actor.userId,
            computedAt = now,
            supersededAt = null,
        )
        repository.insertRun(run)
        events.publishEvent(VariationMarginComputed(run, current?.variationMargin ?: BigDecimal.ZERO.setScale(2)))
        return run
    }

    /** One line per contract with a position overnight or a trade on the day, up to the contract's last trading day. */
    private fun lines(account: ClearingAccountView, date: LocalDate): List<MarginLineView> {
        val fills = clearedFutures(account).flatMap { trade ->
            trade.legs.filter { it.futuresContractCode != null && !trade.tradeDate.isAfter(date) }.map { leg ->
                val sign = if (leg.side == Side.BUY) BigDecimal.ONE else BigDecimal.ONE.negate()
                Triple(leg.futuresContractCode!!, trade.tradeDate, VariationMargin.Fill(leg.quantity * sign, leg.price))
            }
        }
        return fills.groupBy { it.first }.toSortedMap().mapNotNull { (code, contractFills) ->
            val contract = futures.futuresContract(code) ?: throw NotFoundException("FuturesContract", code)
            if (date.isAfter(contract.lastTradingDay)) return@mapNotNull null
            if (contract.currency != account.currency) throw ValidationException("$code trades in ${contract.currency}, the account in ${account.currency}")
            val before = contractFills.filter { it.second.isBefore(date) }.fold(BigDecimal.ZERO) { s, f -> s + f.third.quantity }
            val todays = contractFills.filter { it.second == date }.map { it.third }
            if (before.signum() == 0 && todays.isEmpty()) return@mapNotNull null
            val instrument = marketData.instruments(InstrumentKind.EUA_FUTURE, activeOnly = false).firstOrNull { it.futuresContractCode == code }
                ?: throw ValidationException("No market data instrument for $code")
            val settlement = marketData.latestQuote(instrument.code, QuoteType.SETTLEMENT, date)?.takeIf { it.quoteDate == date }
                ?: throw ValidationException("No settlement price of $code on $date")
            val previous = if (before.signum() == 0) {
                null
            } else {
                marketData.latestQuote(instrument.code, QuoteType.SETTLEMENT, date.minusDays(1))?.value
                    ?: throw ValidationException("No settlement price of $code before $date")
            }
            VariationMargin.line(code, before, todays, settlement.value, previous)
        }
    }

    /** The entity's futures trades whose clearing house is the account's (the CCP, or the counterparty when it clears). */
    private fun clearedFutures(account: ClearingAccountView): List<TradeView> =
        trades.trades(TradeFilter(legalEntityId = account.legalEntityId, productFamily = ProductFamily.EMISSION_ALLOWANCE, limit = MAX_TRADES))
            .filter { it.status !in NOT_MARGINED }
            .filter { it.legs.any { leg -> leg.futuresContractCode != null } }
            .filter { (it.regulatory.ccpCounterpartyId ?: it.counterpartyId) == account.clearingHouseId }

    // ----------------------------------------------------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------------------------------------------------

    private fun readable(legalEntityId: UUID) {
        val actor = actorProvider.current()
        actor.requireRole(Role.BACK_OFFICE, Role.MIDDLE_OFFICE, Role.ACCOUNTANT, Role.RISK, Role.AUDITOR, Role.TRADER, Role.SYSTEM)
        actor.requireEntity(legalEntityId)
    }

    @Transactional(readOnly = true)
    override fun accounts(legalEntityId: UUID?): List<ClearingAccountView> {
        val actor = actorProvider.current()
        legalEntityId?.let(::readable)
        return repository.accounts(legalEntityId).filter { actor.canAccess(it.legalEntityId) }
    }

    @Transactional(readOnly = true)
    override fun account(id: UUID): ClearingAccountView? = repository.account(id)?.also { readable(it.legalEntityId) }

    @Transactional(readOnly = true)
    override fun runs(accountId: UUID, from: LocalDate, to: LocalDate): List<MarginRunView> {
        val account = repository.account(accountId) ?: throw NotFoundException("ClearingAccount", accountId)
        readable(account.legalEntityId)
        if (to.isBefore(from)) throw ValidationException("The period ends before it starts")
        return repository.runs(accountId, from, to)
    }

    @Transactional(readOnly = true)
    override fun run(id: UUID): MarginRunView? = repository.run(id)?.also { readable(it.legalEntityId) }

    @Transactional(readOnly = true)
    override fun marginedTradeIds(legalEntityId: UUID): Set<UUID> =
        repository.accounts(legalEntityId).filter { it.active }.flatMap { account -> clearedFutures(account).map { it.id } }.toSet()

    private companion object {
        val NOT_MARGINED = setOf(TradeStatus.DRAFT, TradeStatus.PENDING_APPROVAL, TradeStatus.CANCELLED)
        const val MAX_TRADES = 100_000
    }
}
