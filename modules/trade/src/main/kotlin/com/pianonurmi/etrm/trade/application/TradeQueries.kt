package com.pianonurmi.etrm.trade.application

import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.trade.api.DeliveryObligationView
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeFilter
import com.pianonurmi.etrm.trade.api.TradePage
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.api.TradeSearch
import com.pianonurmi.etrm.trade.api.TradeVersionView
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.trade.domain.TradeRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Trades, their versions and open delivery obligations; the `visible` reads keep to the user's entities. */
@Service
@Transactional(readOnly = true)
class TradeQueries(private val repository: TradeRepository, private val actorProvider: ActorProvider) : TradeQuery {
    override fun trade(id: UUID): TradeView? = repository.find(id)

    override fun requireTrade(id: UUID): TradeView = repository.find(id) ?: throw NotFoundException("Trade", id)

    override fun byNumber(tradeNumber: String): TradeView? = repository.byNumber(tradeNumber)

    override fun trades(filter: TradeFilter): List<TradeView> = repository.list(filter)

    override fun visibleTrades(filter: TradeFilter): List<TradeView> {
        val actor = actorProvider.current()
        filter.legalEntityId?.let { actor.requireEntity(it) }
        return repository.list(filter.copy(legalEntityIds = actor.entityFilter))
    }

    override fun searchTrades(search: TradeSearch): TradePage {
        val actor = actorProvider.current()
        search.legalEntityId?.let { actor.requireEntity(it) }
        return repository.search(search.copy(legalEntityIds = actor.entityFilter))
    }

    override fun requireVisibleTrade(id: UUID): TradeView = requireTrade(id).also { actorProvider.current().requireEntity(it.legalEntityId) }

    override fun versions(tradeId: UUID): List<TradeVersionView> = repository.versions(tradeId)

    override fun openObligations(legalEntityId: UUID, side: Side?, productFamily: ProductFamily?): List<DeliveryObligationView> =
        repository.openObligations(legalEntityId, side, productFamily)

    override fun obligation(scheduleId: UUID): DeliveryObligationView? = repository.obligation(scheduleId)
}
