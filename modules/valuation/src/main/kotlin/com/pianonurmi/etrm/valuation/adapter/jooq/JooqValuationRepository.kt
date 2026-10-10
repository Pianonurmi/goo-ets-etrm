package com.pianonurmi.etrm.valuation.adapter.jooq

import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.referencedata.api.BookType
import com.pianonurmi.etrm.valuation.adapter.jooq.generated.tables.PnlExplain.PNL_EXPLAIN
import com.pianonurmi.etrm.valuation.adapter.jooq.generated.tables.ValuationResult.VALUATION_RESULT
import com.pianonurmi.etrm.valuation.adapter.jooq.generated.tables.ValuationRun.VALUATION_RUN
import com.pianonurmi.etrm.valuation.adapter.jooq.generated.tables.records.ValuationResultRecord
import com.pianonurmi.etrm.valuation.adapter.jooq.generated.tables.records.ValuationRunRecord
import com.pianonurmi.etrm.valuation.api.ExplainRow
import com.pianonurmi.etrm.valuation.api.ResultStatus
import com.pianonurmi.etrm.valuation.api.RunKind
import com.pianonurmi.etrm.valuation.api.RunStatus
import com.pianonurmi.etrm.valuation.api.ValuationResultView
import com.pianonurmi.etrm.valuation.api.ValuationRunView
import com.pianonurmi.etrm.valuation.domain.ValuationRepository
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Repository
class JooqValuationRepository(private val dsl: DSLContext) : ValuationRepository {
    private val mapType = object : TypeReference<Map<String, String>>() {}

    override fun insert(run: ValuationRunView, results: List<ValuationResultView>) {
        dsl.insertInto(VALUATION_RUN)
            .set(VALUATION_RUN.ID, run.id)
            .set(VALUATION_RUN.LEGAL_ENTITY_ID, run.legalEntityId)
            .set(VALUATION_RUN.VALUATION_DATE, run.valuationDate)
            .set(VALUATION_RUN.KIND, run.kind.name)
            .set(VALUATION_RUN.STATUS, run.status.name)
            .set(VALUATION_RUN.BASE_CURRENCY, run.baseCurrency)
            .set(VALUATION_RUN.TOTAL_MTM_BASE, run.totalMtmBase)
            .set(VALUATION_RUN.EXCEPTIONS, run.exceptions)
            .set(VALUATION_RUN.INPUTS, JSONB.jsonb(Json.mapper.writeValueAsString(run.inputs)))
            .set(VALUATION_RUN.NOTE, run.note)
            .set(VALUATION_RUN.CREATED_BY, run.createdBy)
            .set(VALUATION_RUN.CREATED_AT, run.createdAt)
            .execute()
        if (results.isEmpty()) return
        dsl.batch(
            results.map { r ->
                dsl.insertInto(VALUATION_RESULT)
                    .set(VALUATION_RESULT.RUN_ID, run.id)
                    .set(VALUATION_RESULT.SCHEDULE_ID, r.scheduleId)
                    .set(VALUATION_RESULT.TRADE_ID, r.tradeId)
                    .set(VALUATION_RESULT.TRADE_NUMBER, r.tradeNumber)
                    .set(VALUATION_RESULT.TRADE_VERSION, r.tradeVersion)
                    .set(VALUATION_RESULT.LEG_NO, r.legNo)
                    .set(VALUATION_RESULT.BOOK_ID, r.bookId)
                    .set(VALUATION_RESULT.BOOK_TYPE, r.bookType.name)
                    .set(VALUATION_RESULT.COUNTERPARTY_ID, r.counterpartyId)
                    .set(VALUATION_RESULT.PRODUCT_FAMILY, r.productFamily.name)
                    .set(VALUATION_RESULT.PRODUCT_KEY, r.productKey)
                    .set(VALUATION_RESULT.PRODUCTION_YEAR, r.productionYear)
                    .set(VALUATION_RESULT.DELIVERY_DATE, r.deliveryDate)
                    .set(VALUATION_RESULT.QUANTITY, r.quantity)
                    .set(VALUATION_RESULT.CONTRACT_PRICE, r.contractPrice)
                    .set(VALUATION_RESULT.CONTRACT_CURRENCY, r.contractCurrency)
                    .set(VALUATION_RESULT.MARKET_PRICE, r.marketPrice)
                    .set(VALUATION_RESULT.MARKET_CURRENCY, r.marketCurrency)
                    .set(VALUATION_RESULT.PRICE_ORIGIN, r.priceOrigin)
                    .set(VALUATION_RESULT.CURVE_CODE, r.curveCode)
                    .set(VALUATION_RESULT.SNAPSHOT_ID, r.snapshotId)
                    .set(VALUATION_RESULT.CURVE_DATE, r.curveDate)
                    .set(VALUATION_RESULT.MARKET_PRICE_CONTRACT, r.marketPriceContract)
                    .set(VALUATION_RESULT.FX_TO_BASE, r.fxToBase)
                    .set(VALUATION_RESULT.MTM, r.mtm)
                    .set(VALUATION_RESULT.MTM_BASE, r.mtmBase)
                    .set(VALUATION_RESULT.STATUS, r.status.name)
                    .set(VALUATION_RESULT.NOTE, r.note)
            },
        ).execute()
    }

