package com.pianonurmi.etrm.registry.application

import com.pianonurmi.etrm.document.api.DocumentCommand
import com.pianonurmi.etrm.document.api.StoreDocument
import com.pianonurmi.etrm.inventory.api.ImportStatement
import com.pianonurmi.etrm.inventory.api.ReconciliationCommand
import com.pianonurmi.etrm.inventory.api.StatementView
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.referencedata.api.RegistryType
import com.pianonurmi.etrm.registry.api.InstructionStatus
import com.pianonurmi.etrm.registry.api.RegistryInstructionView
import com.pianonurmi.etrm.registry.api.RegistryStatementCommand
import com.pianonurmi.etrm.registry.api.RegistryTransactionLine
import com.pianonurmi.etrm.registry.api.StatementFormatCommand
import com.pianonurmi.etrm.registry.api.StatementFormatQuery
import com.pianonurmi.etrm.registry.api.StatementFormatView
import com.pianonurmi.etrm.registry.api.StatementKind
import com.pianonurmi.etrm.registry.api.TransactionImportReport
import com.pianonurmi.etrm.registry.api.TransactionLineStatus
import com.pianonurmi.etrm.registry.api.UpsertStatementFormat
import com.pianonurmi.etrm.registry.domain.RegistryInstructionRepository
import com.pianonurmi.etrm.registry.domain.StatementFormatRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
@Transactional
class StatementFormatService(
    private val repository: StatementFormatRepository,
    private val referenceData: ReferenceDataQuery,
    private val actorProvider: ActorProvider,
    private val clock: Clock,
) : StatementFormatQuery, StatementFormatCommand {
    @Transactional(readOnly = true)
    override fun formats(registryCode: String?): List<StatementFormatView> = repository.all(registryCode?.uppercase())

    @Transactional(readOnly = true)
    override fun resolve(registryCode: String, kind: StatementKind, profileCode: String?): StatementFormatView {
        val code = registryCode.uppercase()
        val found = if (profileCode != null) {
            repository.find(code, profileCode.uppercase(), kind) ?: repository.find("*", profileCode.uppercase(), kind)
        } else {
            repository.forRegistry(code, kind).firstOrNull() ?: repository.find("*", GENERIC, kind)
        }
        return found?.takeIf { it.active } ?: throw NotFoundException("StatementFormat", "$code/${profileCode ?: "default"}/$kind")
    }

    override fun upsert(command: UpsertStatementFormat): StatementFormatView {
        val actor = actorProvider.current()
        // The maintainers of transfer routes (RegistryAccess.maintainer) and registry operators.
        actor.requireRole(Role.REGISTRY_OPERATOR, Role.BACK_OFFICE, Role.MIDDLE_OFFICE)
        val code = command.registryCode.uppercase()
        if (code != "*") referenceData.requireRegistry(code)
        val format = StatementFormatView(
            id = repository.find(code, command.profileCode.uppercase(), command.kind)?.id ?: Ids.newId(),
            registryCode = code,
            profileCode = command.profileCode.uppercase(),
            kind = command.kind,
            delimiter = command.delimiter,
            skipLines = command.skipLines,
            dateFormat = command.dateFormat,
            columnMapping = command.columnMapping,
            constants = command.constants,
            active = command.active,
            version = 1,
            updatedBy = actor.userId,
            updatedAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
        )
        StatementFormatParser.problems(format).takeIf {
            it.isNotEmpty()
        }?.let { throw ValidationException("Invalid statement format: ${it.joinToString("; ")}") }
        repository.upsert(format)
        return repository.find(code, format.profileCode, format.kind)!!
    }

    companion object {
        const val GENERIC = "GENERIC"
    }
}

/**
 * Registry exports in, reconciliation and confirmations out. Holdings statements go to inventory for reconciliation
 * (which in turn confirms instructions whose certificates left the account); transaction exports confirm or fail
 * open instructions directly, matched by instruction key, registry reference or certificate range.
 */
