package com.pianonurmi.etrm.marketdata.application

import com.pianonurmi.etrm.marketdata.api.CreateCurve
import com.pianonurmi.etrm.marketdata.api.CurveCommand
import com.pianonurmi.etrm.marketdata.api.CurveKind
import com.pianonurmi.etrm.marketdata.api.CurvePointView
import com.pianonurmi.etrm.marketdata.api.CurvePrice
import com.pianonurmi.etrm.marketdata.api.CurveQuery
import com.pianonurmi.etrm.marketdata.api.CurveSnapshotRejected
import com.pianonurmi.etrm.marketdata.api.CurveSnapshotSignedOff
import com.pianonurmi.etrm.marketdata.api.CurveSnapshotSubmitted
import com.pianonurmi.etrm.marketdata.api.CurveSnapshotView
import com.pianonurmi.etrm.marketdata.api.CurveView
import com.pianonurmi.etrm.marketdata.api.GooMarks
import com.pianonurmi.etrm.marketdata.api.InstrumentKind
import com.pianonurmi.etrm.marketdata.api.PointOrigin
import com.pianonurmi.etrm.marketdata.api.SnapshotStatus
import com.pianonurmi.etrm.marketdata.domain.CurveBuilder
import com.pianonurmi.etrm.marketdata.domain.CurveMath
import com.pianonurmi.etrm.marketdata.domain.CurveRepository
import com.pianonurmi.etrm.marketdata.domain.InstrumentRepository
import com.pianonurmi.etrm.marketdata.domain.PillarInput
import com.pianonurmi.etrm.marketdata.domain.QuoteRepository
import com.pianonurmi.etrm.platform.crypto.HashChain
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.product.api.FuturesContractQuery
import com.pianonurmi.etrm.product.api.ProductBucketQuery
import com.pianonurmi.etrm.product.api.ProductCatalogueQuery
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

/**
 * Builds curve snapshots from prices, has them signed off by a second person, and reads official prices. Snapshots
 * are immutable: an override or a rebuild is a new version, and signing off a later version supersedes the earlier.
 */
