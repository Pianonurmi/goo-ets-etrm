package com.pianonurmi.etrm.reporting.application

import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.text.Csv
import com.pianonurmi.etrm.reporting.api.EmirCompleteness
import com.pianonurmi.etrm.reporting.api.EmirEventView
import com.pianonurmi.etrm.reporting.api.EmirFieldRule
import com.pianonurmi.etrm.reporting.api.ReportDefinitionView
import com.pianonurmi.etrm.reporting.api.ReportExportView
import com.pianonurmi.etrm.reporting.api.ReportExported
import com.pianonurmi.etrm.reporting.api.ReportFormat
import com.pianonurmi.etrm.reporting.api.ReportOutput
import com.pianonurmi.etrm.reporting.api.ReportResult
import com.pianonurmi.etrm.reporting.api.ReportingCommand
import com.pianonurmi.etrm.reporting.api.ReportingQuery
import com.pianonurmi.etrm.reporting.domain.EmirCompletenessCheck
import com.pianonurmi.etrm.reporting.domain.ReportDefinition
import com.pianonurmi.etrm.reporting.domain.ReportParameters
import com.pianonurmi.etrm.reporting.domain.ReportingRepository
import com.pianonurmi.etrm.trade.api.TradeQuery
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Runs reports from the catalogue and logs every one produced: numbered per report and entity, with the content and
 * its hash, and a `ReportExported` event for the audit trail (ADR 0023).
 */
@Service
@Transactional
class ReportingService(
    private val catalogue: ReportCatalogue,
    private val repository: ReportingRepository,
    private val emir: EmirEvents,
    private val trades: TradeQuery,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) : ReportingQuery, ReportingCommand {
    override fun authorize(code: String, parameters: Map<String, String>): UUID? = checked(code, parameters).third

    override fun produce(code: String, parameters: Map<String, String>, format: ReportFormat): ReportOutput {
        val (definition, params, entity) = checked(code, parameters)
        val actor = actorProvider.current()
        val result = definition.run(params)
        val content = render(result, format)
        val export = ReportExportView(
            Ids.newId(),
            definition.code,
            entity,
            repository.nextExportSeq(definition.code, entity),
            format,
            params.values,
            result.rows.size,
            sha256(content),
            actor.userId,
            clock.instant(),
        )
        repository.insertExport(export, content)
        events.publishEvent(ReportExported(export.id, export.reportCode, entity, export.seq, export.contentHash))
        return ReportOutput(export, content)
    }

    private fun checked(code: String, parameters: Map<String, String>): Triple<ReportDefinition, ReportParameters, UUID?> {
        val definition = catalogue.definition(code) ?: throw NotFoundException("Report", code)
        val actor = actorProvider.current()
        actor.requireRole(*definition.roles.toTypedArray())
        val params = ReportParameters(parameters.filterKeys { name -> definition.parameters.any { it.name == name } })
        val entity = if (definition.perEntity) params.uuid("legalEntityId").also(actor::requireEntity) else null
        return Triple(definition, params, entity)
    }

    private fun render(result: ReportResult, format: ReportFormat): String = when (format) {
        ReportFormat.CSV -> Csv.writeForSpreadsheet(result.columns, result.rows.map { row -> result.columns.map { cell(row[it]) } })
        ReportFormat.JSON -> Json.mapper.writeValueAsString(mapOf("report" to result.code, "columns" to result.columns, "rows" to result.rows))
    }

    private fun sha256(content: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun cell(value: Any?): Any? = when (value) {
        is BigDecimal -> value.toPlainString()
        else -> value
    }

    override fun reportError(tradeId: UUID, reason: String): EmirEventView {
        val actor = actorProvider.current()
        actor.requireRole(Role.MIDDLE_OFFICE)
        if (reason.isBlank()) throw ValidationException("An error report needs a reason")
        val trade = trades.requireTrade(tradeId)
        actor.requireEntity(trade.legalEntityId)
        return emir.error(
            trade,
            reason.trim(),
        ) ?: throw ValidationException("Trade ${trade.tradeNumber} has no reported event to correct, or was already reported in error")
    }

    @Transactional(readOnly = true)
    override fun catalogue(): List<ReportDefinitionView> = catalogue.definitions.map {
        ReportDefinitionView(it.code, it.title, it.description, it.roles, it.parameters)
    }

    @Transactional(readOnly = true)
    override fun exports(legalEntityId: UUID?, reportCode: String?): List<ReportExportView> {
        val actor = actorProvider.current()
        actor.requireRole(Role.AUDITOR, Role.ACCOUNTANT, Role.RISK, Role.MIDDLE_OFFICE)
        legalEntityId?.let(actor::requireEntity)
        return repository.exports(legalEntityId, reportCode?.uppercase()).filter { actor.canAccess(it.legalEntityId) }
    }

    @Transactional(readOnly = true)
    override fun export(id: UUID): ReportOutput? {
        val (export, content) = repository.export(id) ?: return null
        val actor = actorProvider.current()
        actor.requireRole(*(catalogue.definition(export.reportCode)?.roles ?: setOf(Role.AUDITOR)).toTypedArray())
        actor.requireEntity(export.legalEntityId)
        content ?: throw ConflictException(
            "The content of ${export.reportCode} #${export.seq} was dropped after the retention period on " +
                "${export.contentExpiredAt}; its hash ${export.contentHash} still proves what was produced. Run the report again.",
        )
        return ReportOutput(export, content)
    }

    @Transactional(readOnly = true)
    override fun emirEvents(legalEntityId: UUID, from: LocalDate, toExclusive: LocalDate): List<EmirEventView> {
        compliance(legalEntityId)
        return repository.emirEvents(legalEntityId, from, toExclusive)
    }

    @Transactional(readOnly = true)
    override fun emirCompleteness(legalEntityId: UUID): List<EmirCompleteness> {
        compliance(legalEntityId)
        val rules = repository.emirFieldRules()
        return repository.emirEvents(legalEntityId, null, null).groupBy { it.tradeId }.map { (tradeId, events) ->
            // The latest lifecycle event, and the latest valuation, must each be complete.
            val latest = events.filter {
                it.action != com.pianonurmi.etrm.reporting.api.EmirAction.VALU
            }.maxByOrNull { it.recordedAt } ?: events.maxBy { it.recordedAt }
            val valuation = events.filter { it.action == com.pianonurmi.etrm.reporting.api.EmirAction.VALU }.maxByOrNull { it.recordedAt }
            val missing = (
                EmirCompletenessCheck.missing(latest, rules) + (
                    valuation?.let {
                        EmirCompletenessCheck.missing(it, rules)
                    } ?: emptyList()
                )
            ).distinct().sorted()
            EmirCompleteness(tradeId, latest.tradeNumber, latest.action, missing)
        }.sortedBy { it.tradeNumber }
    }

    @Transactional(readOnly = true)
    override fun emirFieldRules(): List<EmirFieldRule> = repository.emirFieldRules()

    private fun compliance(legalEntityId: UUID) {
        val actor = actorProvider.current()
        actor.requireRole(Role.MIDDLE_OFFICE, Role.RISK, Role.ACCOUNTANT, Role.AUDITOR)
        actor.requireEntity(legalEntityId)
    }
}
