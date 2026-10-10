package com.pianonurmi.etrm.trade.adapter.jooq

import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.trade.adapter.jooq.generated.Sequences.TRADE_NUMBER_SEQ
import com.pianonurmi.etrm.trade.adapter.jooq.generated.tables.DeliveryRecord.DELIVERY_RECORD
import com.pianonurmi.etrm.trade.adapter.jooq.generated.tables.DeliverySchedule.DELIVERY_SCHEDULE
import com.pianonurmi.etrm.trade.adapter.jooq.generated.tables.Trade.TRADE
import com.pianonurmi.etrm.trade.adapter.jooq.generated.tables.TradeLeg.TRADE_LEG
import com.pianonurmi.etrm.trade.adapter.jooq.generated.tables.TradeVersion.TRADE_VERSION
import com.pianonurmi.etrm.trade.api.DeliveryObligationView
import com.pianonurmi.etrm.trade.api.DeliveryStatus
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeFilter
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeVersionView
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.trade.domain.TradeRepository
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Currency
import java.util.UUID
import org.jooq.postgres.extensions.types.LocalDateRange as PgLocalDateRange

/**
 * Trades are stored as a header row, a full JSON snapshot per version, and current-state projections of legs and
 * delivery schedules for querying. The JSON snapshot is the record of truth for a version; projections are rebuilt
 * from it on every save.
 */
@Repository
class JooqTradeRepository(private val dsl: DSLContext) : TradeRepository {
    override fun nextTradeNumber(year: Int): String = "T-$year-%06d".format(dsl.nextval(TRADE_NUMBER_SEQ))

    override fun find(id: UUID): TradeView? = currentSnapshot(id, lock = false)

    override fun lock(id: UUID): TradeView? = currentSnapshot(id, lock = true)

    override fun byNumber(tradeNumber: String): TradeView? =
        dsl.select(TRADE.ID).from(TRADE).where(TRADE.TRADE_NUMBER.eq(tradeNumber)).fetchOne(TRADE.ID)?.let { find(it) }

    override fun list(filter: TradeFilter): List<TradeView> = dsl.select(TRADE.ID)
        .from(TRADE)
        .where(
            DSL.and(
                listOfNotNull(
                    filter.legalEntityId?.let { TRADE.LEGAL_ENTITY_ID.eq(it) },
                    filter.bookId?.let { TRADE.BOOK_ID.eq(it) },
                    filter.counterpartyId?.let { TRADE.COUNTERPARTY_ID.eq(it) },
                    filter.statuses?.let { TRADE.STATUS.`in`(it.map(TradeStatus::name)) },
                    filter.productFamily?.let { TRADE.PRODUCT_FAMILY.eq(it.name) },
                    filter.tradeDateFrom?.let { TRADE.TRADE_DATE.ge(it) },
                    filter.tradeDateTo?.let { TRADE.TRADE_DATE.lt(it) },
                ),
            ),
        )
        .orderBy(TRADE.TRADE_DATE.desc(), TRADE.TRADE_NUMBER.desc())
        .limit(filter.limit)
        .fetch(TRADE.ID)
        .mapNotNull { find(it) }

    override fun insert(trade: TradeView, recordedBy: String, reason: String?) {
        dsl.insertInto(TRADE)
            .set(TRADE.ID, trade.id)
            .set(TRADE.TRADE_NUMBER, trade.tradeNumber)
            .set(TRADE.LEGAL_ENTITY_ID, trade.legalEntityId)
            .set(TRADE.BOOK_ID, trade.bookId)
            .set(TRADE.COUNTERPARTY_ID, trade.counterpartyId)
            .set(TRADE.PRODUCT_FAMILY, trade.productFamily.name)
            .set(TRADE.INSTRUMENT_TYPE, trade.instrumentType.name)
            .set(TRADE.TRADE_DATE, trade.tradeDate)
            .set(TRADE.TRADER, trade.trader)
            .set(TRADE.BROKER_ID, trade.brokerId)
            .set(TRADE.MASTER_AGREEMENT_REF, trade.masterAgreementRef)
            .set(TRADE.EXTERNAL_REF, trade.externalRef)
            .set(TRADE.STATUS, trade.status.name)
            .set(TRADE.CURRENT_VERSION, trade.version)
            .set(TRADE.CREATED_BY, recordedBy)
            .execute()
        writeVersion(trade, recordedBy, reason)
        writeProjections(trade)
    }