@Service
@Transactional
class RegistryStatementService(
    private val formats: StatementFormatService,
    private val instructionRepository: RegistryInstructionRepository,
    private val instructions: InstructionOutcomes,
    private val reconciliation: ReconciliationCommand,
    private val referenceData: ReferenceDataQuery,
    private val documents: DocumentCommand,
    private val actorProvider: ActorProvider,
) : RegistryStatementCommand {
    override fun importHoldings(registryAccountId: UUID, statementDate: LocalDate, source: String, content: String, profileCode: String?): StatementView {
        actorProvider.current().requireRole(Role.REGISTRY_OPERATOR, Role.BACK_OFFICE)
        val account = referenceData.requireRegistryAccount(registryAccountId)
        val imported = if (referenceData.requireRegistry(account.registryCode).registryType == RegistryType.EMISSION_ALLOWANCE) {
            val format = formats.resolve(account.registryCode, StatementKind.ALLOWANCE_HOLDINGS, profileCode)
            val statement = StatementFormatParser.allowanceHoldings(format, registryAccountId, statementDate, source, content)
            reconciliation.importAllowanceStatement(statement.copy(registryCode = account.registryCode))
        } else {
            reconciliation.importStatement(parseHoldings(registryAccountId, statementDate, source, content, profileCode))
        }
        return reconciliation.reconcile(imported.id)
    }

    @Transactional(readOnly = true)
    override fun parseHoldings(registryAccountId: UUID, statementDate: LocalDate, source: String, content: String, profileCode: String?): ImportStatement {
        val account = referenceData.requireRegistryAccount(registryAccountId)
        val format = formats.resolve(account.registryCode, StatementKind.GOO_HOLDINGS, profileCode)
        return StatementFormatParser.holdings(format, registryAccountId, statementDate, source, content).copy(registryCode = account.registryCode)
    }

    override fun importTransactions(registryCode: String, source: String, content: String, profileCode: String?): TransactionImportReport {
        val actor = actorProvider.current()
        actor.requireRole(Role.REGISTRY_OPERATOR, Role.BACK_OFFICE)
        val code = referenceData.requireRegistry(registryCode).code
        val format = formats.resolve(code, StatementKind.GOO_TRANSACTIONS, profileCode)
        val lines = StatementFormatParser.transactions(format, content)
        val importId = Ids.newId()
        val document = documents.store(
            StoreDocument(
                "REGISTRY_TRANSACTIONS",
                "REGISTRY_IMPORT",
                importId,
                null,
                "$code transactions $source",
                "$code-transactions.csv",
                "text/csv",
                content.toByteArray(),
            ),
        )
        val open = instructionRepository.open(code)
        val confirmed = mutableListOf<UUID>()
        val failed = mutableListOf<UUID>()
        val unmatched = mutableListOf<RegistryTransactionLine>()
        var alreadyFinal = 0
        lines.filter { it.status != TransactionLineStatus.OTHER }.forEach { line ->
            val match = match(code, line, open)
            when {
                match == null -> unmatched += line
                match.status.terminal -> alreadyFinal++
                match.status == InstructionStatus.PENDING_APPROVAL -> unmatched += line
                else -> {
                    val completed = line.status == TransactionLineStatus.COMPLETED
                    instructions.applyRegistryOutcome(match.id, completed, line.externalRef, "transactions:$source#${line.lineNo}")
                    if (completed) confirmed += match.id else failed += match.id
                }
            }
        }
        return TransactionImportReport(code, lines.size, confirmed, failed, alreadyFinal, unmatched, document.id)
    }

    private fun match(registryCode: String, line: RegistryTransactionLine, open: List<RegistryInstructionView>): RegistryInstructionView? {
        line.instructionKey?.let { key -> instructionRepository.byKey(key)?.takeIf { it.registryCode == registryCode }?.let { return it } }
        line.externalRef?.let { ref -> instructionRepository.byExternalRef(registryCode, ref)?.let { return it } }
        val range = line.range ?: return null
        return open.singleOrNull { instruction ->
            instruction.range == range && (line.transactionType == null || line.transactionType == instruction.type)
        }
    }
}
