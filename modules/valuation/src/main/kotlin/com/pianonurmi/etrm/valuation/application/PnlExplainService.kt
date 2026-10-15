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

/** The P&L explain between two official runs, and the portfolio value's explain over a period (ADR 0039). */
@Service
@Transactional(readOnly = true)
class PnlExplainService(
    private val repository: ValuationRepository,
    private val trades: TradeQuery,
    private val valuer: TradeValuer,
    private val portfolioValues: ObjectProvider<PortfolioValueQuery>,
    private val savepoints: Savepoints,
    private val actorProvider: ActorProvider,
) {
    fun portfolioExplain(legalEntityId: UUID, from: LocalDate, to: LocalDate): PortfolioExplainView {
        actorProvider.requireValuationReader(legalEntityId)
        if (to.isBefore(from)) throw ValidationException("The period ends before it starts")
        val steps = repository.portfolioExplains(legalEntityId, from, to)
        val totals = PortfolioCause.entries.associateWith { cause -> steps.mapNotNull { it.causes[cause] }.fold(BigDecimal.ZERO, BigDecimal::add) }
        val realised = portfolioValues.ifAvailable?.let { query ->
            // Plans approved after the first day up to and including the last, like the runs.
            runCatching { savepoints.attempt { query.realisedGiveAway(legalEntityId, from.plusDays(1), to.plusDays(1)).totalEur } }.getOrNull()
        }
        return PortfolioExplainView(legalEntityId, from, to, EUR, steps, totals, realised)
    }

    fun explain(legalEntityId: UUID, from: LocalDate, to: LocalDate): PnlExplainView? {
        actorProvider.requireValuationReader(legalEntityId)
        val r0 = repository.official(legalEntityId, from, orEarlier = true) ?: return null
        val r1 = repository.official(legalEntityId, to, orEarlier = true) ?: return null
        val rows = PnlExplainer.explain(repository.results(r0.id, null), repository.results(r1.id, null), valuer.roller(r1.valuationDate), trades::isCancelled)
        val components = ExplainComponent.entries.associateWith { c -> rows.filter { it.component == c }.fold(BigDecimal.ZERO) { s, r -> s + r.amountBase } }
            .filterValues { it.signum() != 0 }
        return PnlExplainView(
            legalEntityId,
            r0.id,
            r1.id,
            r0.valuationDate,
            r1.valuationDate,
            r1.baseCurrency,
            r0.totalMtmBase,
            r1.totalMtmBase,
            components,
            rows,
        )
    }
}
