package com.pianonurmi.etrm.marketdata.adapter.jooq

import com.pianonurmi.etrm.marketdata.adapter.jooq.generated.tables.Curve.CURVE
import com.pianonurmi.etrm.marketdata.adapter.jooq.generated.tables.CurvePoint.CURVE_POINT
import com.pianonurmi.etrm.marketdata.adapter.jooq.generated.tables.CurveSnapshot.CURVE_SNAPSHOT
import com.pianonurmi.etrm.marketdata.adapter.jooq.generated.tables.records.CurveRecord
import com.pianonurmi.etrm.marketdata.adapter.jooq.generated.tables.records.CurveSnapshotRecord
import com.pianonurmi.etrm.marketdata.api.CurveKind
import com.pianonurmi.etrm.marketdata.api.CurvePointView
import com.pianonurmi.etrm.marketdata.api.CurveSnapshotView
import com.pianonurmi.etrm.marketdata.api.CurveView
import com.pianonurmi.etrm.marketdata.api.DayCount
import com.pianonurmi.etrm.marketdata.api.Interpolation
import com.pianonurmi.etrm.marketdata.api.PointOrigin
import com.pianonurmi.etrm.marketdata.api.QuoteType
import com.pianonurmi.etrm.marketdata.api.SnapshotStatus
import com.pianonurmi.etrm.marketdata.domain.CurveRepository
import com.pianonurmi.etrm.platform.json.Json
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Repository
class JooqCurveRepository(private val dsl: DSLContext) : CurveRepository {
    private val listType = object : TypeReference<List<String>>() {}

    override fun curves(activeOnly: Boolean): List<CurveView> = dsl.selectFrom(CURVE)
        .where(if (activeOnly) CURVE.ACTIVE.isTrue else DSL.noCondition())
        .orderBy(CURVE.CODE)
        .fetch(::toCurve)

    override fun curve(code: String): CurveView? = dsl.selectFrom(CURVE).where(CURVE.CODE.eq(code)).fetchOne()?.let(::toCurve)

    override fun insertCurve(curve: CurveView) {
        dsl.insertInto(CURVE)
            .set(CURVE.CODE, curve.code)
            .set(CURVE.KIND, curve.kind.name)
            .set(CURVE.DESCRIPTION, curve.description)
            .set(CURVE.ALLOWANCE_PRODUCT_CODE, curve.allowanceProductCode)
            .set(CURVE.BUCKET_CODE, curve.bucketCode)
            .set(CURVE.CURRENCY, curve.currency)
            .set(CURVE.QUOTE_TYPES, JSONB.jsonb(Json.mapper.writeValueAsString(curve.quoteTypes.map { it.name })))
            .set(CURVE.STALENESS_DAYS, curve.stalenessDays)
            .set(CURVE.PROXY_CURVE_CODE, curve.proxyCurveCode)
            .set(CURVE.PROXY_SPREAD, curve.proxySpread)
            .set(CURVE.ACTIVE, curve.active)
            .set(CURVE.DAY_COUNT, curve.dayCount?.name)
            .set(CURVE.INTERPOLATION, curve.interpolation.name)
            .execute()
    }

    override fun setInterpolation(code: String, interpolation: Interpolation) {
        dsl.update(CURVE).set(CURVE.INTERPOLATION, interpolation.name).where(CURVE.CODE.eq(code)).execute()
    }

    override fun nextVersion(curveCode: String, asOfDate: LocalDate): Int = (
        dsl.select(DSL.max(CURVE_SNAPSHOT.VERSION)).from(CURVE_SNAPSHOT)
            .where(CURVE_SNAPSHOT.CURVE_CODE.eq(curveCode), CURVE_SNAPSHOT.AS_OF_DATE.eq(asOfDate))
            .fetchOne(0, Int::class.java) ?: 0
    ) + 1

    override fun insertSnapshot(snapshot: CurveSnapshotView) {
        dsl.insertInto(CURVE_SNAPSHOT)
            .set(CURVE_SNAPSHOT.ID, snapshot.id)
            .set(CURVE_SNAPSHOT.CURVE_CODE, snapshot.curveCode)
            .set(CURVE_SNAPSHOT.AS_OF_DATE, snapshot.asOfDate)
            .set(CURVE_SNAPSHOT.VERSION, snapshot.version)
            .set(CURVE_SNAPSHOT.STATUS, snapshot.status.name)
            .set(CURVE_SNAPSHOT.INPUTS_HASH, snapshot.inputsHash)
            .set(CURVE_SNAPSHOT.GAPS, JSONB.jsonb(Json.mapper.writeValueAsString(snapshot.gaps)))
            .set(CURVE_SNAPSHOT.NOTE, snapshot.note)
            .set(CURVE_SNAPSHOT.BUILT_BY, snapshot.builtBy)
            .set(CURVE_SNAPSHOT.BUILT_AT, snapshot.builtAt)
            .set(CURVE_SNAPSHOT.INTERPOLATION, snapshot.interpolation.name)
            .execute()
        snapshot.points.forEach { p ->
            dsl.insertInto(CURVE_POINT)
                .set(CURVE_POINT.SNAPSHOT_ID, snapshot.id)
                .set(CURVE_POINT.PILLAR, p.pillar)
                .set(CURVE_POINT.PILLAR_DATE, p.pillarDate)
                .set(CURVE_POINT.PRODUCTION_YEAR, p.productionYear)
                .set(CURVE_POINT.PRICE, p.price)
                .set(CURVE_POINT.ORIGIN, p.origin.name)
                .set(CURVE_POINT.QUOTE_IDS, JSONB.jsonb(Json.mapper.writeValueAsString(p.quoteIds.map { it.toString() })))
                .set(CURVE_POINT.NOTE, p.note)
                .execute()
        }
    }

