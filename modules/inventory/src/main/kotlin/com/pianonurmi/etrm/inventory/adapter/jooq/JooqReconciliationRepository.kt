package com.pianonurmi.etrm.inventory.adapter.jooq

import com.pianonurmi.etrm.inventory.adapter.jooq.generated.tables.AllowanceStatementLine.ALLOWANCE_STATEMENT_LINE
import com.pianonurmi.etrm.inventory.adapter.jooq.generated.tables.ReconciliationBreak.RECONCILIATION_BREAK
import com.pianonurmi.etrm.inventory.adapter.jooq.generated.tables.ReconciliationBreakSighting.RECONCILIATION_BREAK_SIGHTING
import com.pianonurmi.etrm.inventory.adapter.jooq.generated.tables.RegistryStatement.REGISTRY_STATEMENT
import com.pianonurmi.etrm.inventory.adapter.jooq.generated.tables.RegistryStatementLine.REGISTRY_STATEMENT_LINE
import com.pianonurmi.etrm.inventory.adapter.jooq.generated.tables.records.ReconciliationBreakRecord
import com.pianonurmi.etrm.inventory.api.AllowanceStatementLine
import com.pianonurmi.etrm.inventory.api.BreakAction
import com.pianonurmi.etrm.inventory.api.BreakAgeing
import com.pianonurmi.etrm.inventory.api.BreakFilter
import com.pianonurmi.etrm.inventory.api.BreakStatus
import com.pianonurmi.etrm.inventory.api.BreakType
import com.pianonurmi.etrm.inventory.api.CertificateRange
import com.pianonurmi.etrm.inventory.api.ReconciliationBreakView
import com.pianonurmi.etrm.inventory.api.StatementKind
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
import java.time.LocalDate
import java.util.UUID

@Repository
class JooqReconciliationRepository(private val dsl: DSLContext) : ReconciliationRepository {
    private val mapType = object : TypeReference<Map<String, Any?>>() {}

    override fun insertStatement(view: StatementView, importedBy: String, lines: List<StatementLine>) {
        insertHeader(view, importedBy)
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

    override fun insertAllowanceStatement(view: StatementView, importedBy: String, lines: List<AllowanceStatementLine>) {
        insertHeader(view, importedBy)
        lines.forEach { l ->
            dsl.insertInto(ALLOWANCE_STATEMENT_LINE)
                .set(ALLOWANCE_STATEMENT_LINE.ID, Ids.newId())
                .set(ALLOWANCE_STATEMENT_LINE.STATEMENT_ID, view.id)
                .set(ALLOWANCE_STATEMENT_LINE.LINE_NO, l.lineNo)
                .set(ALLOWANCE_STATEMENT_LINE.PRODUCT_CODE, l.productCode)
                .set(ALLOWANCE_STATEMENT_LINE.QUANTITY, l.quantity)
                .set(ALLOWANCE_STATEMENT_LINE.RAW, JSONB.jsonb(Json.mapper.writeValueAsString(l.raw)))
                .execute()
        }
    }

    override fun allowanceLines(statementId: UUID): List<AllowanceStatementLine> = dsl.selectFrom(ALLOWANCE_STATEMENT_LINE)
        .where(ALLOWANCE_STATEMENT_LINE.STATEMENT_ID.eq(statementId))
        .orderBy(ALLOWANCE_STATEMENT_LINE.LINE_NO)
        .fetch { AllowanceStatementLine(it.lineNo, it.productCode, it.quantity, Json.mapper.readValue(it.raw.data(), mapType)) }

    private fun insertHeader(view: StatementView, importedBy: String) {
        dsl.insertInto(REGISTRY_STATEMENT)
            .set(REGISTRY_STATEMENT.ID, view.id)
            .set(REGISTRY_STATEMENT.REGISTRY_CODE, view.registryCode)
            .set(REGISTRY_STATEMENT.REGISTRY_ACCOUNT_ID, view.registryAccountId)
            .set(REGISTRY_STATEMENT.STATEMENT_DATE, view.statementDate)
            .set(REGISTRY_STATEMENT.SOURCE, view.source)
            .set(REGISTRY_STATEMENT.IMPORTED_BY, importedBy)
            .set(REGISTRY_STATEMENT.LINE_COUNT, view.lineCount)
            .set(REGISTRY_STATEMENT.STATUS, view.status.name)
            .set(REGISTRY_STATEMENT.KIND, view.kind.name)
            .execute()
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
            dsl.select(RECONCILIATION_BREAK.asterisk()).from(RECONCILIATION_BREAK)
                .join(RECONCILIATION_BREAK_SIGHTING).on(RECONCILIATION_BREAK_SIGHTING.BREAK_ID.eq(RECONCILIATION_BREAK.ID))
                .where(RECONCILIATION_BREAK_SIGHTING.STATEMENT_ID.eq(id))
                .orderBy(RECONCILIATION_BREAK.DETECTED_AT)
                .fetch { toBreak(it.into(RECONCILIATION_BREAK)) },
            StatementKind.valueOf(r.kind),
        )
    }

