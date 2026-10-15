package com.pianonurmi.etrm.trade.application

import com.pianonurmi.etrm.platform.errors.DomainException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.trade.api.AmendTrade
import com.pianonurmi.etrm.trade.api.CaptureTrade
import com.pianonurmi.etrm.trade.api.TradeCaptured
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.trade.domain.TradeAggregate
import com.pianonurmi.etrm.trade.domain.TradeRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The other side of an intercompany trade, booked in the counterparty's legal entity with the sides turned round. It
 * is captured with the original, and changes and cancels with it or not at all.
 */
@Component
class IntercompanyMirror(
    private val repository: TradeRepository,
    private val referenceData: ReferenceDataQuery,
    private val builder: TradeBuilder,
    private val policy: TradeApprovalPolicy,
    private val store: TradeStore,
    private val events: ApplicationEventPublisher,
) {
    fun capture(command: CaptureTrade, original: TradeView, mirrorEntityId: UUID, actor: String) {
        val mirrorBook = command.mirrorBookId ?: throw ValidationException("Intercompany trades require mirrorBookId in the counterparty legal entity")
        if (referenceData.requireBook(mirrorBook).legalEntityId != mirrorEntityId) {
            throw ValidationException("mirrorBookId must belong to legal entity $mirrorEntityId")
        }
        val mirrorCounterparty = referenceData.counterparties().firstOrNull { it.linkedLegalEntityId == command.legalEntityId }
            ?: throw ValidationException("No intercompany counterparty record represents legal entity ${command.legalEntityId}")
        val mirrorCommand = command.copy(
            legalEntityId = mirrorEntityId,
            bookId = mirrorBook,
            counterpartyId = mirrorCounterparty.id,
            // The mirror delivers to (or receives from) the original entity's own account, from the account the original
            // names for its counterparty (held by the mirror's entity), when it names a registered one.
            legs = command.legs.mapIndexed { index, leg ->
                leg.copy(
                    side = leg.side.opposite(),
                    deliveryAccountId = original.legs[index].counterpartyAccountId,
                    counterpartyAccountId = leg.deliveryAccountId,
                    counterpartyAccountRef = null,
                )
            },
            mirrorBookId = null,
        )
        // One transaction, one UTI: the mirror reports the original's identifier.
        val mirror = builder.build(mirrorCommand, actor).let {
            it.copy(intercompanyLinkId = original.id, regulatory = it.regulatory.copy(uti = original.regulatory.uti, utiSource = original.regulatory.utiSource))
        }
        repository.insert(mirror, actor, "intercompany mirror of ${original.tradeNumber}")
        val submitted = TradeAggregate(mirror).submitForApproval(policy.requiresApproval(mirror))
        repository.saveVersion(submitted, actor, "submitted")
        val linked = original.copy(intercompanyLinkId = mirror.id, version = original.version + 1)
        repository.saveVersion(linked, actor, "linked to ${mirror.tradeNumber}")
        events.publishEvent(TradeCaptured(submitted))
        store.openObligationsIfDue(submitted)
    }

    /**
     * The mirror of an intercompany trade after the same amendment, with the sides turned round. The source's delivery
     * account is the mirror's counterparty account, and the source's counterparty account (held by the mirror's entity)
     * the mirror's delivery account: an account the amendment changed on [amended] follows to the mirror, one it left as
     * it was keeps the mirror's own value. A leg added by the amendment takes the source's accounts, as at capture. It is
     * worked out like any amendment and, like the mirror at capture, without pre-trade checks. When the mirror cannot take
     * the change (it has deliveries, an option leg decided, or is in a state that forbids it), the one-sided change is
     * refused.
     */
    fun amended(source: TradeView, amended: TradeView, mirror: TradeView, command: AmendTrade): TradeView = try {
        val mirrorCommand = command.copy(
            expectedVersion = mirror.version,
            legs = command.legs?.mapIndexed { index, leg ->
                val own = mirror.legs.getOrNull(index)
                val before = source.legs.getOrNull(index)
                val after = amended.legs[index]
                val deliveryKept = own != null && before != null && after.deliveryAccountId == before.deliveryAccountId
                val counterpartyKept = own != null && before != null && after.counterpartyAccountId == before.counterpartyAccountId
                leg.copy(
                    side = leg.side.opposite(),
                    deliveryAccountId = if (counterpartyKept) own.deliveryAccountId else after.counterpartyAccountId,
                    counterpartyAccountId = if (deliveryKept) own.counterpartyAccountId else after.deliveryAccountId,
                    counterpartyAccountRef = if (deliveryKept) own.counterpartyAccountRef else null,
                )
            },
        )
        TradeAggregate(mirror).amend(builder.amendedState(mirror, mirrorCommand, runChecks = false))
    } catch (e: DomainException) {
        throw ValidationException(
            "Trade ${source.tradeNumber} cannot be amended on its own: its intercompany mirror ${mirror.tradeNumber} refuses the change (${e.message})",
        )
    }

    fun cancelled(source: TradeView, mirror: TradeView): TradeView = try {
        TradeAggregate(mirror).cancel()
    } catch (e: DomainException) {
        throw ValidationException(
            "Trade ${source.tradeNumber} cannot be cancelled on its own: its intercompany mirror ${mirror.tradeNumber} refuses it (${e.message})",
        )
    }
}
