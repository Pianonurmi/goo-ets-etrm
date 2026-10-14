package com.pianonurmi.etrm.etscompliance.application

import com.pianonurmi.etrm.etscompliance.api.ComplianceStatus
import com.pianonurmi.etrm.etscompliance.api.DeadlineKind
import com.pianonurmi.etrm.etscompliance.api.DeadlineView
import com.pianonurmi.etrm.etscompliance.api.EmissionKind
import com.pianonurmi.etrm.etscompliance.api.EmissionRecordView
import com.pianonurmi.etrm.etscompliance.api.EmissionStatus
import com.pianonurmi.etrm.etscompliance.api.EmissionsBasis
import com.pianonurmi.etrm.etscompliance.api.EntityCompliance
import com.pianonurmi.etrm.etscompliance.api.EtsComplianceCommand
import com.pianonurmi.etrm.etscompliance.api.EtsComplianceQuery
import com.pianonurmi.etrm.etscompliance.api.FreeAllocationView
import com.pianonurmi.etrm.etscompliance.api.InstallationCompliance
import com.pianonurmi.etrm.etscompliance.api.InstallationKind
import com.pianonurmi.etrm.etscompliance.api.InstallationView
import com.pianonurmi.etrm.etscompliance.api.RecordEmissions
import com.pianonurmi.etrm.etscompliance.api.RecordVerifiedEmissions
import com.pianonurmi.etrm.etscompliance.api.RegisterInstallation
import com.pianonurmi.etrm.etscompliance.api.RequestSurrender
import com.pianonurmi.etrm.etscompliance.api.SetFreeAllocation
import com.pianonurmi.etrm.etscompliance.api.SurrenderAttributed
import com.pianonurmi.etrm.etscompliance.api.SurrenderRequestStatus
import com.pianonurmi.etrm.etscompliance.api.SurrenderRequestView
import com.pianonurmi.etrm.etscompliance.api.VerifiedEmissionsApproved
import com.pianonurmi.etrm.etscompliance.api.VerifiedEmissionsSubmitted
import com.pianonurmi.etrm.etscompliance.domain.Deadlines
import com.pianonurmi.etrm.etscompliance.domain.EmissionsBasisRule
import com.pianonurmi.etrm.etscompliance.domain.EtsComplianceRepository
import com.pianonurmi.etrm.etscompliance.domain.SurrenderRecord
import com.pianonurmi.etrm.inventory.api.AllowanceHoldingQuery
import com.pianonurmi.etrm.inventory.api.AllowanceMovementType
import com.pianonurmi.etrm.inventory.api.HoldingChanged
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.product.api.ProductCatalogueQuery
import com.pianonurmi.etrm.referencedata.api.HolderType
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.referencedata.api.RegistryAccountType
import com.pianonurmi.etrm.registry.api.InstructionCancelled
import com.pianonurmi.etrm.registry.api.InstructionConfirmed
import com.pianonurmi.etrm.registry.api.InstructionFailed
import com.pianonurmi.etrm.registry.api.RegistryInstructionCommand
import com.pianonurmi.etrm.registry.api.SubmitSurrender
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Installations, their emissions, free allocation and surrenders. Surrenders are attributed to compliance years from
 * the holding movements on the installation's account, so a surrender confirmed in the registry, whoever instructed
 * it, counts once.
 */
