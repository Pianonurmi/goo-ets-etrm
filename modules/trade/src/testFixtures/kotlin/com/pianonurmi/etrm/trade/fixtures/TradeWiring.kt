package com.pianonurmi.etrm.trade.fixtures

import com.pianonurmi.etrm.marketdata.api.FxRates
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.test.Providers
import com.pianonurmi.etrm.product.fixtures.ProductWiring
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.trade.adapter.jooq.JooqTradeRepository
import com.pianonurmi.etrm.trade.api.TradeApprovalCheck
import com.pianonurmi.etrm.trade.application.TradeService
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Clock

/** The trade service as the application wires it, for hand-wired tests of any module. */
object TradeWiring {
    /**
     * Every trade needs four-eyes approval unless [approvalThreshold] says otherwise ([thresholdCurrency] notional);
     * no other module's pre-trade checks unless [checks] names them.
     */
    @Suppress("LongParameterList") // the service's own collaborators, each with the test default
    fun tradeService(
        dsl: DSLContext,
        referenceData: ReferenceDataQuery,
        product: ProductWiring,
        actor: ActorProvider,
        events: ApplicationEventPublisher,
        fx: FxRates,
        clock: Clock = Clock.systemUTC(),
        approvalThreshold: BigDecimal = BigDecimal.ZERO,
        thresholdCurrency: String = "EUR",
        checks: ObjectProvider<TradeApprovalCheck> = Providers.none(),
    ) = TradeService(
        JooqTradeRepository(dsl),
        referenceData,
        product.criteria,
        product.buckets,
        product.catalogue,
        actor,
        events,
        clock,
        approvalThreshold,
        fx,
        thresholdCurrency,
        product.futures,
        checks,
    )
}
