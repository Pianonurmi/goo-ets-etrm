package com.pianonurmi.etrm.risk

import com.pianonurmi.etrm.allocation.api.GooRiskQuery
import com.pianonurmi.etrm.clearing.api.ClearingQuery
import com.pianonurmi.etrm.clearing.api.MarginCallQuery
import com.pianonurmi.etrm.inventory.api.AllowanceHoldingQuery
import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.marketdata.api.CurveQuery
import com.pianonurmi.etrm.marketdata.api.FxRates
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.tx.Savepoints
import com.pianonurmi.etrm.position.api.PositionQuery
import com.pianonurmi.etrm.product.api.ProductBucketQuery
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.risk.application.ComplianceShortMeasurer
import com.pianonurmi.etrm.risk.application.CreditExposureService
import com.pianonurmi.etrm.risk.application.CreditFigures
import com.pianonurmi.etrm.risk.application.CreditLimitMeasurer
import com.pianonurmi.etrm.risk.application.GooLimitMeasurer
import com.pianonurmi.etrm.risk.application.LimitMeasurement
import com.pianonurmi.etrm.risk.application.MarginLiquidityMeasurer
import com.pianonurmi.etrm.risk.application.PositionFigures
import com.pianonurmi.etrm.risk.application.PositionLimitMeasurer
import com.pianonurmi.etrm.risk.application.PreTradeChecks
import com.pianonurmi.etrm.risk.application.RiskFx
import com.pianonurmi.etrm.risk.application.RiskLimitService
import com.pianonurmi.etrm.risk.application.RiskQueries
import com.pianonurmi.etrm.risk.application.StopLossMeasurer
import com.pianonurmi.etrm.risk.application.TenorLimitMeasurer
import com.pianonurmi.etrm.risk.domain.RiskRepository
import com.pianonurmi.etrm.settlement.api.SettlementReports
import com.pianonurmi.etrm.trade.api.TradeApprovalCheck
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.valuation.api.ValuationQuery
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock

/** Risk's services wired by hand, as the Spring context wires them, for tests without one. */
class RiskServices(val lifecycle: RiskLimitService, val measurement: LimitMeasurement, val checks: PreTradeChecks, val queries: RiskQueries)

/** The ports risk reads, in one place so a test replaces only those it needs. */
@Suppress("LongParameterList") // every port the module reads, as the Spring context passes them
class RiskPorts(
    val repository: RiskRepository,
    val referenceData: ReferenceDataQuery,
    val trades: TradeQuery,
    val valuations: ValuationQuery,
    val settlement: SettlementReports,
    val positions: PositionQuery,
    val holdings: AllowanceHoldingQuery,
    val lots: LotQuery,
    val curves: CurveQuery,
    val fxRates: FxRates,
    val buckets: ProductBucketQuery,
    val clearing: ClearingQuery,
    val margin: MarginCallQuery,
    val goo: ObjectProvider<GooRiskQuery>,
    val approvalChecks: ObjectProvider<TradeApprovalCheck>,
)

fun riskServices(ports: RiskPorts, actors: ActorProvider, events: ApplicationEventPublisher, clock: Clock, savepoints: Savepoints): RiskServices {
    val fx = RiskFx(ports.fxRates)
    val positions = PositionFigures(ports.trades, ports.valuations, ports.referenceData, ports.holdings, ports.lots, savepoints, clock)
    val credit = CreditFigures(positions, ports.curves, fx, ports.repository, ports.referenceData)
    val exposures = CreditExposureService(
        ports.repository,
        ports.referenceData,
        ports.valuations,
        ports.settlement,
        ports.clearing,
        ports.margin,
        credit,
        fx,
        clock,
    )
    val measurers = listOf(
        CreditLimitMeasurer(credit),
        PositionLimitMeasurer(positions),
        TenorLimitMeasurer(positions),
        StopLossMeasurer(ports.valuations, fx),
        ComplianceShortMeasurer(ports.positions),
        MarginLiquidityMeasurer(ports.margin, fx),
        GooLimitMeasurer(ports.goo, savepoints),
    )
    val measurement = LimitMeasurement(ports.repository, ports.referenceData, exposures, measurers, actors, events, clock)
    val checks = PreTradeChecks(ports.repository, ports.referenceData, positions, credit, ports.goo, savepoints, clock)
    return RiskServices(
        RiskLimitService(ports.repository, ports.referenceData, ports.buckets, measurement, actors, events, clock),
        measurement,
        checks,
        RiskQueries(ports.repository, ports.trades, ports.approvalChecks, checks, actors),
    )
}
