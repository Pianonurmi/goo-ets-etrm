package com.pianonurmi.etrm.inventory.adapter.jooq

import com.pianonurmi.etrm.inventory.adapter.jooq.generated.tables.CertificateLot.CERTIFICATE_LOT
import com.pianonurmi.etrm.inventory.adapter.jooq.generated.tables.CertificateLotVersion.CERTIFICATE_LOT_VERSION
import com.pianonurmi.etrm.inventory.adapter.jooq.generated.tables.records.CertificateLotRecord
import com.pianonurmi.etrm.inventory.api.AcquisitionSource
import com.pianonurmi.etrm.inventory.api.CancellationDetails
import com.pianonurmi.etrm.inventory.api.CertificateRange
import com.pianonurmi.etrm.inventory.api.LotFilter
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.LotView
import com.pianonurmi.etrm.inventory.domain.LotChange
import com.pianonurmi.etrm.inventory.domain.LotRepository
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.product.api.EnergyCarrier
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.jooq.postgres.extensions.types.Ltree
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.jooq.postgres.extensions.types.LocalDateRange as PgLocalDateRange

@Repository
class JooqLotRepository(private val dsl: DSLContext) : LotRepository {
    private val mapType = object : TypeReference<Map<String, Any?>>() {}

    override fun find(id: UUID): LotView? = dsl.selectFrom(CERTIFICATE_LOT).where(CERTIFICATE_LOT.ID.eq(id)).fetchOne()?.let(::toView)

    override fun lock(id: UUID): LotView? = dsl.selectFrom(CERTIFICATE_LOT).where(CERTIFICATE_LOT.ID.eq(id)).forUpdate().fetchOne()?.let(::toView)

    override fun insert(lot: LotView, change: LotChange) {
        dsl.insertInto(CERTIFICATE_LOT)
            .set(record(lot))
            .set(CERTIFICATE_LOT.VERSION, lot.version)
            .set(CERTIFICATE_LOT.CREATED_SEQ, change.seq)
            .set(CERTIFICATE_LOT.UPDATED_SEQ, change.seq)
            .set(CERTIFICATE_LOT.EFFECTIVE_AT, change.effectiveAt)
            .set(CERTIFICATE_LOT.UPDATED_BY, change.actor)
            .execute()
    }

    override fun update(lot: LotView, change: LotChange): Boolean = dsl.update(CERTIFICATE_LOT)
        .set(record(lot))
        .set(CERTIFICATE_LOT.VERSION, lot.version)
        .set(CERTIFICATE_LOT.UPDATED_SEQ, change.seq)
        .set(CERTIFICATE_LOT.EFFECTIVE_AT, change.effectiveAt)
        .set(CERTIFICATE_LOT.UPDATED_BY, change.actor)
        .set(CERTIFICATE_LOT.UPDATED_AT, Instant.now())
        .where(CERTIFICATE_LOT.ID.eq(lot.id), CERTIFICATE_LOT.VERSION.eq(lot.version - 1))
        .execute() == 1

    override fun list(filter: LotFilter): List<LotView> = dsl.selectFrom(CERTIFICATE_LOT)
        .where(
            DSL.and(
                listOfNotNull(
                    filter.holderEntityId?.let { CERTIFICATE_LOT.HOLDER_ENTITY_ID.eq(it) },
                    filter.registryAccountId?.let { CERTIFICATE_LOT.REGISTRY_ACCOUNT_ID.eq(it) },
                    filter.statuses?.let { CERTIFICATE_LOT.STATUS.`in`(it.map(LotStatus::name)) },
                    filter.deviceGsrn?.let { CERTIFICATE_LOT.DEVICE_GSRN.eq(it) },
                    filter.expiringBefore?.let { CERTIFICATE_LOT.EXPIRY_DATE.lt(it) },
                    filter.reservationRef?.let { CERTIFICATE_LOT.RESERVATION_REF.eq(it) },
                ),
            ),
        )
        .orderBy(CERTIFICATE_LOT.EXPIRY_DATE, CERTIFICATE_LOT.ISSUANCE_REF, CERTIFICATE_LOT.CERT_NO_START)
        .limit(filter.limit)
        .fetch(::toView)

