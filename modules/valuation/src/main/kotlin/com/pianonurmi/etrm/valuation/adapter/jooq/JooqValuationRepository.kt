package com.pianonurmi.etrm.valuation.adapter.jooq

import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.referencedata.api.BookType
import com.pianonurmi.etrm.valuation.adapter.jooq.generated.tables.PnlExplain.PNL_EXPLAIN
import com.pianonurmi.etrm.valuation.adapter.jooq.generated.tables.PortfolioExplain.PORTFOLIO_EXPLAIN
import com.pianonurmi.etrm.valuation.adapter.jooq.generated.tables.PortfolioValue.PORTFOLIO_VALUE
import com.pianonurmi.etrm.valuation.adapter.jooq.generated.tables.ValuationResult.VALUATION_RESULT
import com.pianonurmi.etrm.valuation.adapter.jooq.generated.tables.ValuationRun.VALUATION_RUN
import com.pianonurmi.etrm.valuation.adapter.jooq.generated.tables.records.PortfolioExplainRecord
import com.pianonurmi.etrm.valuation.adapter.jooq.generated.tables.records.ValuationResultRecord
import com.pianonurmi.etrm.valuation.adapter.jooq.generated.tables.records.ValuationRunRecord
import com.pianonurmi.etrm.valuation.api.ExplainRow
import com.pianonurmi.etrm.valuation.api.PortfolioBridgeView
import com.pianonurmi.etrm.valuation.api.PortfolioBucketRow
import com.pianonurmi.etrm.valuation.api.PortfolioCause
import com.pianonurmi.etrm.valuation.api.PortfolioExplainStep
import com.pianonurmi.etrm.valuation.api.PortfolioObligationRow
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
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Repository
class JooqValuationRepository(private val dsl: DSLContext) : ValuationRepository {
    private val mapType = object : TypeReference<Map<String, String>>() {}
    private val bucketsType = object : TypeReference<List<PortfolioBucketRow>>() {}
    private val obligationsType = object : TypeReference<List<PortfolioObligationRow>>() {}

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
                    .set(VALUATION_RESULT.CTD_PRICE_EUR, r.ctdPriceEur)
                    .set(VALUATION_RESULT.CTD_MTM_BASE, r.ctdMtmBase)
                    .set(VALUATION_RESULT.PAY_DATE, r.payDate)
                    .set(VALUATION_RESULT.DISCOUNT_FACTOR, r.discountFactor)
                    .set(VALUATION_RESULT.DISCOUNT_CURVE_CODE, r.discountCurveCode)
                    .set(VALUATION_RESULT.DISCOUNT_SNAPSHOT_ID, r.discountSnapshotId)
                    .set(VALUATION_RESULT.MTM_UNDISCOUNTED, r.mtmUndiscounted)
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

    override fun insertPortfolio(portfolio: PortfolioBridgeView) {
        dsl.insertInto(PORTFOLIO_VALUE)
            .set(PORTFOLIO_VALUE.RUN_ID, portfolio.runId)
            .set(PORTFOLIO_VALUE.LEGAL_ENTITY_ID, portfolio.legalEntityId)
            .set(PORTFOLIO_VALUE.VALUATION_DATE, portfolio.valuationDate)
            .set(PORTFOLIO_VALUE.CURRENCY, portfolio.currency)
            .set(PORTFOLIO_VALUE.HELD_MWH, portfolio.heldMwh)
            .set(PORTFOLIO_VALUE.INVENTORY_VALUE, portfolio.inventoryValue)
            .set(PORTFOLIO_VALUE.TRADE_MTM, portfolio.tradeMtm)
            .set(PORTFOLIO_VALUE.GIVE_AWAY, portfolio.giveAway)
            .set(PORTFOLIO_VALUE.TRANSFER_FEES, portfolio.transferFees)
            .set(PORTFOLIO_VALUE.EXPIRY_HAIRCUT, portfolio.expiryHaircut)
            .set(PORTFOLIO_VALUE.PORTFOLIO_VALUE_, portfolio.portfolioValue)
            .set(PORTFOLIO_VALUE.ALLOCATED_MWH, portfolio.allocatedMwh)
            .set(PORTFOLIO_VALUE.SHORTFALL_MWH, portfolio.shortfallMwh)
            .set(PORTFOLIO_VALUE.UNPRICED_MWH, portfolio.unpricedMwh)
            .set(PORTFOLIO_VALUE.STAMP, portfolio.stamp)
            .set(PORTFOLIO_VALUE.BUCKETS, JSONB.jsonb(Json.mapper.writeValueAsString(portfolio.buckets)))
            .set(PORTFOLIO_VALUE.OBLIGATIONS, JSONB.jsonb(Json.mapper.writeValueAsString(portfolio.obligations)))
            .set(PORTFOLIO_VALUE.COMPUTED_AT, portfolio.computedAt)
            .execute()
    }