    override fun run(id: UUID, lock: Boolean): ValuationRunView? = dsl.selectFrom(VALUATION_RUN)
        .where(VALUATION_RUN.ID.eq(id))
        .let { if (lock) it.forUpdate() else it }
        .fetchOne()?.let(::toRun)

    override fun runs(legalEntityId: UUID, from: LocalDate, to: LocalDate): List<ValuationRunView> = dsl.selectFrom(VALUATION_RUN)
        .where(VALUATION_RUN.LEGAL_ENTITY_ID.eq(legalEntityId), VALUATION_RUN.VALUATION_DATE.between(from, to))
        .orderBy(VALUATION_RUN.VALUATION_DATE, VALUATION_RUN.CREATED_AT)
        .fetch(::toRun)

    override fun results(runId: UUID, bookId: UUID?): List<ValuationResultView> = dsl.selectFrom(VALUATION_RESULT)
        .where(VALUATION_RESULT.RUN_ID.eq(runId))
        .and(bookId?.let { VALUATION_RESULT.BOOK_ID.eq(it) } ?: DSL.noCondition())
        .orderBy(VALUATION_RESULT.TRADE_NUMBER, VALUATION_RESULT.LEG_NO, VALUATION_RESULT.DELIVERY_DATE)
        .fetch(::toResult)

    override fun official(legalEntityId: UUID, date: LocalDate, orEarlier: Boolean, before: Boolean): ValuationRunView? = dsl.selectFrom(VALUATION_RUN)
        .where(VALUATION_RUN.LEGAL_ENTITY_ID.eq(legalEntityId), VALUATION_RUN.STATUS.eq(RunStatus.OFFICIAL.name))
        .and(
            when {
                before -> VALUATION_RUN.VALUATION_DATE.lt(date)
                orEarlier -> VALUATION_RUN.VALUATION_DATE.le(date)
                else -> VALUATION_RUN.VALUATION_DATE.eq(date)
            },
        )
        .orderBy(VALUATION_RUN.VALUATION_DATE.desc())
        .limit(1)
        .fetchOne()?.let(::toRun)

    override fun setStatus(id: UUID, status: RunStatus, signedOffBy: String?, at: Instant?, note: String?) {
        val update = dsl.update(VALUATION_RUN).set(VALUATION_RUN.STATUS, status.name)
        if (signedOffBy != null) {
            update.set(
                VALUATION_RUN.SIGNED_OFF_BY,
                signedOffBy,
            ).set(VALUATION_RUN.SIGNED_OFF_AT, at).set(VALUATION_RUN.SIGN_OFF_NOTE, note)
        }
        update.where(VALUATION_RUN.ID.eq(id)).execute()
    }

    override fun supersede(id: UUID, at: Instant) {
        dsl.update(VALUATION_RUN)
            .set(VALUATION_RUN.STATUS, RunStatus.SUPERSEDED.name)
            .set(VALUATION_RUN.SUPERSEDED_AT, at)
            .where(VALUATION_RUN.ID.eq(id))
            .execute()
    }

    override fun insertExplain(runId: UUID, priorRunId: UUID?, rows: List<ExplainRow>) {
        if (rows.isEmpty()) return
        dsl.batch(
            rows.map { r ->
                dsl.insertInto(PNL_EXPLAIN)
                    .set(PNL_EXPLAIN.RUN_ID, runId)
                    .set(PNL_EXPLAIN.PRIOR_RUN_ID, priorRunId)
                    .set(PNL_EXPLAIN.SCHEDULE_ID, r.scheduleId)
                    .set(PNL_EXPLAIN.TRADE_ID, r.tradeId)
                    .set(PNL_EXPLAIN.BOOK_ID, r.bookId)
                    .set(PNL_EXPLAIN.COMPONENT, r.component.name)
                    .set(PNL_EXPLAIN.AMOUNT_BASE, r.amountBase)
            },
        ).execute()
    }

    private fun toRun(r: ValuationRunRecord) = ValuationRunView(
        r.id,
        r.legalEntityId,
        r.valuationDate,
        RunKind.valueOf(r.kind),
        RunStatus.valueOf(r.status),
        r.baseCurrency,
        r.totalMtmBase,
        r.exceptions,
        Json.mapper.readValue(r.inputs.data(), mapType),
        r.note,
        r.createdBy,
        r.createdAt,
        r.signedOffBy,
        r.signedOffAt,
        r.signOffNote,
        r.supersededAt,
    )

    private fun toResult(r: ValuationResultRecord) = ValuationResultView(
        r.runId,
        r.scheduleId,
        r.tradeId,
        r.tradeNumber,
        r.tradeVersion,
        r.legNo,
        r.bookId,
        BookType.valueOf(r.bookType),
        r.counterpartyId,
        ProductFamily.valueOf(r.productFamily),
        r.productKey,
        r.productionYear,
        r.deliveryDate,
        r.quantity,
        r.contractPrice,
        r.contractCurrency,
        r.marketPrice,
        r.marketCurrency,
        r.priceOrigin,
        r.curveCode,
        r.snapshotId,
        r.curveDate,
        r.marketPriceContract,
        r.fxToBase,
        r.mtm,
        r.mtmBase,
        ResultStatus.valueOf(r.status),
        r.note,
    )
}
