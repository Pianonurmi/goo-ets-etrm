package com.pianonurmi.etrm.reporting.adapter.jooq

import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.reporting.adapter.jooq.generated.tables.EmirEvent.EMIR_EVENT
import com.pianonurmi.etrm.reporting.adapter.jooq.generated.tables.EmirFieldRule.EMIR_FIELD_RULE
import com.pianonurmi.etrm.reporting.adapter.jooq.generated.tables.ReportExport.REPORT_EXPORT
import com.pianonurmi.etrm.reporting.adapter.jooq.generated.tables.records.EmirEventRecord
import com.pianonurmi.etrm.reporting.adapter.jooq.generated.tables.records.ReportExportRecord
import com.pianonurmi.etrm.reporting.api.EmirAction
import com.pianonurmi.etrm.reporting.api.EmirEventView
import com.pianonurmi.etrm.reporting.api.EmirFieldRule
import com.pianonurmi.etrm.reporting.api.ReportExportView
import com.pianonurmi.etrm.reporting.api.ReportFormat
import com.pianonurmi.etrm.reporting.domain.ReportingRepository
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Repository
class JooqReportingRepository(private val dsl: DSLContext) : ReportingRepository {
    private val stringMap = object : TypeReference<Map<String, String?>>() {}

    override fun nextExportSeq(reportCode: String, legalEntityId: UUID?): Int {
        // One export number at a time per report and entity.
        dsl.execute("SELECT pg_advisory_xact_lock(hashtext({0}))", DSL.`val`("report:$reportCode:$legalEntityId"))
        return (
            dsl.select(DSL.max(REPORT_EXPORT.SEQ)).from(REPORT_EXPORT)
                .where(
                    REPORT_EXPORT.REPORT_CODE.eq(reportCode),
                    legalEntityId?.let { REPORT_EXPORT.LEGAL_ENTITY_ID.eq(it) } ?: REPORT_EXPORT.LEGAL_ENTITY_ID.isNull,
                )
                .fetchOne(0, Int::class.java) ?: 0
        ) + 1
    }

    override fun insertExport(export: ReportExportView, content: String) {
        dsl.insertInto(REPORT_EXPORT)
            .set(REPORT_EXPORT.ID, export.id)
            .set(REPORT_EXPORT.REPORT_CODE, export.reportCode)
            .set(REPORT_EXPORT.LEGAL_ENTITY_ID, export.legalEntityId)
            .set(REPORT_EXPORT.SEQ, export.seq)
            .set(REPORT_EXPORT.FORMAT, export.format.name)
            .set(REPORT_EXPORT.PARAMETERS, JSONB.valueOf(Json.mapper.writeValueAsString(export.parameters)))
            .set(REPORT_EXPORT.ROW_COUNT, export.rowCount)
            .set(REPORT_EXPORT.CONTENT_HASH, export.contentHash)
            .set(REPORT_EXPORT.CONTENT, content)
            .set(REPORT_EXPORT.EXPORTED_BY, export.exportedBy)
            .set(REPORT_EXPORT.EXPORTED_AT, export.exportedAt)
            .execute()
    }

    override fun exports(legalEntityId: UUID?, reportCode: String?): List<ReportExportView> = dsl.selectFrom(REPORT_EXPORT)
        .where(
            legalEntityId?.let { REPORT_EXPORT.LEGAL_ENTITY_ID.eq(it) } ?: DSL.noCondition(),
            reportCode?.let { REPORT_EXPORT.REPORT_CODE.eq(it) } ?: DSL.noCondition(),
        )
        .orderBy(REPORT_EXPORT.EXPORTED_AT.desc())
        .limit(MAX_LISTED)
        .fetch(::toExport)

    override fun export(id: UUID): Pair<ReportExportView, String?>? =
        dsl.selectFrom(REPORT_EXPORT).where(REPORT_EXPORT.ID.eq(id)).fetchOne()?.let { toExport(it) to it.content }