    override fun search(holderEntityId: UUID, condition: Condition, statuses: Set<LotStatus>, limit: Int): List<LotView> = dsl.selectFrom(CERTIFICATE_LOT)
        .where(CERTIFICATE_LOT.HOLDER_ENTITY_ID.eq(holderEntityId), CERTIFICATE_LOT.STATUS.`in`(statuses.map(LotStatus::name)), condition)
        .orderBy(CERTIFICATE_LOT.EXPIRY_DATE, CERTIFICATE_LOT.ID)
        .limit(limit)
        .fetch(::toView)

    override fun live(holderEntityId: UUID): List<LotView> = dsl.selectFrom(CERTIFICATE_LOT)
        .where(CERTIFICATE_LOT.HOLDER_ENTITY_ID.eq(holderEntityId), CERTIFICATE_LOT.STATUS.`in`(LotStatus.LIVE.map(LotStatus::name)))
        .orderBy(CERTIFICATE_LOT.ID)
        .fetch(::toView)

    override fun liveInAccount(registryAccountId: UUID): List<LotView> = dsl.selectFrom(CERTIFICATE_LOT)
        .where(CERTIFICATE_LOT.REGISTRY_ACCOUNT_ID.eq(registryAccountId), CERTIFICATE_LOT.STATUS.`in`(LotStatus.LIVE.map(LotStatus::name)))
        .fetch(::toView)

    override fun dueForExpiry(asOf: LocalDate): List<UUID> = dsl.select(CERTIFICATE_LOT.ID)
        .from(CERTIFICATE_LOT)
        .where(CERTIFICATE_LOT.STATUS.`in`(LotStatus.HELD.name, LotStatus.RESERVED.name), CERTIFICATE_LOT.EXPIRY_DATE.le(asOf))
        .orderBy(CERTIFICATE_LOT.ID)
        .fetch(CERTIFICATE_LOT.ID)

    override fun asOf(lotId: UUID, effectiveAt: Instant, knownAt: Instant): Map<String, Any?>? = dsl.select(CERTIFICATE_LOT_VERSION.DATA)
        .from(CERTIFICATE_LOT_VERSION)
        .where(CERTIFICATE_LOT_VERSION.LOT_ID.eq(lotId))
        .and(DSL.condition("{0} @> {1}::timestamptz", CERTIFICATE_LOT_VERSION.SYS_PERIOD, DSL.`val`(knownAt.toString())))
        .and(DSL.condition("{0} @> {1}::timestamptz", CERTIFICATE_LOT_VERSION.VALID_PERIOD, DSL.`val`(effectiveAt.toString())))
        .orderBy(CERTIFICATE_LOT_VERSION.VERSION.desc())
        .limit(1)
        .fetchOne(CERTIFICATE_LOT_VERSION.DATA)
        ?.let { Json.mapper.readValue(it.data(), mapType) }

    override fun expiryLadder(holderEntityId: UUID, from: LocalDate, toExclusive: LocalDate): Map<LocalDate, Long> {
        val month = DSL.field("date_trunc('month', {0})::date", LocalDate::class.java, CERTIFICATE_LOT.EXPIRY_DATE)
        val qty = DSL.sum(CERTIFICATE_LOT.QUANTITY_MWH)
        return dsl.select(month, qty)
            .from(CERTIFICATE_LOT)
            .where(
                CERTIFICATE_LOT.HOLDER_ENTITY_ID.eq(holderEntityId),
                CERTIFICATE_LOT.STATUS.`in`(LotStatus.HELD.name, LotStatus.RESERVED.name),
                CERTIFICATE_LOT.EXPIRY_DATE.ge(from),
                CERTIFICATE_LOT.EXPIRY_DATE.lt(toExclusive),
            )
            .groupBy(month)
            .orderBy(month)
            .fetch()
            .associate { it.get(month)!! to it.get(qty)!!.toLong() }
    }

