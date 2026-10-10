package com.pianonurmi.etrm.position.adapter.jooq

import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.position.adapter.jooq.generated.tables.ComplianceInput.COMPLIANCE_INPUT
import com.pianonurmi.etrm.position.adapter.jooq.generated.tables.PositionSnapshot.POSITION_SNAPSHOT
import com.pianonurmi.etrm.position.api.PositionSnapshotView
import com.pianonurmi.etrm.position.api.SnapshotKind
import com.pianonurmi.etrm.position.api.UpsertComplianceInput
import com.pianonurmi.etrm.position.application.PositionRepository
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Repository
class JooqPositionRepository(private val dsl: DSLContext) : PositionRepository {
    private val mapType = object : TypeReference<Map<String, Any?>>() {}

    override fun complianceInput(legalEntityId: UUID, complianceYear: Int): UpsertComplianceInput? = dsl.selectFrom(COMPLIANCE_INPUT)
        .where(COMPLIANCE_INPUT.LEGAL_ENTITY_ID.eq(legalEntityId), COMPLIANCE_INPUT.COMPLIANCE_YEAR.eq(complianceYear))
        .fetchOne {
            UpsertComplianceInput(it.legalEntityId, it.complianceYear, it.verifiedEmissionsTco2e, it.forecastEmissionsTco2e, it.freeAllocationExpected)
        }

    override fun upsertComplianceInput(input: UpsertComplianceInput, actor: String) {
        dsl.insertInto(COMPLIANCE_INPUT)
            .set(COMPLIANCE_INPUT.LEGAL_ENTITY_ID, input.legalEntityId)
            .set(COMPLIANCE_INPUT.COMPLIANCE_YEAR, input.complianceYear)
            .set(COMPLIANCE_INPUT.VERIFIED_EMISSIONS_TCO2E, input.verifiedEmissionsTco2e)
            .set(COMPLIANCE_INPUT.FORECAST_EMISSIONS_TCO2E, input.forecastEmissionsTco2e)
            .set(COMPLIANCE_INPUT.FREE_ALLOCATION_EXPECTED, input.freeAllocationExpected)
            .set(COMPLIANCE_INPUT.UPDATED_BY, actor)
            .onConflict(COMPLIANCE_INPUT.LEGAL_ENTITY_ID, COMPLIANCE_INPUT.COMPLIANCE_YEAR)
            .doUpdate()
            .set(COMPLIANCE_INPUT.VERIFIED_EMISSIONS_TCO2E, input.verifiedEmissionsTco2e)
            .set(COMPLIANCE_INPUT.FORECAST_EMISSIONS_TCO2E, input.forecastEmissionsTco2e)
            .set(COMPLIANCE_INPUT.FREE_ALLOCATION_EXPECTED, input.freeAllocationExpected)
            .set(COMPLIANCE_INPUT.UPDATED_BY, actor)
            .set(COMPLIANCE_INPUT.UPDATED_AT, Instant.now())
            .execute()
    }

    override fun snapshot(legalEntityId: UUID, kind: SnapshotKind, asOfDate: LocalDate): PositionSnapshotView? = dsl.selectFrom(POSITION_SNAPSHOT)
        .where(POSITION_SNAPSHOT.LEGAL_ENTITY_ID.eq(legalEntityId), POSITION_SNAPSHOT.KIND.eq(kind.name), POSITION_SNAPSHOT.AS_OF_DATE.eq(asOfDate))
        .fetchOne {
            PositionSnapshotView(
                it.id,
                it.legalEntityId,
                SnapshotKind.valueOf(it.kind),
                it.asOfDate,
                it.takenAt,
                Json.mapper.readValue(it.data.data(), mapType),
            )
        }

    override fun snapshots(legalEntityId: UUID, kind: SnapshotKind, from: LocalDate, to: LocalDate): List<PositionSnapshotView> = dsl.selectFrom(
        POSITION_SNAPSHOT,
    )
        .where(POSITION_SNAPSHOT.LEGAL_ENTITY_ID.eq(legalEntityId), POSITION_SNAPSHOT.KIND.eq(kind.name), POSITION_SNAPSHOT.AS_OF_DATE.between(from, to))
        .orderBy(POSITION_SNAPSHOT.AS_OF_DATE)
        .fetch {
            PositionSnapshotView(
                it.id,
                it.legalEntityId,
                SnapshotKind.valueOf(it.kind),
                it.asOfDate,
                it.takenAt,
                Json.mapper.readValue(it.data.data(), mapType),
            )
        }

    override fun upsertSnapshot(snapshot: PositionSnapshotView) {
        val data = JSONB.jsonb(Json.mapper.writeValueAsString(snapshot.data))
        dsl.insertInto(POSITION_SNAPSHOT)
            .set(POSITION_SNAPSHOT.ID, snapshot.id)
            .set(POSITION_SNAPSHOT.LEGAL_ENTITY_ID, snapshot.legalEntityId)
            .set(POSITION_SNAPSHOT.KIND, snapshot.kind.name)
            .set(POSITION_SNAPSHOT.AS_OF_DATE, snapshot.asOfDate)
            .set(POSITION_SNAPSHOT.TAKEN_AT, snapshot.takenAt)
            .set(POSITION_SNAPSHOT.DATA, data)
            .onConflict(POSITION_SNAPSHOT.LEGAL_ENTITY_ID, POSITION_SNAPSHOT.KIND, POSITION_SNAPSHOT.AS_OF_DATE)
            .doUpdate()
            .set(POSITION_SNAPSHOT.TAKEN_AT, snapshot.takenAt)
            .set(POSITION_SNAPSHOT.DATA, data)
            .execute()
    }
}