    override fun snapshot(id: UUID, lock: Boolean): CurveSnapshotView? = dsl.selectFrom(CURVE_SNAPSHOT)
        .where(CURVE_SNAPSHOT.ID.eq(id))
        .let { if (lock) it.forUpdate() else it }
        .fetchOne()?.let(::toSnapshot)

    override fun snapshots(curveCode: String, asOfDate: LocalDate): List<CurveSnapshotView> = dsl.selectFrom(CURVE_SNAPSHOT)
        .where(CURVE_SNAPSHOT.CURVE_CODE.eq(curveCode), CURVE_SNAPSHOT.AS_OF_DATE.eq(asOfDate))
        .orderBy(CURVE_SNAPSHOT.VERSION)
        .fetch(::toSnapshot)

    override fun setStatus(id: UUID, status: SnapshotStatus, signedOffBy: String?, at: Instant?) {
        val update = dsl.update(CURVE_SNAPSHOT).set(CURVE_SNAPSHOT.STATUS, status.name)
        if (signedOffBy != null) update.set(CURVE_SNAPSHOT.SIGNED_OFF_BY, signedOffBy).set(CURVE_SNAPSHOT.SIGNED_OFF_AT, at)
        update.where(CURVE_SNAPSHOT.ID.eq(id)).execute()
    }

    override fun supersede(id: UUID, at: Instant) {
        dsl.update(CURVE_SNAPSHOT)
            .set(CURVE_SNAPSHOT.STATUS, SnapshotStatus.SUPERSEDED.name)
            .set(CURVE_SNAPSHOT.SUPERSEDED_AT, at)
            .where(CURVE_SNAPSHOT.ID.eq(id))
            .execute()
    }

    override fun official(curveCode: String, asOfDate: LocalDate?, onOrBefore: LocalDate?, knownAt: Instant?): CurveSnapshotView? {
        val known: Condition = if (knownAt == null) {
            CURVE_SNAPSHOT.STATUS.eq(SnapshotStatus.OFFICIAL.name)
        } else {
            CURVE_SNAPSHOT.STATUS.`in`(SnapshotStatus.OFFICIAL.name, SnapshotStatus.SUPERSEDED.name)
                .and(CURVE_SNAPSHOT.SIGNED_OFF_AT.le(knownAt))
                .and(CURVE_SNAPSHOT.SUPERSEDED_AT.isNull.or(CURVE_SNAPSHOT.SUPERSEDED_AT.gt(knownAt)))
        }
        return dsl.selectFrom(CURVE_SNAPSHOT)
            .where(CURVE_SNAPSHOT.CURVE_CODE.eq(curveCode), known)
            .and(asOfDate?.let { CURVE_SNAPSHOT.AS_OF_DATE.eq(it) } ?: DSL.noCondition())
            .and(onOrBefore?.let { CURVE_SNAPSHOT.AS_OF_DATE.le(it) } ?: DSL.noCondition())
            .orderBy(CURVE_SNAPSHOT.AS_OF_DATE.desc(), CURVE_SNAPSHOT.SIGNED_OFF_AT.desc())
            .limit(1)
            .fetchOne()?.let(::toSnapshot)
    }

    private fun points(snapshotId: UUID): List<CurvePointView> = dsl.selectFrom(CURVE_POINT)
        .where(CURVE_POINT.SNAPSHOT_ID.eq(snapshotId))
        .orderBy(CURVE_POINT.PILLAR_DATE.asc().nullsLast(), CURVE_POINT.PRODUCTION_YEAR, CURVE_POINT.PILLAR)
        .fetch {
            CurvePointView(
                it.pillar,
                it.pillarDate,
                it.productionYear,
                it.price,
                PointOrigin.valueOf(it.origin),
                Json.mapper.readValue(it.quoteIds.data(), listType).map(UUID::fromString),
                it.note,
            )
        }

    private fun toCurve(r: CurveRecord) = CurveView(
        r.code,
        CurveKind.valueOf(r.kind),
        r.description,
        r.allowanceProductCode,
        r.bucketCode,
        r.currency,
        Json.mapper.readValue(r.quoteTypes.data(), listType).map(QuoteType::valueOf),
        r.stalenessDays,
        r.proxyCurveCode,
        r.proxySpread,
        r.active,
        r.dayCount?.let(DayCount::valueOf),
        Interpolation.valueOf(r.interpolation),
    )

    private fun toSnapshot(r: CurveSnapshotRecord): CurveSnapshotView {
        val curve = curve(r.curveCode)!!
        return CurveSnapshotView(
            r.id,
            r.curveCode,
            curve.kind,
            curve.currency,
            r.asOfDate,
            r.version,
            SnapshotStatus.valueOf(r.status),
            r.inputsHash,
            Json.mapper.readValue(r.gaps.data(), listType),
            r.note,
            r.builtBy,
            r.builtAt,
            r.signedOffBy,
            r.signedOffAt,
            r.supersededAt,
            points(r.id),
            curve.dayCount,
            Interpolation.valueOf(r.interpolation),
        )
    }
}
