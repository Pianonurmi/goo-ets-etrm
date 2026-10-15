package com.pianonurmi.etrm.inventory.adapter.jooq

import com.pianonurmi.etrm.inventory.adapter.jooq.generated.tables.Beneficiary.BENEFICIARY
import com.pianonurmi.etrm.inventory.adapter.jooq.generated.tables.CertificateLot.CERTIFICATE_LOT
import com.pianonurmi.etrm.inventory.adapter.jooq.generated.tables.records.BeneficiaryRecord
import com.pianonurmi.etrm.inventory.api.BeneficiaryView
import com.pianonurmi.etrm.inventory.domain.BeneficiaryRepository
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class JooqBeneficiaryRepository(private val dsl: DSLContext) : BeneficiaryRepository {
    override fun insert(id: UUID, name: String?, identifier: String?, recordedBy: String) {
        dsl.insertInto(BENEFICIARY)
            .set(BENEFICIARY.ID, id)
            .set(BENEFICIARY.NAME, name)
            .set(BENEFICIARY.IDENTIFIER, identifier)
            .set(BENEFICIARY.RECORDED_AT, DSL.currentInstant())
            .set(BENEFICIARY.RECORDED_BY, recordedBy)
            .execute()
    }

    override fun find(id: UUID, forUpdate: Boolean): BeneficiaryView? {
        val query = dsl.selectFrom(BENEFICIARY).where(BENEFICIARY.ID.eq(id))
        return (if (forUpdate) query.forUpdate().fetchOne() else query.fetchOne())?.let(::toView)
    }

    override fun requestErasure(id: UUID, requestedBy: String, reason: String, at: Instant) {
        dsl.update(BENEFICIARY)
            .set(BENEFICIARY.ERASURE_REQUESTED_AT, at)
            .set(BENEFICIARY.ERASURE_REQUESTED_BY, requestedBy)
            .set(BENEFICIARY.ERASURE_REASON, reason)
            .where(BENEFICIARY.ID.eq(id))
            .execute()
    }

    override fun withdrawErasure(id: UUID) {
        dsl.update(BENEFICIARY)
            .setNull(BENEFICIARY.ERASURE_REQUESTED_AT)
            .setNull(BENEFICIARY.ERASURE_REQUESTED_BY)
            .setNull(BENEFICIARY.ERASURE_REASON)
            .where(BENEFICIARY.ID.eq(id))
            .execute()
    }

    override fun erase(id: UUID, erasedBy: String, at: Instant) {
        dsl.update(BENEFICIARY)
            .setNull(BENEFICIARY.NAME)
            .setNull(BENEFICIARY.IDENTIFIER)
            .set(BENEFICIARY.ERASED_AT, at)
            .set(BENEFICIARY.ERASED_BY, erasedBy)
            .where(BENEFICIARY.ID.eq(id))
            .execute()
        // The lot's version history stores lots without the beneficiary (V7), so this update leaves no copy behind.
        dsl.update(CERTIFICATE_LOT)
            .set(CERTIFICATE_LOT.CANCELLATION, withoutNames(CERTIFICATE_LOT.CANCELLATION))
            .where(DSL.field("{0} ->> 'beneficiaryRef'", String::class.java, CERTIFICATE_LOT.CANCELLATION).eq(id.toString()))
            .execute()
    }

    private fun withoutNames(field: Field<JSONB?>) = DSL.field("{0} - 'beneficiaryName' - 'beneficiaryId'", JSONB::class.java, field)

    private fun toView(r: BeneficiaryRecord) = BeneficiaryView(
        id = r.id,
        name = r.name,
        identifier = r.identifier,
        recordedAt = r.recordedAt,
        recordedBy = r.recordedBy,
        erasureRequestedAt = r.erasureRequestedAt,
        erasureRequestedBy = r.erasureRequestedBy,
        erasureReason = r.erasureReason,
        erasedAt = r.erasedAt,
        erasedBy = r.erasedBy,
    )
}
