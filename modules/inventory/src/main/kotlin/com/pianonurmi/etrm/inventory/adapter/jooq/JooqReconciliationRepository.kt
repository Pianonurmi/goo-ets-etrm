package com.pianonurmi.etrm.inventory.adapter.jooq

import com.pianonurmi.etrm.inventory.adapter.jooq.generated.tables.ReconciliationBreak.RECONCILIATION_BREAK
import com.pianonurmi.etrm.inventory.adapter.jooq.generated.tables.RegistryStatement.REGISTRY_STATEMENT
import com.pianonurmi.etrm.inventory.adapter.jooq.generated.tables.RegistryStatementLine.REGISTRY_STATEMENT_LINE
import com.pianonurmi.etrm.inventory.adapter.jooq.generated.tables.records.ReconciliationBreakRecord
import com.pianonurmi.etrm.inventory.api.BreakStatus
import com.pianonurmi.etrm.inventory.api.BreakType
import com.pianonurmi.etrm.inventory.api.CertificateRange
import com.pianonurmi.etrm.inventory.api.ReconciliationBreakView
import com.pianonurmi.etrm.inventory.api.StatementLine
import com.pianonurmi.etrm.inventory.api.StatementStatus
import com.pianonurmi.etrm.inventory.api.StatementView
import com.pianonurmi.etrm.inventory.domain.ReconciliationRepository
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.platform.time.LocalDateRange
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import java.time.Instant
import java.util.UUID

@Repository
class JooqReconciliationRepository(private val dsl: DSLContext) : ReconciliationRepository {
    private val mapType = object : TypeReference<Map<String, Any?>>() {}

    override fun insertStatement(view: StatementView, importedBy: String, lines: List<StatementLine>) {
        dsl.insertInto(REGISTRY_STATEMENT)
            .set(REGISTRY_STATEMENT.ID, view.id)
            .set(REGISTRY_STATEMENT.REGISTRY_CODE, view.registryCode)
            .set(REGISTRY_STATEMENT.REGISTRY_ACCOUNT_ID, view.registryAccountId)
            .set(REGISTRY_STATEMENT.STATEMENT_DATE, view.statementDate)
            .set(REGISTRY_STATEMENT.SOURCE, view.source)
            .set(REGISTRY_STATEMENT.IMPORTED_BY, importedBy)
            .set(REGISTRY_STATEMENT.LINE_COUNT, view.lineCount)
            .set(REGISTRY_STATEMENT.STATUS, view.status.name)
            .execute()
        if (lines.isEmpty()) return
        val insert = dsl.insertInto(
            REGISTRY_STATEMENT_LINE,
            REGISTRY_STATEMENT_LINE.ID,
            REGISTRY_STATEMENT_LINE.STATEMENT_ID,
            REGISTRY_STATEMENT_LINE.LINE_NO,
            REGISTRY_STATEMENT_LINE.ISSUANCE_REF,
            REGISTRY_STATEMENT_LINE.CERT_NO_START,
            REGISTRY_STATEMENT_LINE.CERT_NO_END,
            REGISTRY_STATEMENT_LINE.DEVICE_GSRN,
            REGISTRY_STATEMENT_LINE.PRODUCTION_START,
            REGISTRY_STATEMENT_LINE.PRODUCTION_END,
            REGISTRY_STATEMENT_LINE.RAW,
        )
        lines.forEach { l ->
            insert.values(
                Ids.newId(),
                view.id,
                l.lineNo,
                l.issuanceRef,
                l.range.start,
                l.range.end,
                l.deviceGsrn,
                l.productionPeriod?.start,
                l.productionPeriod?.endExclusive,
                JSONB.jsonb(Json.mapper.writeValueAsString(l.raw)),
            )
        }
        insert.execute()
    }

    override fun statement(id: UUID): StatementView? = dsl.selectFrom(REGISTRY_STATEMENT).where(REGISTRY_STATEMENT.ID.eq(id)).fetchOne()?.let { r ->
        StatementView(
            r.id,
            r.registryCode,
            r.registryAccountId,
            r.statementDate,
            r.source,
            r.lineCount,
            StatementStatus.valueOf(r.status),
            dsl.selectFrom(RECONCILIATION_BREAK).where(RECONCILIATION_BREAK.STATEMENT_ID.eq(id)).orderBy(RECONCILIATION_BREAK.DETECTED_AT).fetch(::toBreak),
        )
    }

