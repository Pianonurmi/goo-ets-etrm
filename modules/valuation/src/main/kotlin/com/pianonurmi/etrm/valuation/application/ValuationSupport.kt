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

internal const val EUR = "EUR"

/** Valuations are read by risk, middle office, accounting, audit and traders, within their entities. */
internal fun ActorProvider.requireValuationReader(legalEntityId: UUID) {
    val actor = current()
    actor.requireRole(Role.RISK, Role.MIDDLE_OFFICE, Role.ACCOUNTANT, Role.AUDITOR, Role.TRADER, Role.SYSTEM)
    actor.requireEntity(legalEntityId)
}

/** A cancelled trade's schedules leave the explain as cancellations rather than as a market move. */
internal fun TradeQuery.isCancelled(tradeId: UUID): Boolean = trade(tradeId)?.status == TradeStatus.CANCELLED
