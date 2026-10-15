package com.pianonurmi.etrm.valuation.application

import com.pianonurmi.etrm.allocation.api.PortfolioValueQuery
import com.pianonurmi.etrm.marketdata.api.CurvePrice
import com.pianonurmi.etrm.marketdata.api.CurveQuery
import com.pianonurmi.etrm.marketdata.api.DiscountFactor
import com.pianonurmi.etrm.marketdata.api.DiscountFactors
import com.pianonurmi.etrm.marketdata.api.FxRates
import com.pianonurmi.etrm.marketdata.api.IndexFixings
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.SecondPerson
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.paging.Cursor
import com.pianonurmi.etrm.platform.paging.page
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.tx.Savepoints
import com.pianonurmi.etrm.product.api.FuturesContractQuery
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.product.api.criteria.CriteriaService
import com.pianonurmi.etrm.product.api.criteria.VintageYears
import com.pianonurmi.etrm.referencedata.api.BookType
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.trade.api.OptionTerms
import com.pianonurmi.etrm.trade.api.PriceType
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeFilter
import com.pianonurmi.etrm.trade.api.TradeLegView
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.valuation.api.ExplainComponent
import com.pianonurmi.etrm.valuation.api.ForwardTerms
import com.pianonurmi.etrm.valuation.api.ForwardValue
import com.pianonurmi.etrm.valuation.api.OptionValuation
import com.pianonurmi.etrm.valuation.api.PnlExplainView
import com.pianonurmi.etrm.valuation.api.PortfolioBridgeView
import com.pianonurmi.etrm.valuation.api.PortfolioBucketRow
import com.pianonurmi.etrm.valuation.api.PortfolioCause
import com.pianonurmi.etrm.valuation.api.PortfolioExplainView
import com.pianonurmi.etrm.valuation.api.PortfolioObligationRow
import com.pianonurmi.etrm.valuation.api.ResultStatus
import com.pianonurmi.etrm.valuation.api.RunKind
import com.pianonurmi.etrm.valuation.api.RunStatus
import com.pianonurmi.etrm.valuation.api.ValuationCommand
import com.pianonurmi.etrm.valuation.api.ValuationQuery
import com.pianonurmi.etrm.valuation.api.ValuationResultPage
import com.pianonurmi.etrm.valuation.api.ValuationResultView
import com.pianonurmi.etrm.valuation.api.ValuationRunRejected
import com.pianonurmi.etrm.valuation.api.ValuationRunSignedOff
import com.pianonurmi.etrm.valuation.api.ValuationRunSubmitted
import com.pianonurmi.etrm.valuation.api.ValuationRunView
import com.pianonurmi.etrm.valuation.domain.Black76
import com.pianonurmi.etrm.valuation.domain.Mtm
import com.pianonurmi.etrm.valuation.domain.OptionRight
import com.pianonurmi.etrm.valuation.domain.PnlExplainer
import com.pianonurmi.etrm.valuation.domain.PortfolioExplainer
import com.pianonurmi.etrm.valuation.domain.ResultKey
import com.pianonurmi.etrm.valuation.domain.ValuationRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A run's portfolio value from allocation (ADR 0039) and the cheapest-to-deliver view of GoO sales. */
@Component
class PortfolioBridges(
    private val portfolioValues: ObjectProvider<PortfolioValueQuery>,
    private val savepoints: Savepoints,
    private val fx: FxRates,
) {
    private val log = LoggerFactory.getLogger(PortfolioBridges::class.java)

    /**
     * The run's portfolio value (ADR 0039, management view): allocation's value of the held book at the run date's
     * prices, plus the GoO trades' MtM in EUR. None when allocation is not available or cannot value the book (never
     * failing the run), or when the base currency has no rate to EUR.
     */
    fun portfolioOf(runId: UUID, legalEntityId: UUID, date: LocalDate, base: String, results: List<ValuationResultView>): PortfolioBridgeView? {
        val query = portfolioValues.ifAvailable ?: return null
        val value = try {
            savepoints.attempt { query.value(legalEntityId, date) }
        } catch (e: RuntimeException) {
            log.warn("Portfolio value of {} on {} could not be computed", legalEntityId, date, e)
            null
        } ?: return null
        val toEur = if (base == EUR) BigDecimal.ONE else fx.rate(date, base, EUR)?.rate ?: return null
        val tradeMtm = (
            results.filter { it.productFamily == ProductFamily.GUARANTEE_OF_ORIGIN }
                .mapNotNull { it.mtmBase }.fold(BigDecimal.ZERO, BigDecimal::add) * toEur
        ).setScale(2, RoundingMode.HALF_UP)
        return PortfolioBridgeView(
            runId = runId,
            legalEntityId = legalEntityId,
            valuationDate = date,
            currency = value.currency,
            heldMwh = value.heldMwh,
            inventoryValue = value.inventoryValueEur,
            tradeMtm = tradeMtm,
            giveAway = value.giveAwayEur,
            transferFees = value.transferFeesEur,
            expiryHaircut = value.expiryHaircutEur,
            portfolioValue = value.netEur + tradeMtm,
            allocatedMwh = value.allocatedMwh,
            shortfallMwh = value.shortfallMwh,
            unpricedMwh = value.unpricedMwh,
            stamp = value.stamp,
            computedAt = value.computedAt,
            buckets = value.byBucket.map { PortfolioBucketRow(it.bucketCode, it.productionYear, it.heldMwh, it.valueEur, it.allocatedMwh) },
            obligations = value.byObligation.map {
                PortfolioObligationRow(
                    obligationId = it.obligationId,
                    scheduleId = it.sourceRef,
                    tradeRef = it.tradeRef,
                    bucketCode = it.bucketCode,
                    vintageYear = it.vintageYear,
                    demandMwh = it.demandMwh,
                    allocatedMwh = it.allocatedMwh,
                    cheapestToDeliverEur = it.cheapestToDeliverEur,
                    saleMarkEur = it.saleMarkEur,
                    giveAwayEur = it.giveAwayEur,
                    transferFeesEur = it.transferFeesEur,
                )
            },
            priorRunId = null,
            explain = emptyMap(),
        )
    }

    /**
     * A GoO sale valued against the certificates that deliver it instead of its bucket curve: the cheapest-to-deliver
     * price (EUR per MWh) in the contract currency, and the MtM on it. Shown only; the official MtM is unchanged.
     */
    fun withCheapestToDeliver(results: List<ValuationResultView>, portfolio: PortfolioBridgeView, date: LocalDate): List<ValuationResultView> {
        val ctd = portfolio.obligations.filter {
            it.scheduleId != null && it.cheapestToDeliverEur != null
        }.associate { it.scheduleId!! to it.cheapestToDeliverEur!! }
        return results.map { result ->
            val price = ctd[result.scheduleId]
            if (price == null || result.contractPrice == null || result.fxToBase == null) return@map result
            val inContract = if (result.contractCurrency == EUR) BigDecimal.ONE else fx.rate(date, EUR, result.contractCurrency)?.rate ?: return@map result
            val mtm = result.quantity * (price * inContract - result.contractPrice) * (result.discountFactor ?: BigDecimal.ONE)
            result.copy(ctdPriceEur = price, ctdMtmBase = (mtm * result.fxToBase).setScale(2, RoundingMode.HALF_UP))
        }
    }
}