    override fun statements(registryAccountId: UUID, limit: Int): List<StatementView> = dsl.select(REGISTRY_STATEMENT.ID)
        .from(REGISTRY_STATEMENT)
        .where(REGISTRY_STATEMENT.REGISTRY_ACCOUNT_ID.eq(registryAccountId))
        .orderBy(REGISTRY_STATEMENT.STATEMENT_DATE.desc(), REGISTRY_STATEMENT.ID.desc())
        .limit(limit.coerceIn(1, 500))
        .fetch(REGISTRY_STATEMENT.ID)
        .mapNotNull { statement(it) }

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
            .set(RECONCILIATION_BREAK.CERT_NO_START, view.range?.start)
            .set(RECONCILIATION_BREAK.CERT_NO_END, view.range?.end)
            .set(RECONCILIATION_BREAK.PRODUCT_CODE, view.productCode)
            .set(RECONCILIATION_BREAK.LOT_ID, view.lotId)
            .set(RECONCILIATION_BREAK.REGISTRY_ACCOUNT_ID, view.registryAccountId)
            .set(RECONCILIATION_BREAK.FINGERPRINT, view.fingerprint)
            .set(RECONCILIATION_BREAK.FIRST_SEEN_ON, view.firstSeenOn)
            .set(RECONCILIATION_BREAK.LAST_SEEN_ON, view.lastSeenOn)
            .set(RECONCILIATION_BREAK.REGISTRY_QUANTITY, view.registryQuantity)
            .set(RECONCILIATION_BREAK.INTERNAL_QUANTITY, view.internalQuantity)
            .set(RECONCILIATION_BREAK.STATUS, view.status.name)
            .execute()
        updateBreak(view)
    }

    override fun updateBreak(view: ReconciliationBreakView) {
        dsl.update(RECONCILIATION_BREAK)
            .set(RECONCILIATION_BREAK.STATUS, view.status.name)
            .set(RECONCILIATION_BREAK.DETAILS, JSONB.jsonb(Json.mapper.writeValueAsString(view.details)))
            .set(RECONCILIATION_BREAK.REGISTRY_QUANTITY, view.registryQuantity)
            .set(RECONCILIATION_BREAK.INTERNAL_QUANTITY, view.internalQuantity)
            .set(RECONCILIATION_BREAK.LAST_SEEN_ON, view.lastSeenOn)
            .set(RECONCILIATION_BREAK.ASSIGNEE, view.assignee)
            .set(RECONCILIATION_BREAK.RESOLUTION, view.resolution)
            .set(RECONCILIATION_BREAK.RESOLUTION_ACTION, view.resolutionAction?.name)
            .set(RECONCILIATION_BREAK.RESOLVED_BY, view.resolvedBy)
            .set(RECONCILIATION_BREAK.RESOLVED_AT, view.resolvedAt)
            .set(RECONCILIATION_BREAK.ACCEPTANCE_REQUESTED_BY, view.acceptanceRequestedBy)
            .set(
                RECONCILIATION_BREAK.ACCEPTANCE_REQUESTED_AT,
                // Kept from the first request while pending; cleared when the request is withdrawn or rejected.
                DSL.field(
                    "CASE WHEN {0}::text IS NULL THEN NULL ELSE coalesce({1}, now()) END",
                    Instant::class.java,
                    DSL.`val`(view.acceptanceRequestedBy),
                    RECONCILIATION_BREAK.ACCEPTANCE_REQUESTED_AT,
                ),
            )
            .where(RECONCILIATION_BREAK.ID.eq(view.id))
            .execute()
    }

    override fun updateStatementStatus(id: UUID, status: StatementStatus) {
        dsl.update(REGISTRY_STATEMENT)
            .set(REGISTRY_STATEMENT.STATUS, status.name)
            .set(REGISTRY_STATEMENT.RECONCILED_AT, Instant.now())
            .where(REGISTRY_STATEMENT.ID.eq(id))
            .execute()
    }

    override fun findBreak(id: UUID, forUpdate: Boolean): ReconciliationBreakView? {
        val query = dsl.selectFrom(RECONCILIATION_BREAK).where(RECONCILIATION_BREAK.ID.eq(id))
        return (if (forUpdate) query.forUpdate().fetchOne() else query.fetchOne())?.let(::toBreak)
    }

    override fun currentByFingerprint(fingerprint: String): ReconciliationBreakView? = dsl.selectFrom(RECONCILIATION_BREAK)
        .where(RECONCILIATION_BREAK.FINGERPRINT.eq(fingerprint))
        .and(RECONCILIATION_BREAK.STATUS.`in`(OPEN_STATUSES + BreakStatus.ACCEPTED.name))
        .orderBy(RECONCILIATION_BREAK.DETECTED_AT.desc())
        .limit(1)
        .forUpdate()
        .fetchOne()?.let(::toBreak)

    override fun openForAccount(registryAccountId: UUID): List<ReconciliationBreakView> = dsl.selectFrom(RECONCILIATION_BREAK)
        .where(RECONCILIATION_BREAK.REGISTRY_ACCOUNT_ID.eq(registryAccountId), RECONCILIATION_BREAK.STATUS.`in`(OPEN_STATUSES))
        .orderBy(RECONCILIATION_BREAK.DETECTED_AT)
        .forUpdate()
        .fetch(::toBreak)

    override fun addSighting(breakId: UUID, statementId: UUID) {
        dsl.insertInto(RECONCILIATION_BREAK_SIGHTING)
            .set(RECONCILIATION_BREAK_SIGHTING.BREAK_ID, breakId)
            .set(RECONCILIATION_BREAK_SIGHTING.STATEMENT_ID, statementId)
            .onConflictDoNothing()
            .execute()
    }

    override fun breaks(filter: BreakFilter): List<ReconciliationBreakView> = dsl.selectFrom(RECONCILIATION_BREAK)
        .where(RECONCILIATION_BREAK.STATUS.`in`(filter.statuses.map { it.name }))
        .and(filter.registryAccountId?.let { RECONCILIATION_BREAK.REGISTRY_ACCOUNT_ID.eq(it) } ?: DSL.noCondition())
        .and(filter.type?.let { RECONCILIATION_BREAK.BREAK_TYPE.eq(it.name) } ?: DSL.noCondition())
        .orderBy(RECONCILIATION_BREAK.DETECTED_AT)
        .limit(filter.limit)
        .fetch(::toBreak)

    override fun ageing(today: LocalDate): BreakAgeing {
        val days = DSL.field("{0} - {1}", Int::class.java, DSL.inline(today), RECONCILIATION_BREAK.FIRST_SEEN_ON)
        val r = dsl.select(
            DSL.count().filterWhere(days.le(1)),
            DSL.count().filterWhere(days.gt(1).and(days.le(7))),
            DSL.count().filterWhere(days.gt(7).and(days.le(30))),
            DSL.count().filterWhere(days.gt(30)),
        ).from(RECONCILIATION_BREAK).where(RECONCILIATION_BREAK.STATUS.`in`(OPEN_STATUSES)).fetchSingle()
        return BreakAgeing(r.value1(), r.value2(), r.value3(), r.value4())
    }

    private fun toBreak(r: ReconciliationBreakRecord) = ReconciliationBreakView(
        id = r.id,
        statementId = r.statementId,
        type = BreakType.valueOf(r.breakType),
        issuanceRef = r.issuanceRef,
        range = r.certNoStart?.let { start -> r.certNoEnd?.let { end -> CertificateRange(start, end) } },
        lotId = r.lotId,
        details = Json.mapper.readValue(r.details.data(), mapType),
        status = BreakStatus.valueOf(r.status),
        resolution = r.resolution,
        productCode = r.productCode,
        registryQuantity = r.registryQuantity,
        internalQuantity = r.internalQuantity,
        registryAccountId = r.registryAccountId,
        fingerprint = r.fingerprint,
        firstSeenOn = r.firstSeenOn,
        lastSeenOn = r.lastSeenOn,
        assignee = r.assignee,
        resolutionAction = r.resolutionAction?.let(BreakAction::valueOf),
        acceptanceRequestedBy = r.acceptanceRequestedBy,
        resolvedBy = r.resolvedBy,
        resolvedAt = r.resolvedAt,
        detectedAt = r.detectedAt,
    )

    private companion object {
        val OPEN_STATUSES = BreakStatus.entries.filter { it.open }.map { it.name }
    }
}
