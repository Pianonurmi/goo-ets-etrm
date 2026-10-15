package com.pianonurmi.etrm.trade.fixtures

import com.pianonurmi.etrm.marketdata.api.FxRates
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.test.Providers
import com.pianonurmi.etrm.product.fixtures.ProductWiring
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.trade.adapter.jooq.JooqTradeRepository
import com.pianonurmi.etrm.trade.api.TradeApprovalCheck
import com.pianonurmi.etrm.trade.api.TradeCommand
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.application.IntercompanyMirror
import com.pianonurmi.etrm.trade.application.TradeAmendment
import com.pianonurmi.etrm.trade.application.TradeApprovalPolicy
import com.pianonurmi.etrm.trade.application.TradeBuilder
import com.pianonurmi.etrm.trade.application.TradeCapture
import com.pianonurmi.etrm.trade.application.TradeCommands
import com.pianonurmi.etrm.trade.application.TradeLifecycle
import com.pianonurmi.etrm.trade.application.TradeQueries
import com.pianonurmi.etrm.trade.application.TradeStore
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Clock

/** The trade module's services, wired as the application wires them: its command and query API, and the parts. */
class WiredTrades(
    val capture: TradeCapture,
    val amendment: TradeAmendment,
    val lifecycle: TradeLifecycle,
    val queries: TradeQueries,
) : TradeCommand by TradeCommands(capture, amendment, lifecycle),
    TradeQuery by queries

/** The trade services as the application wires them, for hand-wired tests of any module. */
object TradeWiring {
    /**
     * Every trade needs four-eyes approval unless [approvalThreshold] says otherwise ([thresholdCurrency] notional);
     * no other module's pre-trade checks unless [checks] names them.
     */
    @Suppress("LongParameterList") // the services' own collaborators, each with the test default
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
    ): WiredTrades {
        val repository = JooqTradeRepository(dsl)
        val policy = TradeApprovalPolicy(fx, approvalThreshold, thresholdCurrency, checks)
        val builder = TradeBuilder(repository, referenceData, product.criteria, product.buckets, product.catalogue, product.futures, policy)
        val store = TradeStore(repository, events)
        val mirror = IntercompanyMirror(repository, referenceData, builder, policy, store, events)
        val lifecycle = TradeLifecycle(repository, store, actor, events, clock)
        return WiredTrades(
            TradeCapture(repository, referenceData, builder, policy, store, mirror, lifecycle, actor, events),
            TradeAmendment(builder, mirror, store, actor, events),
            lifecycle,
            TradeQueries(repository, actor),
        )
    }
}