@Service
@Transactional
class EtsComplianceService(
    private val repository: EtsComplianceRepository,
    private val referenceData: ReferenceDataQuery,
    private val catalogue: ProductCatalogueQuery,
    private val holdings: AllowanceHoldingQuery,
    private val registry: RegistryInstructionCommand,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) : EtsComplianceQuery, EtsComplianceCommand {
    private val deadlines: Deadlines by lazy { Deadlines(repository.deadlineRules()) }

    // ----------------------------------------------------------------------------------------------------------
    // Installations and emissions
    // ----------------------------------------------------------------------------------------------------------

    override fun register(command: RegisterInstallation): InstallationView {
        authorise(command.legalEntityId, Role.MIDDLE_OFFICE, Role.BACK_OFFICE)
        referenceData.requireLegalEntity(command.legalEntityId)
        val account = referenceData.requireRegistryAccount(command.registryAccountId)
        val expected = when (command.kind) {
            InstallationKind.INSTALLATION -> RegistryAccountType.OPERATOR_HOLDING
            InstallationKind.AIRCRAFT_OPERATOR -> RegistryAccountType.AIRCRAFT_OPERATOR_HOLDING
        }
        if (account.accountType != expected) throw ValidationException("A ${command.kind} holds an $expected account, not ${account.accountType}")
        if (account.holderType != HolderType.LEGAL_ENTITY || account.holderId != command.legalEntityId) {
            throw ValidationException("Account ${account.accountNumber} is not held by the installation's legal entity")
        }
        if (repository.installationByAccount(account.id) != null) throw ConflictException("Account ${account.accountNumber} already belongs to an installation")
        val products = (command.eligibleProducts ?: listOf("EUA")).map { it.uppercase() }
        products.forEach { catalogue.allowanceProduct(it) ?: throw NotFoundException("AllowanceProduct", it) }
        val installation = InstallationView(
            id = Ids.newId(),
            legalEntityId = command.legalEntityId,
            kind = command.kind,
            eutlId = command.eutlId.trim(),
            permitId = command.permitId?.trim(),
            name = command.name.trim(),
            country = command.country.uppercase(),
            activityCode = command.activityCode,
            monitoringPlanRef = command.monitoringPlanRef,
            monitoringPlanVersion = command.monitoringPlanVersion,
            registryAccountId = account.id,
            eligibleProducts = products,
            validFrom = command.validFrom,
            validTo = null,
            active = true,
            version = 1,
        )
        repository.insertInstallation(installation)
        return installation
    }

    override fun recordEmissions(installationId: UUID, command: RecordEmissions): EmissionRecordView {
        val installation = requireInstallation(installationId)
        authorise(installation.legalEntityId, Role.MIDDLE_OFFICE, Role.BACK_OFFICE, Role.RISK)
        if (command.kind == EmissionKind.VERIFIED) throw ValidationException("Verified emissions are recorded with their verifier for approval")
        if ((command.kind == EmissionKind.MONTHLY_ESTIMATE) != (command.periodMonth != null)) {
            throw ValidationException(
                "Monthly estimates, and only they, name a month",
            )
        }
        if (command.quantityTco2e < 0) throw ValidationException("Emissions cannot be negative")
        repository.emissions(installationId, command.complianceYear)
            .filter { it.status == EmissionStatus.APPROVED && it.kind == command.kind && it.periodMonth == command.periodMonth }
            .forEach { repository.setEmissionStatus(it.id, EmissionStatus.SUPERSEDED) }
        val record =
            emission(installation, command.complianceYear, command.kind, command.periodMonth, command.quantityTco2e, EmissionStatus.APPROVED, command.note)
        repository.insertEmission(record)
        return record
    }

    override fun recordVerifiedEmissions(installationId: UUID, command: RecordVerifiedEmissions): EmissionRecordView {
        val installation = requireInstallation(installationId)
        authorise(installation.legalEntityId, Role.MIDDLE_OFFICE, Role.BACK_OFFICE, Role.RISK)
        if (command.quantityTco2e < 0) throw ValidationException("Emissions cannot be negative")
        if (command.verifier.isBlank()) throw ValidationException("Verified emissions name their verifier")
        val record = emission(
            installation,
            command.complianceYear,
            EmissionKind.VERIFIED,
            null,
            command.quantityTco2e,
            EmissionStatus.PENDING_APPROVAL,
            command.note,
        )
            .copy(verifier = command.verifier.trim(), verifiedOn = command.verifiedOn, documentRef = command.documentRef)
        repository.insertEmission(record)
        events.publishEvent(VerifiedEmissionsSubmitted(record, installation))
        return record
    }

    private fun emission(installation: InstallationView, year: Int, kind: EmissionKind, month: Int?, quantity: Long, status: EmissionStatus, note: String?) =
        EmissionRecordView(
            Ids.newId(),
            installation.id,
            year,
            kind,
            month,
            quantity,
            status,
            null,
            null,
            null,
            note,
            actorProvider.current().userId,
            clock.instant(),
            null,
            null,
        )

    /** From the approval task: the verified figure replaces any earlier approved one for the year. */
    fun approveVerified(recordId: UUID, by: String): EmissionRecordView {
        val record = repository.emission(recordId, lock = true) ?: throw NotFoundException("EmissionRecord", recordId)
        if (record.status != EmissionStatus.PENDING_APPROVAL) throw ValidationException("Emissions record ${record.id} is ${record.status}")
        if (record.recordedBy == by) throw SegregationOfDutiesException("Verified emissions are approved by someone other than who recorded them")
        repository.emissions(record.installationId, record.complianceYear)
            .filter { it.kind == EmissionKind.VERIFIED && it.status == EmissionStatus.APPROVED }
            .forEach { repository.setEmissionStatus(it.id, EmissionStatus.SUPERSEDED) }
        repository.setEmissionStatus(recordId, EmissionStatus.APPROVED, by, clock.instant())
        val approved = repository.emission(recordId)!!
        events.publishEvent(VerifiedEmissionsApproved(approved, requireInstallation(approved.installationId)))
        return approved
    }

    fun rejectVerified(recordId: UUID) {
        val record = repository.emission(recordId, lock = true) ?: throw NotFoundException("EmissionRecord", recordId)
        if (record.status != EmissionStatus.PENDING_APPROVAL) throw ValidationException("Emissions record ${record.id} is ${record.status}")
        repository.setEmissionStatus(recordId, EmissionStatus.REJECTED)
    }

    override fun setFreeAllocation(installationId: UUID, year: Int, command: SetFreeAllocation): FreeAllocationView {
        val installation = requireInstallation(installationId)
        authorise(installation.legalEntityId, Role.MIDDLE_OFFICE, Role.BACK_OFFICE)
        if (command.allocated < 0 || (command.adjusted ?: 0) < 0) throw ValidationException("Allocations cannot be negative")
        repository.upsertFreeAllocation(installationId, year, command, actorProvider.current().userId)
        return freeAllocationOf(installation, year)!!
    }

    // ----------------------------------------------------------------------------------------------------------
    // Surrenders
    // ----------------------------------------------------------------------------------------------------------

    override fun requestSurrender(installationId: UUID, command: RequestSurrender): SurrenderRequestView {
        val installation = requireInstallation(installationId)
        authorise(installation.legalEntityId, Role.BACK_OFFICE, Role.REGISTRY_OPERATOR)
        val product = (command.productCode ?: installation.eligibleProducts.first()).uppercase()
        if (product !in installation.eligibleProducts) {
            throw ValidationException(
                "${installation.name} surrenders ${installation.eligibleProducts.joinToString()}, not $product",
            )
        }
        val owed = compliance(installation, command.complianceYear).outstanding
        val quantity = command.quantity ?: owed
        if (quantity <= 0) throw ValidationException("Nothing to surrender for ${command.complianceYear}")
        val key = "surrender:${installation.id}:${command.complianceYear}:${repository.requestCount(installation.id, command.complianceYear) + 1}"
        val instruction = registry.submitSurrender(SubmitSurrender(installation.registryAccountId, product, quantity, command.complianceYear, key))
        val request = SurrenderRequestView(
            Ids.newId(),
            installation.id,
            command.complianceYear,
            product,
            quantity,
            instruction.id,
            SurrenderRequestStatus.SUBMITTED,
            actorProvider.current().userId,
            clock.instant(),
        )
        repository.insertRequest(request, key)
        return request
    }

    /** A surrender on an installation's account counts for its compliance year, or else the earliest year still owed. */
    @EventListener
    fun on(event: HoldingChanged) {
        val movement = event.movement
        if (movement.type != AllowanceMovementType.SURRENDER) return
        val installation = repository.installationByAccount(event.holding.registryAccountId) ?: return
        val year = movement.complianceYear ?: earliestOpenYear(installation, movement.effectiveDate)
        val quantity = kotlin.math.abs(movement.quantity)
        val recorded = repository.insertSurrender(
            SurrenderRecord(movement.seq, installation.id, year, event.holding.productCode, quantity, movement.complianceYear != null, movement.effectiveDate),
        )
        if (recorded) events.publishEvent(SurrenderAttributed(installation.id, installation.legalEntityId, year, quantity, movement.seq))
    }

    private fun earliestOpenYear(installation: InstallationView, on: LocalDate): Int =
        (installation.validFrom.year until on.year).firstOrNull { compliance(installation, it).outstanding > 0 } ?: (on.year - 1)

    @EventListener
    fun on(event: InstructionConfirmed) {
        repository.setRequestStatus(event.instruction.id, SurrenderRequestStatus.CONFIRMED)
    }

    @EventListener
    fun on(event: InstructionFailed) {
        repository.setRequestStatus(event.instruction.id, SurrenderRequestStatus.FAILED)
    }

    @EventListener
    fun on(event: InstructionCancelled) {
        repository.setRequestStatus(event.instruction.id, SurrenderRequestStatus.CANCELLED)
    }

    // ----------------------------------------------------------------------------------------------------------
    // Compliance position
    // ----------------------------------------------------------------------------------------------------------

    private fun compliance(installation: InstallationView, year: Int): InstallationCompliance {
        val (emissions, basis) = EmissionsBasisRule.of(repository.emissions(installation.id, year))
        val surrendered = repository.surrendered(installation.id, year)
        val held = installation.eligibleProducts.sumOf { holdings.holding(installation.registryAccountId, it)?.quantity ?: 0 }
        val deadline = deadlines.dueOn(DeadlineKind.SURRENDER, year)
        val status = when {
            basis != EmissionsBasis.VERIFIED && LocalDate.now(clock).isAfter(deadline) && surrendered < emissions -> ComplianceStatus.NON_COMPLIANT
            basis != EmissionsBasis.VERIFIED -> ComplianceStatus.OPEN
            surrendered >= emissions -> ComplianceStatus.COMPLIANT
            LocalDate.now(clock).isAfter(deadline) -> ComplianceStatus.NON_COMPLIANT
            else -> ComplianceStatus.VERIFIED
        }
        return InstallationCompliance(installation, year, emissions, basis, surrendered, held, freeAllocationOf(installation, year), deadline, status)
    }

    private fun freeAllocationOf(installation: InstallationView, year: Int): FreeAllocationView? {
        val set = repository.freeAllocation(installation.id, year) ?: return null
        val received = installation.eligibleProducts.sumOf { product ->
            holdings.holding(installation.registryAccountId, product)?.let { h ->
                holdings.movements(h.id).filter { it.type == AllowanceMovementType.FREE_ALLOCATION && (it.complianceYear ?: it.effectiveDate.year) == year }
                    .sumOf { it.quantity }
            } ?: 0L
        }
        return FreeAllocationView(installation.id, year, set.allocated, set.adjusted, set.status, received, set.note)
    }

    @Transactional(readOnly = true)
    override fun compliance(installationId: UUID, complianceYear: Int): InstallationCompliance {
        val installation = requireInstallation(installationId)
        readable(installation.legalEntityId)
        return compliance(installation, complianceYear)
    }

    @Transactional(readOnly = true)
    override fun entityCompliance(legalEntityId: UUID, complianceYear: Int): EntityCompliance {
        readable(legalEntityId)
        val installations = repository.installations(legalEntityId, activeOnly = false)
            .filter { it.validFrom.year <= complianceYear && (it.validTo == null || it.validTo.year >= complianceYear) }
        return EntityCompliance(
            legalEntityId,
            complianceYear,
            deadlines.dueOn(DeadlineKind.SURRENDER, complianceYear),
            installations.map { compliance(it, complianceYear) },
            installations.map { it.registryAccountId }.toSet(),
        )
    }

    @Transactional(readOnly = true)
    override fun deadlines(legalEntityId: UUID, from: LocalDate, to: LocalDate): List<DeadlineView> {
        readable(legalEntityId)
        return repository.installations(legalEntityId, activeOnly = true).flatMap { deadlinesOf(it, from, to) }.sortedBy { it.dueOn }
    }

    /** Each deadline due between [from] and [to] for an installation, with whether it has been met. */
    fun deadlinesOf(installation: InstallationView, from: LocalDate, to: LocalDate): List<DeadlineView> =
        (from.year - 1..to.year).filter { it >= installation.validFrom.year }.flatMap { year ->
            DeadlineKind.entries.map { kind ->
                val due = deadlines.dueOn(kind, year)
                val met = when (kind) {
                    DeadlineKind.VERIFIED_EMISSIONS -> EmissionsBasisRule.of(repository.emissions(installation.id, year)).second == EmissionsBasis.VERIFIED
                    DeadlineKind.FREE_ALLOCATION -> freeAllocationOf(installation, year)?.let { it.outstanding == 0L } ?: true
                    DeadlineKind.SURRENDER -> compliance(installation, year).let { it.emissionsBasis == EmissionsBasis.VERIFIED && it.outstanding == 0L }
                }
                DeadlineView(installation.id, installation.name, kind, year, due, met)
            }
        }.filter { !it.dueOn.isBefore(from) && !it.dueOn.isAfter(to) }

    @Transactional(readOnly = true)
    override fun installations(legalEntityId: UUID?, activeOnly: Boolean): List<InstallationView> =
        repository.installations(legalEntityId, activeOnly).filter { actorProvider.current().canAccess(it.legalEntityId) }

    @Transactional(readOnly = true)
    override fun installation(id: UUID): InstallationView? = repository.installation(id)?.also { readable(it.legalEntityId) }

    @Transactional(readOnly = true)
    override fun emissions(installationId: UUID, complianceYear: Int?): List<EmissionRecordView> {
        readable(requireInstallation(installationId).legalEntityId)
        return repository.emissions(installationId, complianceYear)
    }

    @Transactional(readOnly = true)
    override fun freeAllocation(installationId: UUID, year: Int): FreeAllocationView? {
        val installation = requireInstallation(installationId)
        readable(installation.legalEntityId)
        return freeAllocationOf(installation, year)
    }

    @Transactional(readOnly = true)
    override fun surrenderRequests(installationId: UUID): List<SurrenderRequestView> {
        readable(requireInstallation(installationId).legalEntityId)
        return repository.requests(installationId)
    }

    private fun requireInstallation(id: UUID): InstallationView = repository.installation(id) ?: throw NotFoundException("Installation", id)

    private fun authorise(legalEntityId: UUID, vararg roles: Role) {
        val actor = actorProvider.current()
        actor.requireRole(*roles)
        actor.requireEntity(legalEntityId)
    }

    private fun readable(legalEntityId: UUID) = actorProvider.current().requireEntity(legalEntityId)
}
