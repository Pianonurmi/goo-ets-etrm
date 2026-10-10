package com.pianonurmi.etrm.trade.adapter.jooq

import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.trade.adapter.jooq.generated.tables.Trade.TRADE
import com.pianonurmi.etrm.trade.adapter.jooq.generated.tables.TradeConfirmation.TRADE_CONFIRMATION
import com.pianonurmi.etrm.trade.api.ConfirmationDirection
import com.pianonurmi.etrm.trade.api.ConfirmationSource
import com.pianonurmi.etrm.trade.api.ConfirmationStatus
import com.pianonurmi.etrm.trade.api.ConfirmationTerms
import com.pianonurmi.etrm.trade.api.ConfirmationView
import com.pianonurmi.etrm.trade.api.FieldDifference
import com.pianonurmi.etrm.trade.domain.ConfirmationRepository
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import java.time.Instant
import java.util.UUID

@Repository
class JooqConfirmationRepository(private val dsl: DSLContext) : ConfirmationRepository {
    private val differencesType = object : TypeReference<List<FieldDifference>>() {}

    override fun insert(view: ConfirmationView) {
        dsl.insertInto(TRADE_CONFIRMATION)
            .set(TRADE_CONFIRMATION.ID, view.id)
            .set(TRADE_CONFIRMATION.TRADE_ID, view.tradeId)
            .set(TRADE_CONFIRMATION.TRADE_VERSION, view.tradeVersion)
            .set(TRADE_CONFIRMATION.DIRECTION, view.direction.name)
            .set(TRADE_CONFIRMATION.SOURCE, view.source.name)
            .set(TRADE_CONFIRMATION.REFERENCE, view.reference)
            .set(TRADE_CONFIRMATION.TERMS, JSONB.jsonb(Json.mapper.writeValueAsString(view.terms)))
            .set(TRADE_CONFIRMATION.STATUS, view.status.name)
            .set(TRADE_CONFIRMATION.DIFFERENCES, JSONB.jsonb(Json.mapper.writeValueAsString(view.differences)))
            .set(TRADE_CONFIRMATION.MATCHED_AGAINST, view.matchedAgainst)
            .set(TRADE_CONFIRMATION.DOCUMENT_ID, view.documentId)
            .set(TRADE_CONFIRMATION.CREATED_BY, view.createdBy)
            .set(TRADE_CONFIRMATION.CREATED_AT, view.createdAt)
            .execute()
    }

    override fun update(view: ConfirmationView) {
        dsl.update(TRADE_CONFIRMATION)
            .set(TRADE_CONFIRMATION.STATUS, view.status.name)
            .set(TRADE_CONFIRMATION.DIFFERENCES, JSONB.jsonb(Json.mapper.writeValueAsString(view.differences)))
            .set(TRADE_CONFIRMATION.MATCHED_AGAINST, view.matchedAgainst)
            .set(TRADE_CONFIRMATION.DOCUMENT_ID, view.documentId)
            .set(TRADE_CONFIRMATION.DECIDED_BY, view.decidedBy)
            .set(TRADE_CONFIRMATION.DECIDED_AT, if (view.decidedBy == null) null else Instant.now())
            .set(TRADE_CONFIRMATION.NOTE, view.note)
            .where(TRADE_CONFIRMATION.ID.eq(view.id))
            .execute()
    }

    override fun find(id: UUID, forUpdate: Boolean): ConfirmationView? {
        val query = select().where(TRADE_CONFIRMATION.ID.eq(id))
        return (if (forUpdate) query.forUpdate().of(TRADE_CONFIRMATION).fetchOne() else query.fetchOne())?.let(::toView)
    }

    override fun forTrade(tradeId: UUID): List<ConfirmationView> =
        select().where(TRADE_CONFIRMATION.TRADE_ID.eq(tradeId)).orderBy(TRADE_CONFIRMATION.CREATED_AT, TRADE_CONFIRMATION.ID).fetch(::toView)

    override fun theirs(tradeId: UUID, reference: String): ConfirmationView? = select()
        .where(
            TRADE_CONFIRMATION.TRADE_ID.eq(tradeId),
            TRADE_CONFIRMATION.DIRECTION.eq(ConfirmationDirection.THEIRS.name),
            TRADE_CONFIRMATION.REFERENCE.eq(reference),
        )
        .fetchOne(::toView)

    private fun select() = dsl.select(TRADE_CONFIRMATION.asterisk(), TRADE.TRADE_NUMBER)
        .from(TRADE_CONFIRMATION)
        .join(TRADE).on(TRADE.ID.eq(TRADE_CONFIRMATION.TRADE_ID))

    private fun toView(r: Record) = ConfirmationView(
        id = r.get(TRADE_CONFIRMATION.ID)!!,
        tradeId = r.get(TRADE_CONFIRMATION.TRADE_ID)!!,
        tradeNumber = r.get(TRADE.TRADE_NUMBER)!!,
        tradeVersion = r.get(TRADE_CONFIRMATION.TRADE_VERSION)!!,
        direction = ConfirmationDirection.valueOf(r.get(TRADE_CONFIRMATION.DIRECTION)!!),
        source = ConfirmationSource.valueOf(r.get(TRADE_CONFIRMATION.SOURCE)!!),
        reference = r.get(TRADE_CONFIRMATION.REFERENCE),
        terms = Json.mapper.readValue(r.get(TRADE_CONFIRMATION.TERMS)!!.data(), ConfirmationTerms::class.java),
        status = ConfirmationStatus.valueOf(r.get(TRADE_CONFIRMATION.STATUS)!!),
        differences = Json.mapper.readValue(r.get(TRADE_CONFIRMATION.DIFFERENCES)!!.data(), differencesType),
        matchedAgainst = r.get(TRADE_CONFIRMATION.MATCHED_AGAINST),
        documentId = r.get(TRADE_CONFIRMATION.DOCUMENT_ID),
        createdBy = r.get(TRADE_CONFIRMATION.CREATED_BY)!!,
        createdAt = r.get(TRADE_CONFIRMATION.CREATED_AT)!!,
        decidedBy = r.get(TRADE_CONFIRMATION.DECIDED_BY),
        note = r.get(TRADE_CONFIRMATION.NOTE),
    )
}
