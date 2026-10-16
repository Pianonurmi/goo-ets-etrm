package com.pianonurmi.etrm.reporting.api

import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import com.pianonurmi.etrm.platform.security.Role
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class ReportFormat { CSV, JSON }

data class ReportParameter(val name: String, val type: String, val required: Boolean, val description: String)

/** A report in the catalogue: who may run it and what it takes. */
data class ReportDefinitionView(
    val code: String,
    val title: String,
    val description: String,
    val roles: Set<Role>,
    val parameters: List<ReportParameter>,
)

/** The rows of a report; values are strings, numbers, dates or null. */
data class ReportResult(val code: String, val columns: List<String>, val rows: List<Map<String, Any?>>)

/** One produced report: numbered per report and entity, with the hash of what was handed out. */
data class ReportExportView(
    val id: UUID,
    val reportCode: String,
    val legalEntityId: UUID?,
    val seq: Int,
    val format: ReportFormat,
    val parameters: Map<String, String>,
    val rowCount: Int,
    val contentHash: String,
    val exportedBy: String,
    val exportedAt: Instant,
    /** Set once the content was dropped after the retention period; number, parameters and hash stay. */
    val contentExpiredAt: Instant? = null,
)

data class ReportOutput(val export: ReportExportView, val content: String)

enum class EmirAction { NEWT, MODI, TERM, EROR, VALU }

data class EmirEventView(
    val id: UUID,
    val legalEntityId: UUID,
    val tradeId: UUID,
    val tradeNumber: String,
    val tradeVersion: Int,
    val uti: String?,
    val action: EmirAction,
    val eventDate: LocalDate,
    val sourceRef: String,
    val fields: Map<String, String?>,
    val recordedBy: String,
    val recordedAt: Instant,
)

data class EmirFieldRule(val fieldName: String, val appliesTo: String, val description: String, val verified: Boolean)

/** The latest event of a reportable trade and the fields it is missing. */
data class EmirCompleteness(val tradeId: UUID, val tradeNumber: String, val latestAction: EmirAction, val missingFields: List<String>) {
    val complete: Boolean get() = missingFields.isEmpty()
}

interface ReportingQuery {
    fun catalogue(): List<ReportDefinitionView>

    fun exports(legalEntityId: UUID?, reportCode: String?): List<ReportExportView>

    fun export(id: UUID): ReportOutput?

    fun emirEvents(legalEntityId: UUID, from: LocalDate, toExclusive: LocalDate): List<EmirEventView>

    fun emirCompleteness(legalEntityId: UUID): List<EmirCompleteness>

    fun emirFieldRules(): List<EmirFieldRule>
}

interface ReportingCommand {
    /** Runs a report with its parameters and logs the export; returns the content in the format asked for. */
    fun produce(code: String, parameters: Map<String, String>, format: ReportFormat): ReportOutput

    /** Records that a reported trade should not have been reported (EROR). */
    fun reportError(tradeId: UUID, reason: String): EmirEventView
}

data class ReportExported(
    val exportId: UUID,
    val reportCode: String,
    val legalEntityId: UUID?,
    val seq: Int,
    val contentHash: String,
    val metadata: EventMetadata = EventMetadata(),
) : DomainEvent by metadata