    override fun saveVersion(trade: TradeView, recordedBy: String, reason: String?): Boolean {
        val updated = dsl.update(TRADE)
            .set(TRADE.TRADE_DATE, trade.tradeDate)
            .set(TRADE.BROKER_ID, trade.brokerId)
            .set(TRADE.MASTER_AGREEMENT_REF, trade.masterAgreementRef)
            .set(TRADE.EXTERNAL_REF, trade.externalRef)
            .set(TRADE.STATUS, trade.status.name)
            .set(TRADE.CURRENT_VERSION, trade.version)
            .set(TRADE.INTERCOMPANY_LINK_ID, trade.intercompanyLinkId)
            .set(TRADE.APPROVED_BY, trade.approvedBy)
            .set(TRADE.APPROVED_AT, trade.approvedAt)
            .set(TRADE.CONFIRMED_AT, trade.confirmedAt)
            .set(TRADE.CONFIRMATION_REF, trade.confirmationRef)
            .set(TRADE.UPDATED_AT, Instant.now())
            .where(TRADE.ID.eq(trade.id), TRADE.CURRENT_VERSION.eq(trade.version - 1))
            .execute()
        if (updated != 1) return false
        dsl.update(TRADE_VERSION)
            .set(TRADE_VERSION.SUPERSEDED_AT, DSL.currentInstant())
            .where(TRADE_VERSION.TRADE_ID.eq(trade.id), TRADE_VERSION.SUPERSEDED_AT.isNull)
            .execute()
        writeVersion(trade, recordedBy, reason)
        // Projections removed by an amendment go first (never ones with delivery records: the aggregate forbids amending those).
        dsl.deleteFrom(DELIVERY_SCHEDULE)
            .where(DELIVERY_SCHEDULE.TRADE_ID.eq(trade.id), DELIVERY_SCHEDULE.ID.notIn(trade.legs.flatMap { leg -> leg.deliveries.map { it.id } }))
            .execute()
        dsl.deleteFrom(TRADE_LEG).where(TRADE_LEG.TRADE_ID.eq(trade.id), TRADE_LEG.ID.notIn(trade.legs.map { it.id })).execute()
        writeProjections(trade)
        return true
    }

    override fun versions(tradeId: UUID): List<TradeVersionView> = dsl.selectFrom(TRADE_VERSION)
        .where(TRADE_VERSION.TRADE_ID.eq(tradeId))
        .orderBy(TRADE_VERSION.VERSION)
        .fetch { r ->
            TradeVersionView(
                r.version,
                r.recordedAt,
                r.supersededAt,
                r.recordedBy,
                r.reason,
                TradeStatus.valueOf(r.status),
                Json.mapper.readValue(r.data.data(), TradeView::class.java),
            )
        }

    override fun appendDeliveryRecord(
        scheduleId: UUID,
        quantity: BigDecimal,
        deliveredOn: LocalDate,
        reference: String?,
        idempotencyKey: String?,
        recordedBy: String,
    ): UUID {
        val id = Ids.newId()
        dsl.insertInto(DELIVERY_RECORD)
            .set(DELIVERY_RECORD.ID, id)
            .set(DELIVERY_RECORD.IDEMPOTENCY_KEY, idempotencyKey)
            .set(DELIVERY_RECORD.SCHEDULE_ID, scheduleId)
            .set(DELIVERY_RECORD.QUANTITY, quantity)
            .set(DELIVERY_RECORD.DELIVERED_ON, deliveredOn)
            .set(DELIVERY_RECORD.REFERENCE, reference)
            .set(DELIVERY_RECORD.RECORDED_BY, recordedBy)
            .execute()
        return id
    }

    override fun deliveryRecorded(idempotencyKey: String): Boolean =
        dsl.fetchExists(DELIVERY_RECORD, DELIVERY_RECORD.IDEMPOTENCY_KEY.eq(idempotencyKey))

    override fun openObligations(legalEntityId: UUID, side: Side?, productFamily: ProductFamily?): List<DeliveryObligationView> = dsl
        .select(
            DELIVERY_SCHEDULE.asterisk(),
            TRADE_LEG.asterisk(),
            TRADE.TRADE_NUMBER,
            TRADE.LEGAL_ENTITY_ID,
            TRADE.BOOK_ID,
            TRADE.COUNTERPARTY_ID,
            TRADE.PRODUCT_FAMILY,
        )
        .from(DELIVERY_SCHEDULE)
        .join(TRADE_LEG).on(TRADE_LEG.ID.eq(DELIVERY_SCHEDULE.LEG_ID))
        .join(TRADE).on(TRADE.ID.eq(DELIVERY_SCHEDULE.TRADE_ID))
        .where(
            TRADE.LEGAL_ENTITY_ID.eq(legalEntityId),
            TRADE.STATUS.`in`(TradeStatus.entries.filter { it.firm }.map { it.name }),
            DELIVERY_SCHEDULE.STATUS.`in`(DeliveryStatus.PENDING.name, DeliveryStatus.PARTIALLY_DELIVERED.name),
            side?.let { TRADE_LEG.SIDE.eq(it.name) } ?: DSL.noCondition(),
            productFamily?.let { TRADE.PRODUCT_FAMILY.eq(it.name) } ?: DSL.noCondition(),
        )
        .orderBy(DELIVERY_SCHEDULE.DELIVERY_DATE, TRADE.TRADE_NUMBER)
        .fetch(::toObligation)

