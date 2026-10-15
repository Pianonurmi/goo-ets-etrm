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

/** Runs, results, the official run of a date, portfolio bridges and explains, and ad hoc values of a trade or terms. */
@Service
@Transactional(readOnly = true)
class ValuationQueries(
    private val repository: ValuationRepository,
    private val trades: TradeQuery,
    private val valuer: TradeValuer,
    private val explains: PnlExplainService,
    private val referenceData: ReferenceDataQuery,
    private val actorProvider: ActorProvider,
) : ValuationQuery {
    override fun run(id: UUID): ValuationRunView? = repository.run(id)?.also { actorProvider.requireValuationReader(it.legalEntityId) }

    override fun runs(legalEntityId: UUID, from: LocalDate, to: LocalDate): List<ValuationRunView> {
        actorProvider.requireValuationReader(legalEntityId)
        return repository.runs(legalEntityId, from, to)
    }

    override fun results(runId: UUID, bookId: UUID?): List<ValuationResultView> {
        val run = repository.run(runId) ?: throw NotFoundException("ValuationRun", runId)
        actorProvider.requireValuationReader(run.legalEntityId)
        return repository.results(runId, bookId)
    }

    override fun resultPage(runId: UUID, bookId: UUID?, limit: Int, cursor: String?): ValuationResultPage {
        if (limit !in 1..MAX_RESULT_PAGE) throw ValidationException("limit must be 1..$MAX_RESULT_PAGE")
        val run = repository.run(runId) ?: throw NotFoundException("ValuationRun", runId)
        actorProvider.requireValuationReader(run.legalEntityId)
        val after = cursor?.let {
            Cursor.decode(it, RESULT_KEY_PARTS) { p -> ResultKey(p[0], p[1].toInt(), LocalDate.parse(p[2]), UUID.fromString(p[3])) }
        }
        val (items, next) = repository.resultPage(runId, bookId, limit, after)
            .page(limit) { Cursor.encode(it.tradeNumber, it.legNo, it.deliveryDate, it.scheduleId) }
        return ValuationResultPage(items, next)
    }

    override fun official(legalEntityId: UUID, date: LocalDate, orEarlier: Boolean): ValuationRunView? {
        actorProvider.requireValuationReader(legalEntityId)
        return repository.official(legalEntityId, date, orEarlier)
    }

    override fun portfolio(runId: UUID): PortfolioBridgeView? {
        val run = repository.run(runId) ?: throw NotFoundException("ValuationRun", runId)
        actorProvider.requireValuationReader(run.legalEntityId)
        return repository.portfolio(runId)
    }

    override fun portfolioExplain(legalEntityId: UUID, from: LocalDate, to: LocalDate): PortfolioExplainView =
        explains.portfolioExplain(legalEntityId, from, to)

    override fun explain(legalEntityId: UUID, from: LocalDate, to: LocalDate): PnlExplainView? = explains.explain(legalEntityId, from, to)

    override fun forwardValue(legalEntityId: UUID, date: LocalDate, terms: ForwardTerms): ForwardValue? {
        actorProvider.requireValuationReader(legalEntityId)
        return valuer.forwardValue(referenceData.requireLegalEntity(legalEntityId).baseCurrency.currencyCode, date, terms)
    }

    override fun optionValuation(legalEntityId: UUID, date: LocalDate, leg: TradeLegView): OptionValuation? {
        actorProvider.requireValuationReader(legalEntityId)
        return valuer.optionValuation(date, leg)
    }

    override fun tradeMtm(tradeId: UUID, date: LocalDate): List<ValuationResultView> {
        val trade = trades.trade(tradeId) ?: throw NotFoundException("Trade", tradeId)
        actorProvider.requireValuationReader(trade.legalEntityId)
        val base = referenceData.requireLegalEntity(trade.legalEntityId).baseCurrency.currencyCode
        val bookType = referenceData.requireBook(trade.bookId).bookType
        return valuer.value(UUID(0, 0), trade, date, base, valuer.discounter(date)) { bookType }
    }
}

private const val MAX_RESULT_PAGE = 1000
private const val RESULT_KEY_PARTS = 4