    override fun lines(statementId: UUID): List<StatementLine> = dsl.selectFrom(REGISTRY_STATEMENT_LINE)
        .where(REGISTRY_STATEMENT_LINE.STATEMENT_ID.eq(statementId))
        .orderBy(REGISTRY_STATEMENT_LINE.LINE_NO)
        .fetch { r ->
            StatementLine(
                r.lineNo,
                r.issuanceRef,
                CertificateRange(r.certNoStart, r.certNoEnd),
                r.deviceGsrn,
                r.productionStart?.let { start -> r.productionEnd?.let { end -> LocalDateRange(start, end) } },
                Json.mapper.readValue(r.raw.data(), mapType),
            )
        }

    override fun insertBreak(view: ReconciliationBreakView) {
        dsl.insertInto(RECONCILIATION_BREAK)
            .set(RECONCILIATION_BREAK.ID, view.id)
            .set(RECONCILIATION_BREAK.STATEMENT_ID, view.statementId)
            .set(RECONCILIATION_BREAK.BREAK_TYPE, view.type.name)
            .set(RECONCILIATION_BREAK.ISSUANCE_REF, view.issuanceRef)
            .set(RECONCILIATION_BREAK.CERT_NO_START, view.range.start)
            .set(RECONCILIATION_BREAK.CERT_NO_END, view.range.end)
            .set(RECONCILIATION_BREAK.LOT_ID, view.lotId)
            .set(RECONCILIATION_BREAK.DETAILS, JSONB.jsonb(Json.mapper.writeValueAsString(view.details)))
            .set(RECONCILIATION_BREAK.STATUS, view.status.name)
            .execute()
    }

    override fun updateStatementStatus(id: UUID, status: StatementStatus) {
        dsl.update(REGISTRY_STATEMENT)
            .set(REGISTRY_STATEMENT.STATUS, status.name)
            .set(REGISTRY_STATEMENT.RECONCILED_AT, Instant.now())
            .where(REGISTRY_STATEMENT.ID.eq(id))
            .execute()
    }

    override fun findBreak(id: UUID): ReconciliationBreakView? = dsl.selectFrom(
        RECONCILIATION_BREAK,
    ).where(RECONCILIATION_BREAK.ID.eq(id)).fetchOne()?.let(::toBreak)

    override fun updateBreak(view: ReconciliationBreakView, resolvedBy: String) {
        dsl.update(RECONCILIATION_BREAK)
            .set(RECONCILIATION_BREAK.STATUS, view.status.name)
            .set(RECONCILIATION_BREAK.RESOLUTION, view.resolution)
            .set(RECONCILIATION_BREAK.RESOLVED_BY, resolvedBy)
            .set(RECONCILIATION_BREAK.RESOLVED_AT, Instant.now())
            .where(RECONCILIATION_BREAK.ID.eq(view.id))
            .execute()
    }

    override fun openBreaks(registryAccountId: UUID?): List<ReconciliationBreakView> = dsl.select(RECONCILIATION_BREAK.asterisk())
        .from(RECONCILIATION_BREAK)
        .join(REGISTRY_STATEMENT).on(REGISTRY_STATEMENT.ID.eq(RECONCILIATION_BREAK.STATEMENT_ID))
        .where(RECONCILIATION_BREAK.STATUS.eq(BreakStatus.OPEN.name))
        .and(registryAccountId?.let { REGISTRY_STATEMENT.REGISTRY_ACCOUNT_ID.eq(it) } ?: DSL.noCondition())
        .orderBy(RECONCILIATION_BREAK.DETECTED_AT)
        .fetch { toBreak(it.into(RECONCILIATION_BREAK)) }

    private fun toBreak(r: ReconciliationBreakRecord) = ReconciliationBreakView(
        r.id,
        r.statementId,
        BreakType.valueOf(r.breakType),
        r.issuanceRef,
        CertificateRange(r.certNoStart, r.certNoEnd),
        r.lotId,
        Json.mapper.readValue(r.details.data(), mapType),
        BreakStatus.valueOf(r.status),
        r.resolution,
    )
}