    override fun expireContent(before: Instant, at: Instant, limit: Int): Int = dsl.update(REPORT_EXPORT)
        .setNull(REPORT_EXPORT.CONTENT)
        .set(REPORT_EXPORT.CONTENT_EXPIRED_AT, at)
        .where(
            REPORT_EXPORT.ID.`in`(
                DSL.select(REPORT_EXPORT.ID).from(REPORT_EXPORT)
                    .where(REPORT_EXPORT.CONTENT.isNotNull, REPORT_EXPORT.EXPORTED_AT.lt(before))
                    .orderBy(REPORT_EXPORT.EXPORTED_AT)
                    .limit(limit),
            ),
        )
        .execute()

    override fun insertEmirEvent(event: EmirEventView): Boolean = dsl.insertInto(EMIR_EVENT)
        .set(EMIR_EVENT.ID, event.id)
        .set(EMIR_EVENT.LEGAL_ENTITY_ID, event.legalEntityId)
        .set(EMIR_EVENT.TRADE_ID, event.tradeId)
        .set(EMIR_EVENT.TRADE_NUMBER, event.tradeNumber)
        .set(EMIR_EVENT.TRADE_VERSION, event.tradeVersion)
        .set(EMIR_EVENT.UTI, event.uti)
        .set(EMIR_EVENT.ACTION_TYPE, event.action.name)
        .set(EMIR_EVENT.EVENT_DATE, event.eventDate)
        .set(EMIR_EVENT.SOURCE_REF, event.sourceRef)
        .set(EMIR_EVENT.FIELDS, JSONB.valueOf(Json.mapper.writeValueAsString(event.fields)))
        .set(EMIR_EVENT.RECORDED_BY, event.recordedBy)
        .set(EMIR_EVENT.RECORDED_AT, event.recordedAt)
        .onConflictDoNothing()
        .execute() == 1

    override fun emirEvents(tradeId: UUID): List<EmirEventView> =
        dsl.selectFrom(EMIR_EVENT).where(EMIR_EVENT.TRADE_ID.eq(tradeId)).orderBy(EMIR_EVENT.RECORDED_AT).fetch(::toEvent)

    override fun emirEvents(legalEntityId: UUID, from: LocalDate?, toExclusive: LocalDate?): List<EmirEventView> = dsl.selectFrom(EMIR_EVENT)
        .where(
            EMIR_EVENT.LEGAL_ENTITY_ID.eq(legalEntityId),
            from?.let { EMIR_EVENT.EVENT_DATE.ge(it) } ?: DSL.noCondition(),
            toExclusive?.let { EMIR_EVENT.EVENT_DATE.lt(it) } ?: DSL.noCondition(),
        )
        .orderBy(EMIR_EVENT.EVENT_DATE, EMIR_EVENT.RECORDED_AT)
        .fetch(::toEvent)

    override fun emirFieldRules(): List<EmirFieldRule> = dsl.selectFrom(EMIR_FIELD_RULE).orderBy(EMIR_FIELD_RULE.FIELD_NAME).fetch {
        EmirFieldRule(it.fieldName, it.appliesTo, it.description, it.verified)
    }

    private fun toExport(r: ReportExportRecord) = ReportExportView(
        r.id,
        r.reportCode,
        r.legalEntityId,
        r.seq,
        ReportFormat.valueOf(r.format),
        Json.mapper.readValue(r.parameters.data(), stringMap).mapValues { it.value.orEmpty() },
        r.rowCount,
        r.contentHash,
        r.exportedBy,
        r.exportedAt,
        r.contentExpiredAt,
    )

    private fun toEvent(r: EmirEventRecord) = EmirEventView(
        r.id,
        r.legalEntityId,
        r.tradeId,
        r.tradeNumber,
        r.tradeVersion,
        r.uti,
        EmirAction.valueOf(r.actionType),
        r.eventDate,
        r.sourceRef,
        Json.mapper.readValue(r.fields.data(), stringMap),
        r.recordedBy,
        r.recordedAt,
    )

    private companion object {
        const val MAX_LISTED = 500
    }
}