    override fun obligation(scheduleId: UUID): DeliveryObligationView? = dsl
        .select(
            DELIVERY_SCHEDULE.asterisk(),
            TRADE_LEG.asterisk(),
            TRADE.TRADE_NUMBER,
            TRADE.LEGAL_ENTITY_ID,
            TRADE.BOOK_ID,
            TRADE.COUNTERPARTY_ID,
            TRADE.PRODUCT_FAMILY,
        )
        .from(DELIVERY_SCHEDULE)
        .join(TRADE_LEG).on(TRADE_LEG.ID.eq(DELIVERY_SCHEDULE.LEG_ID))
        .join(TRADE).on(TRADE.ID.eq(DELIVERY_SCHEDULE.TRADE_ID))
        .where(DELIVERY_SCHEDULE.ID.eq(scheduleId))
        .fetchOne(::toObligation)

    // ----------------------------------------------------------------------------------------------------------

    private fun currentSnapshot(id: UUID, lock: Boolean): TradeView? {
        val header = dsl.selectFrom(TRADE).where(TRADE.ID.eq(id)).let { if (lock) it.forUpdate() else it }.fetchOne() ?: return null
        val json = dsl.select(TRADE_VERSION.DATA)
            .from(TRADE_VERSION)
            .where(TRADE_VERSION.TRADE_ID.eq(id), TRADE_VERSION.VERSION.eq(header.currentVersion))
            .fetchOne(TRADE_VERSION.DATA) ?: return null
        return Json.mapper.readValue(json.data(), TradeView::class.java)
    }

    private fun writeVersion(trade: TradeView, recordedBy: String, reason: String?) {
        dsl.insertInto(TRADE_VERSION)
            .set(TRADE_VERSION.ID, Ids.newId())
            .set(TRADE_VERSION.TRADE_ID, trade.id)
            .set(TRADE_VERSION.VERSION, trade.version)
            .set(TRADE_VERSION.RECORDED_BY, recordedBy)
            .set(TRADE_VERSION.REASON, reason)
            .set(TRADE_VERSION.STATUS, trade.status.name)
            .set(TRADE_VERSION.DATA, JSONB.jsonb(Json.mapper.writeValueAsString(trade)))
            .execute()
    }