@Service
@Transactional
class CurveService(
    private val curves: CurveRepository,
    private val instruments: InstrumentRepository,
    private val quotes: QuoteRepository,
    private val futures: FuturesContractQuery,
    private val catalogue: ProductCatalogueQuery,
    private val buckets: ProductBucketQuery,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) : CurveQuery, CurveCommand, GooMarks {
    override fun createCurve(command: CreateCurve): CurveView {
        actorProvider.current().requireRole(Role.MIDDLE_OFFICE, Role.RISK)
        val code = command.code.trim().uppercase()
        if (curves.curve(code) != null) throw ConflictException("Curve $code already exists")
        if (command.quoteTypes.isEmpty()) throw ValidationException("A curve needs at least one quote type")
        when (command.kind) {
            CurveKind.EUA_FORWARD -> {
                val product = command.allowanceProductCode ?: throw ValidationException("A forward curve needs allowanceProductCode")
                catalogue.allowanceProduct(product) ?: throw NotFoundException("AllowanceProduct", product)
            }
            CurveKind.GOO_VINTAGE -> buckets.requireBucket(command.bucketCode ?: throw ValidationException("A vintage curve needs bucketCode"))
        }
        command.proxyCurveCode?.let { proxy ->
            val p = curves.curve(proxy.uppercase()) ?: throw NotFoundException("Curve", proxy)
            if (p.kind != command.kind) throw ValidationException("A proxy curve must be of the same kind")
        }
        val curve = CurveView(
            code = code,
            kind = command.kind,
            description = command.description,
            allowanceProductCode = command.allowanceProductCode?.uppercase().takeIf { command.kind == CurveKind.EUA_FORWARD },
            bucketCode = command.bucketCode?.uppercase().takeIf { command.kind == CurveKind.GOO_VINTAGE },
            currency = Currency.getInstance(command.currency.uppercase()).currencyCode,
            quoteTypes = command.quoteTypes,
            stalenessDays = command.stalenessDays,
            proxyCurveCode = command.proxyCurveCode?.uppercase(),
            proxySpread = command.proxySpread,
            active = true,
        )
        curves.insertCurve(curve)
        return curve
    }

    override fun build(curveCode: String, asOfDate: LocalDate, note: String?): CurveSnapshotView {
        actorProvider.current().requireRole(Role.MIDDLE_OFFICE, Role.RISK, Role.SYSTEM)
        val curve = curves.curve(curveCode.uppercase()) ?: throw NotFoundException("Curve", curveCode)
        val proxy = curve.proxyCurveCode?.let { curves.official(it, null, asOfDate, null) }
        val result = CurveBuilder.build(
            asOf = asOfDate,
            quoteTypes = curve.quoteTypes,
            stalenessDays = curve.stalenessDays,
            pillars = pillars(curve, asOfDate),
            latest = { instrument, type -> quotes.latest(instrument, type, asOfDate, null) },
            proxy = proxy?.points.orEmpty(),
            proxySpread = curve.proxySpread,
        )
        if (result.points.isEmpty()) throw ValidationException("No prices for curve ${curve.code} on $asOfDate (missing: ${result.gaps.joinToString()})")
        return store(curve, asOfDate, result.points, result.gaps, note)
    }

    /** Futures of the product still to deliver (by delivery date) plus spot two working days out; or the bucket's vintages. */
    private fun pillars(curve: CurveView, asOf: LocalDate): List<PillarInput> = when (curve.kind) {
        CurveKind.EUA_FORWARD -> {
            val product = curve.allowanceProductCode!!
            val byContract = instruments.all(InstrumentKind.EUA_FUTURE, true).associateBy { it.futuresContractCode }
            val forwards = futures.futuresContracts(product, activeOnly = true)
                .filter { it.deliveryDate.isAfter(asOf) && it.currency == curve.currency }
                .mapNotNull { c -> byContract[c.code]?.let { PillarInput(c.code, c.deliveryDate, null, it.code) } }
            val spot = instruments.all(InstrumentKind.EUA_SPOT, true).firstOrNull { it.allowanceProductCode == product && it.currency == curve.currency }
                ?.let { PillarInput("SPOT", workingDaysAfter(asOf, 2), null, it.code) }
            listOfNotNull(spot) + forwards
        }
        CurveKind.GOO_VINTAGE -> instruments.all(InstrumentKind.GOO_VINTAGE, true)
            .filter { it.bucketCode == curve.bucketCode && it.currency == curve.currency }
            .map { PillarInput(it.productionYear.toString(), null, it.productionYear, it.code) }
    }

    private fun workingDaysAfter(date: LocalDate, days: Int): LocalDate {
        var d = date
        var left = days
        while (left > 0) {
            d = d.plusDays(1)
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) left--
        }
        return d
    }

    private fun store(curve: CurveView, asOf: LocalDate, points: List<CurvePointView>, gaps: List<String>, note: String?): CurveSnapshotView {
        val canonical = Json.canonical(
            mapOf(
                "curve" to curve.code,
                "asOf" to asOf.toString(),
                "points" to points.map {
                    listOf(it.pillar, it.pillarDate?.toString(), it.productionYear, it.price.setScale(6, RoundingMode.HALF_EVEN).toPlainString())
                },
            ),
        )
        val snapshot = CurveSnapshotView(
            id = Ids.newId(),
            curveCode = curve.code,
            kind = curve.kind,
            currency = curve.currency,
            asOfDate = asOf,
            version = curves.nextVersion(curve.code, asOf),
            status = SnapshotStatus.DRAFT,
            inputsHash = HashChain.next(null, canonical),
            gaps = gaps,
            note = note,
            builtBy = actorProvider.current().userId,
            builtAt = clock.instant(),
            signedOffBy = null,
            signedOffAt = null,
            supersededAt = null,
            points = points,
        )
        curves.insertSnapshot(snapshot)
        return snapshot
    }

    override fun override(snapshotId: UUID, pillar: String, price: BigDecimal, note: String): CurveSnapshotView {
        actorProvider.current().requireRole(Role.MIDDLE_OFFICE, Role.RISK)
        if (note.isBlank()) throw ValidationException("An override needs a reason")
        if (price.signum() <= 0) throw ValidationException("A price must be positive")
        val base = curves.snapshot(snapshotId) ?: throw NotFoundException("CurveSnapshot", snapshotId)
        val curve = curves.curve(base.curveCode)!!
        val existing = base.points.firstOrNull { it.pillar == pillar } ?: throw NotFoundException("CurvePillar", pillar)
        val points = base.points.map { if (it.pillar == pillar) it.copy(price = price, origin = PointOrigin.OVERRIDE, note = note) else it }
        return store(curve, base.asOfDate, points, base.gaps, "Override of ${existing.pillar} in version ${base.version}: $note")
    }

    override fun submit(snapshotId: UUID): CurveSnapshotView {
        actorProvider.current().requireRole(Role.MIDDLE_OFFICE, Role.RISK, Role.SYSTEM)
        val snapshot = curves.snapshot(snapshotId, lock = true) ?: throw NotFoundException("CurveSnapshot", snapshotId)
        if (snapshot.status != SnapshotStatus.DRAFT) throw ValidationException("Snapshot ${snapshot.id} is ${snapshot.status}")
        curves.setStatus(snapshotId, SnapshotStatus.PENDING_SIGN_OFF)
        val submitted = curves.snapshot(snapshotId)!!
        events.publishEvent(CurveSnapshotSubmitted(submitted))
        return submitted
    }

    /** Applied from the sign-off task: the snapshot becomes official for its date and supersedes the earlier one. */
    fun signOff(snapshotId: UUID, by: String): CurveSnapshotView {
        val snapshot = curves.snapshot(snapshotId, lock = true) ?: throw NotFoundException("CurveSnapshot", snapshotId)
        if (snapshot.status != SnapshotStatus.PENDING_SIGN_OFF) throw ValidationException("Snapshot ${snapshot.id} is ${snapshot.status}")
        if (snapshot.builtBy == by) throw SegregationOfDutiesException("A curve is signed off by someone other than who built it")
        val now = clock.instant()
        val previous = curves.official(snapshot.curveCode, snapshot.asOfDate, null, null)
        previous?.let { curves.supersede(it.id, now) }
        curves.setStatus(snapshotId, SnapshotStatus.OFFICIAL, by, now)
        val official = curves.snapshot(snapshotId)!!
        events.publishEvent(CurveSnapshotSignedOff(official, previous?.id))
        return official
    }

    fun reject(snapshotId: UUID, reason: String?): CurveSnapshotView {
        val snapshot = curves.snapshot(snapshotId, lock = true) ?: throw NotFoundException("CurveSnapshot", snapshotId)
        if (snapshot.status != SnapshotStatus.PENDING_SIGN_OFF) throw ValidationException("Snapshot ${snapshot.id} is ${snapshot.status}")
        curves.setStatus(snapshotId, SnapshotStatus.REJECTED)
        val rejected = curves.snapshot(snapshotId)!!
        events.publishEvent(CurveSnapshotRejected(rejected, reason))
        return rejected
    }

    // ----------------------------------------------------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    override fun curves(activeOnly: Boolean): List<CurveView> = curves.curves(activeOnly)

    @Transactional(readOnly = true)
    override fun curve(code: String): CurveView? = curves.curve(code.uppercase())

    @Transactional(readOnly = true)
    override fun snapshot(id: UUID): CurveSnapshotView? = curves.snapshot(id)

    @Transactional(readOnly = true)
    override fun snapshots(curveCode: String, asOfDate: LocalDate): List<CurveSnapshotView> = curves.snapshots(curveCode.uppercase(), asOfDate)

    @Transactional(readOnly = true)
    override fun official(curveCode: String, asOfDate: LocalDate, knownAt: Instant?): CurveSnapshotView? =
        curves.official(curveCode.uppercase(), asOfDate, null, knownAt)

    @Transactional(readOnly = true)
    override fun latestOfficial(curveCode: String, onOrBefore: LocalDate, knownAt: Instant?): CurveSnapshotView? =
        curves.official(curveCode.uppercase(), null, onOrBefore, knownAt)

    @Transactional(readOnly = true)
    override fun forwardPrice(allowanceProductCode: String, deliveryDate: LocalDate, asOfDate: LocalDate, knownAt: Instant?): CurvePrice? {
        val curve = curves.curves(true).firstOrNull {
            it.kind == CurveKind.EUA_FORWARD && it.allowanceProductCode == allowanceProductCode.uppercase()
        } ?: return null
        return latestOfficial(curve.code, asOfDate, knownAt)?.let { CurveMath.priceOn(it, deliveryDate) }
    }

    @Transactional(readOnly = true)
    override fun vintagePrice(bucketCode: String, productionYear: Int, asOf: LocalDate, knownAt: Instant?): CurvePrice? {
        val curve = curves.curves(true).firstOrNull { it.kind == CurveKind.GOO_VINTAGE && it.bucketCode == bucketCode.uppercase() } ?: return null
        return latestOfficial(curve.code, asOf, knownAt)?.let { CurveMath.priceFor(it, productionYear) }
    }

    /** Allocation marks are EUR/MWh; a curve in another currency gives no mark rather than a wrong one. */
    @Transactional(readOnly = true)
    override fun mark(bucketCode: String, productionYear: Int, asOf: LocalDate): BigDecimal? =
        vintagePrice(bucketCode, productionYear, asOf)?.takeIf { it.currency == "EUR" }?.value
}