    private fun record(lot: LotView): CertificateLotRecord = CertificateLotRecord().apply {
        id = lot.id
        holderEntityId = lot.holderEntityId
        registryAccountId = lot.registryAccountId
        registryCode = lot.registryCode
        issuanceRef = lot.issuanceRef
        certNoStart = lot.range.start
        certNoEnd = lot.range.end
        energyCarrier = lot.energyCarrier.name
        certificateScheme = lot.certificateScheme
        issuingBody = lot.issuingBody
        issuingCountry = lot.issuingCountry
        deviceId = lot.deviceId
        deviceGsrn = lot.deviceGsrn
        deviceName = lot.deviceName
        deviceCountry = lot.deviceCountry
        deviceRegion = lot.deviceRegion
        deviceCommissioningDate = lot.deviceCommissioningDate
        deviceCapacityKw = lot.deviceCapacityKw
        energySourceCode = lot.energySourceCode
        energySourcePath = Ltree.ltree(lot.energySourcePath)
        technologyCode = lot.technologyCode
        technologyPath = lot.technologyPath?.let { Ltree.ltree(it) }
        productionPeriod = PgLocalDateRange.localDateRange(lot.productionPeriod.start, lot.productionPeriod.endExclusive)
        issueDate = lot.issueDate
        expiryDate = lot.expiryDate
        supportProduction = lot.supportProduction
        supportInvestment = lot.supportInvestment
        supportSchemeCode = lot.supportSchemeCode
        labels = lot.labels.sorted().toTypedArray()
        status = lot.status.name
        rootLotId = lot.rootLotId
        parentLotId = lot.parentLotId
        acquisitionSource = lot.acquisitionSource.name
        acquisitionRef = lot.acquisitionRef
        acquisitionDate = lot.acquisitionDate
        costBasisPerMwh = lot.costBasisPerMwh
        costCurrency = lot.costCurrency
        reservationRef = lot.reservationRef
        instructionRef = lot.instructionRef
        attributes = JSONB.jsonb(Json.mapper.writeValueAsString(lot.attributes))
        cancellation = lot.cancellation?.let { JSONB.jsonb(Json.mapper.writeValueAsString(it)) }
    }

    private fun toView(r: CertificateLotRecord): LotView {
        val period = r.productionPeriod
        return LotView(
            id = r.id,
            holderEntityId = r.holderEntityId,
            registryAccountId = r.registryAccountId,
            registryCode = r.registryCode,
            issuanceRef = r.issuanceRef,
            range = CertificateRange(r.certNoStart, r.certNoEnd),
            quantityMwh = r.quantityMwh ?: (r.certNoEnd - r.certNoStart + java.math.BigInteger.ONE).longValueExact(),
            energyCarrier = EnergyCarrier.valueOf(r.energyCarrier),
            certificateScheme = r.certificateScheme,
            issuingBody = r.issuingBody,
            issuingCountry = r.issuingCountry,
            deviceId = r.deviceId,
            deviceGsrn = r.deviceGsrn,
            deviceName = r.deviceName,
            deviceCountry = r.deviceCountry,
            deviceRegion = r.deviceRegion,
            deviceCommissioningDate = r.deviceCommissioningDate,
            deviceCapacityKw = r.deviceCapacityKw,
            energySourceCode = r.energySourceCode,
            energySourcePath = r.energySourcePath.data(),
            technologyCode = r.technologyCode,
            technologyPath = r.technologyPath?.data(),
            productionPeriod = LocalDateRange(period.lower()!!, period.upper()!!),
            issueDate = r.issueDate,
            expiryDate = r.expiryDate,
            supportProduction = r.supportProduction,
            supportInvestment = r.supportInvestment,
            supportSchemeCode = r.supportSchemeCode,
            labels = r.labels.filterNotNull().toSet(),
            status = LotStatus.valueOf(r.status),
            rootLotId = r.rootLotId,
            parentLotId = r.parentLotId,
            acquisitionSource = AcquisitionSource.valueOf(r.acquisitionSource),
            acquisitionRef = r.acquisitionRef,
            acquisitionDate = r.acquisitionDate,
            costBasisPerMwh = r.costBasisPerMwh,
            costCurrency = r.costCurrency,
            reservationRef = r.reservationRef,
            instructionRef = r.instructionRef,
            attributes = Json.mapper.readValue(r.attributes.data(), mapType),
            cancellation = r.cancellation?.let { Json.mapper.readValue(it.data(), CancellationDetails::class.java) },
            version = r.version,
        )
    }
}