    private fun writeProjections(trade: TradeView) {
        trade.legs.forEach { leg ->
            dsl.insertInto(TRADE_LEG)
                .set(TRADE_LEG.ID, leg.id)
                .set(TRADE_LEG.TRADE_ID, trade.id)
                .set(TRADE_LEG.LEG_NO, leg.legNo)
                .set(TRADE_LEG.SIDE, leg.side.name)
                .set(TRADE_LEG.ENERGY_CARRIER, leg.energyCarrier?.name)
                .set(TRADE_LEG.ALLOWANCE_PRODUCT_CODE, leg.allowanceProductCode)
                .set(TRADE_LEG.CRITERIA_ID, leg.criteriaId)
                .set(TRADE_LEG.CRITERIA_SUMMARY, leg.criteriaSummary)
                .set(TRADE_LEG.BUCKET_CODE, leg.bucketCode)
                .set(TRADE_LEG.QUANTITY, leg.quantity)
                .set(TRADE_LEG.UNIT, leg.unit.name)
                .set(TRADE_LEG.PRICE, leg.price)
                .set(TRADE_LEG.PRICE_CURRENCY, leg.priceCurrency.currencyCode)
                .set(TRADE_LEG.PRICE_TYPE, leg.priceType.name)
                .set(TRADE_LEG.PRICE_INDEX, leg.priceIndex)
                .set(TRADE_LEG.DELIVERY_ACCOUNT_ID, leg.deliveryAccountId)
                .set(TRADE_LEG.COUNTERPARTY_ACCOUNT_REF, leg.counterpartyAccountRef)
                .onConflict(TRADE_LEG.ID)
                .doUpdate()
                .set(TRADE_LEG.CRITERIA_ID, leg.criteriaId)
                .set(TRADE_LEG.CRITERIA_SUMMARY, leg.criteriaSummary)
                .set(TRADE_LEG.BUCKET_CODE, leg.bucketCode)
                .set(TRADE_LEG.QUANTITY, leg.quantity)
                .set(TRADE_LEG.PRICE, leg.price)
                .set(TRADE_LEG.PRICE_CURRENCY, leg.priceCurrency.currencyCode)
                .set(TRADE_LEG.PRICE_TYPE, leg.priceType.name)
                .set(TRADE_LEG.PRICE_INDEX, leg.priceIndex)
                .set(TRADE_LEG.DELIVERY_ACCOUNT_ID, leg.deliveryAccountId)
                .set(TRADE_LEG.COUNTERPARTY_ACCOUNT_REF, leg.counterpartyAccountRef)
                .execute()
            leg.deliveries.forEach { d ->
                dsl.insertInto(DELIVERY_SCHEDULE)
                    .set(DELIVERY_SCHEDULE.ID, d.id)
                    .set(DELIVERY_SCHEDULE.TRADE_ID, trade.id)
                    .set(DELIVERY_SCHEDULE.LEG_ID, leg.id)
                    .set(DELIVERY_SCHEDULE.SCHEDULE_NO, d.scheduleNo)
                    .set(DELIVERY_SCHEDULE.DELIVERY_WINDOW, PgLocalDateRange.localDateRange(d.deliveryWindow.start, d.deliveryWindow.endExclusive))
                    .set(DELIVERY_SCHEDULE.DELIVERY_DATE, d.deliveryDate)
                    .set(DELIVERY_SCHEDULE.QUANTITY, d.quantity)
                    .set(DELIVERY_SCHEDULE.DELIVERED_QUANTITY, d.deliveredQuantity)
                    .set(DELIVERY_SCHEDULE.PAYMENT_DATE, d.paymentDate)
                    .set(DELIVERY_SCHEDULE.STATUS, d.status.name)
                    .onConflict(DELIVERY_SCHEDULE.ID)
                    .doUpdate()
                    .set(DELIVERY_SCHEDULE.DELIVERY_WINDOW, PgLocalDateRange.localDateRange(d.deliveryWindow.start, d.deliveryWindow.endExclusive))
                    .set(DELIVERY_SCHEDULE.DELIVERY_DATE, d.deliveryDate)
                    .set(DELIVERY_SCHEDULE.QUANTITY, d.quantity)
                    .set(DELIVERY_SCHEDULE.DELIVERED_QUANTITY, d.deliveredQuantity)
                    .set(DELIVERY_SCHEDULE.PAYMENT_DATE, d.paymentDate)
                    .set(DELIVERY_SCHEDULE.STATUS, d.status.name)
                    .execute()
            }
        }
    }

    private fun toObligation(r: org.jooq.Record): DeliveryObligationView {
        val s = r.into(DELIVERY_SCHEDULE)
        val l = r.into(TRADE_LEG)
        val window = s.deliveryWindow
        return DeliveryObligationView(
            scheduleId = s.id,
            tradeId = s.tradeId,
            tradeNumber = r.get(TRADE.TRADE_NUMBER),
            legId = l.id,
            legalEntityId = r.get(TRADE.LEGAL_ENTITY_ID),
            bookId = r.get(TRADE.BOOK_ID),
            counterpartyId = r.get(TRADE.COUNTERPARTY_ID),
            side = Side.valueOf(l.side),
            productFamily = ProductFamily.valueOf(r.get(TRADE.PRODUCT_FAMILY)),
            energyCarrier = l.energyCarrier?.let { com.pianonurmi.etrm.product.api.EnergyCarrier.valueOf(it) },
            allowanceProductCode = l.allowanceProductCode,
            criteriaId = l.criteriaId,
            criterion = null,
            quantity = s.quantity,
            deliveredQuantity = s.deliveredQuantity,
            deliveryWindow = LocalDateRange(window.lower()!!, window.upper()!!),
            deliveryDate = s.deliveryDate,
            deliveryAccountId = l.deliveryAccountId,
            counterpartyAccountRef = l.counterpartyAccountRef,
            price = l.price,
            priceCurrency = Currency.getInstance(l.priceCurrency),
            priceType = com.pianonurmi.etrm.trade.api.PriceType.valueOf(l.priceType),
            status = DeliveryStatus.valueOf(s.status),
        )
    }
}
