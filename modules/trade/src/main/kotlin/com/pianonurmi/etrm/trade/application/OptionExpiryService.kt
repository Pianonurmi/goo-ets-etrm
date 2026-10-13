package com.pianonurmi.etrm.trade.application

import com.pianonurmi.etrm.marketdata.api.InstrumentKind
import com.pianonurmi.etrm.marketdata.api.MarketDataQuery
import com.pianonurmi.etrm.marketdata.api.QuoteType
import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.product.api.FuturesContractQuery
import com.pianonurmi.etrm.product.api.InstrumentType
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.trade.api.CaptureDelivery
import com.pianonurmi.etrm.trade.api.CaptureLeg
import com.pianonurmi.etrm.trade.api.CaptureTrade
import com.pianonurmi.etrm.trade.api.OptionExpiryView
import com.pianonurmi.etrm.trade.api.OptionOutcome
import com.pianonurmi.etrm.trade.api.OptionType
import com.pianonurmi.etrm.trade.api.RegulatoryTerms
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeLegView
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.trade.domain.TradeRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Options at expiry (ADR 0047): a leg in the money against the underlying's settlement price of the expiry date is
 * exercised into the future at the strike, captured and confirmed by the system; otherwise it is abandoned. A leg
 * whose settlement price is not in yet waits for the next run: nothing is guessed.
 */
@Service
@Transactional
class OptionExpiryService(
    private val repository: TradeRepository,
    private val trades: TradeService,
    private val marketData: MarketDataQuery,
    private val futures: FuturesContractQuery,
) {
    private val log = LoggerFactory.getLogger(OptionExpiryService::class.java)

    /** Confirmed trades with an option leg expiring on or before [date]. */
    @Transactional(readOnly = true)
    fun due(date: LocalDate): List<UUID> = repository.optionsExpiringBy(date)

    /** Decides the trade's option legs that have expired by [date]; returns the trade, or null when it was not open. */
    fun expire(tradeId: UUID, date: LocalDate): TradeView? {
        var trade = trades.requireTrade(tradeId)
        if (trade.status != TradeStatus.CONFIRMED) return null
        trade.legs.filter { leg -> leg.option != null && leg.expiry == null && !leg.option.expiryDate.isAfter(date) }.forEach { leg ->
            val terms = leg.option!!
            val settlement = settlementPrice(terms.underlyingContractCode, terms.expiryDate)
            if (settlement == null) {
                log.warn(
                    "Option {} leg {} not decided: no settlement price of {} on {}",
                    trade.tradeNumber,
                    leg.legNo,
                    terms.underlyingContractCode,
                    terms.expiryDate,
                )
                return@forEach
            }
            val inTheMoney = when (terms.optionType) {
                OptionType.CALL -> settlement > terms.strike
                OptionType.PUT -> settlement < terms.strike
            }
            val future = if (inTheMoney) {
                trades.captureConfirmed(exercise(trade, leg), "exercise of ${trade.tradeNumber} leg ${leg.legNo}", "exercise of ${trade.tradeNumber}")
            } else {
                null
            }
            val outcome = OptionExpiryView(
                outcome = if (future != null) OptionOutcome.EXERCISED else OptionOutcome.ABANDONED,
                settlementPrice = settlement,
                decidedOn = terms.expiryDate,
                futureTradeId = future?.id,
                futureTradeNumber = future?.tradeNumber,
            )
            trade = trades.recordExpiry(trade.id, leg.legNo, outcome, future)
        }
        return trade
    }

    private fun settlementPrice(contractCode: String, date: LocalDate): BigDecimal? {
        val instrument = marketData.instruments(InstrumentKind.EUA_FUTURE).firstOrNull { it.futuresContractCode == contractCode } ?: return null
        return marketData.latestQuote(instrument.code, QuoteType.SETTLEMENT, date)?.takeIf { it.quoteDate == date }?.value
    }

    /** The future at the strike: a bought call or sold put buys it, a bought put or sold call sells it. */
    private fun exercise(option: TradeView, leg: TradeLegView): CaptureTrade {
        val terms = leg.option!!
        val contract = futures.futuresContract(terms.underlyingContractCode)
            ?: throw IllegalStateException("Underlying ${terms.underlyingContractCode} of ${option.tradeNumber} not found")
        val side = when {
            terms.optionType == OptionType.CALL -> leg.side
            leg.side == Side.BUY -> Side.SELL
            else -> Side.BUY
        }
        return CaptureTrade(
            legalEntityId = option.legalEntityId,
            bookId = option.bookId,
            counterpartyId = option.counterpartyId,
            productFamily = ProductFamily.EMISSION_ALLOWANCE,
            instrumentType = InstrumentType.FUTURE,
            tradeDate = terms.expiryDate,
            trader = option.trader,
            externalRef = "exercise:${option.tradeNumber}",
            legs = listOf(
                CaptureLeg(
                    side = side,
                    allowanceProductCode = leg.allowanceProductCode,
                    quantity = leg.quantity,
                    unit = leg.unit,
                    price = terms.strike,
                    priceCurrency = leg.priceCurrency.currencyCode,
                    deliveryAccountId = leg.deliveryAccountId,
                    counterpartyAccountId = leg.counterpartyAccountId,
                    deliveries = listOf(
                        CaptureDelivery(LocalDateRange(contract.deliveryDate, contract.deliveryDate.plusDays(1)), contract.deliveryDate, leg.quantity),
                    ),
                    futuresContractCode = contract.code,
                ),
            ),
            reason = "exercise of ${option.tradeNumber} leg ${leg.legNo}",
            regulatory = RegulatoryTerms(venueMic = option.regulatory.venueMic, ccpCounterpartyId = option.regulatory.ccpCounterpartyId),
        )
    }
}