    override fun portfolio(runId: UUID): PortfolioBridgeView? {
        val r = dsl.selectFrom(PORTFOLIO_VALUE).where(PORTFOLIO_VALUE.RUN_ID.eq(runId)).fetchOne() ?: return null
        val explain = dsl.selectFrom(PORTFOLIO_EXPLAIN).where(PORTFOLIO_EXPLAIN.RUN_ID.eq(runId)).fetch()
        return PortfolioBridgeView(
            runId = r.runId,
            legalEntityId = r.legalEntityId,
            valuationDate = r.valuationDate,
            currency = r.currency,
            heldMwh = r.heldMwh,
            inventoryValue = r.inventoryValue,
            tradeMtm = r.tradeMtm,
            giveAway = r.giveAway,
            transferFees = r.transferFees,
            expiryHaircut = r.expiryHaircut,
            portfolioValue = r.portfolioValue,
            allocatedMwh = r.allocatedMwh,
            shortfallMwh = r.shortfallMwh,
            unpricedMwh = r.unpricedMwh,
            stamp = r.stamp,
            computedAt = r.computedAt,
            buckets = Json.mapper.readValue(r.buckets.data(), bucketsType),
            obligations = Json.mapper.readValue(r.obligations.data(), obligationsType),
            priorRunId = explain.firstOrNull()?.priorRunId,
            explain = causes(explain),
        )
    }

    override fun insertPortfolioExplain(runId: UUID, priorRunId: UUID, causes: Map<PortfolioCause, BigDecimal>) {
        dsl.batch(
            causes.map { (cause, amount) ->
                dsl.insertInto(PORTFOLIO_EXPLAIN)
                    .set(PORTFOLIO_EXPLAIN.RUN_ID, runId)
                    .set(PORTFOLIO_EXPLAIN.PRIOR_RUN_ID, priorRunId)
                    .set(PORTFOLIO_EXPLAIN.CAUSE, cause.name)
                    .set(PORTFOLIO_EXPLAIN.AMOUNT, amount)
            },
        ).execute()
    }

    override fun portfolioExplains(legalEntityId: UUID, from: LocalDate, to: LocalDate): List<PortfolioExplainStep> = dsl
        .select(PORTFOLIO_VALUE.RUN_ID, PORTFOLIO_VALUE.VALUATION_DATE, PORTFOLIO_VALUE.PORTFOLIO_VALUE_)
        .from(PORTFOLIO_VALUE)
        .join(VALUATION_RUN).on(VALUATION_RUN.ID.eq(PORTFOLIO_VALUE.RUN_ID))
        .where(
            PORTFOLIO_VALUE.LEGAL_ENTITY_ID.eq(legalEntityId),
            PORTFOLIO_VALUE.VALUATION_DATE.gt(from),
            PORTFOLIO_VALUE.VALUATION_DATE.le(to),
            VALUATION_RUN.STATUS.eq(RunStatus.OFFICIAL.name),
        )
        .orderBy(PORTFOLIO_VALUE.VALUATION_DATE)
        .fetch()
        .mapNotNull { row ->
            val explain = dsl.selectFrom(PORTFOLIO_EXPLAIN).where(PORTFOLIO_EXPLAIN.RUN_ID.eq(row.value1())).fetch()
            val prior = explain.firstOrNull()?.priorRunId ?: return@mapNotNull null
            PortfolioExplainStep(row.value1(), prior, row.value2(), row.value3(), causes(explain))
        }

    private fun causes(rows: List<PortfolioExplainRecord>): Map<PortfolioCause, BigDecimal> =
        rows.map { PortfolioCause.valueOf(it.cause) to it.amount }.sortedBy { it.first.ordinal }.toMap()

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
        r.ctdPriceEur,
        r.ctdMtmBase,
        r.payDate,
        r.discountFactor,
        r.discountCurveCode,
        r.discountSnapshotId,
        r.mtmUndiscounted,
    )
}
