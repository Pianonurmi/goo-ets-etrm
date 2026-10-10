package com.pianonurmi.etrm.etscompliance.api

import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class InstallationKind { INSTALLATION, AIRCRAFT_OPERATOR }

data class InstallationView(
    val id: UUID,
    val legalEntityId: UUID,
    val kind: InstallationKind,
    /** Identifier in the EU Transaction Log. */
    val eutlId: String,
    val permitId: String?,
    val name: String,
    val country: String,
    val activityCode: String?,
    val monitoringPlanRef: String?,
    val monitoringPlanVersion: String?,
    /** The operator or aircraft operator holding account in the Union Registry. */
    val registryAccountId: UUID,
    val eligibleProducts: List<String>,
    val validFrom: LocalDate,
    val validTo: LocalDate?,
    val active: Boolean,
    val version: Int,
)

data class RegisterInstallation(
    val legalEntityId: UUID,
    val kind: InstallationKind,
    val eutlId: String,
    val name: String,
    val country: String,
    val registryAccountId: UUID,
    val permitId: String? = null,
    val activityCode: String? = null,
    val monitoringPlanRef: String? = null,
    val monitoringPlanVersion: String? = null,
    val eligibleProducts: List<String>? = null,
    val validFrom: LocalDate,
)

enum class EmissionKind { FORECAST, MONTHLY_ESTIMATE, VERIFIED }

enum class EmissionStatus { PENDING_APPROVAL, APPROVED, REJECTED, SUPERSEDED }

data class EmissionRecordView(
    val id: UUID,
    val installationId: UUID,
    val complianceYear: Int,
    val kind: EmissionKind,
    val periodMonth: Int?,
    val quantityTco2e: Long,
    val status: EmissionStatus,
    val verifier: String?,
    val verifiedOn: LocalDate?,
    val documentRef: String?,
    val note: String?,
    val recordedBy: String,
    val recordedAt: Instant,
    val approvedBy: String?,
    val approvedAt: Instant?,
)

/** A forecast or a monthly estimate; both take effect at once and replace the previous one. */
data class RecordEmissions(
    val complianceYear: Int,
    val kind: EmissionKind,
    val quantityTco2e: Long,
    val periodMonth: Int? = null,
    val note: String? = null,
)

/** The verified annual emissions; they count only after a second person approves them. */
data class RecordVerifiedEmissions(
    val complianceYear: Int,
    val quantityTco2e: Long,
    val verifier: String,
    val verifiedOn: LocalDate,
    val documentRef: String? = null,
    val note: String? = null,
)

enum class FreeAllocationStatus { EXPECTED, ISSUED, WITHHELD }

data class FreeAllocationView(
    val installationId: UUID,
    val year: Int,
    val allocated: Long,
    val adjusted: Long?,
    val status: FreeAllocationStatus,
    /** Issued to the installation's account for the year (from the holding's movements). */
    val received: Long,
    val note: String?,
) {
    val entitlement: Long get() = adjusted ?: allocated

    val outstanding: Long get() = if (status == FreeAllocationStatus.WITHHELD) 0 else maxOf(0, entitlement - received)
}

data class SetFreeAllocation(
    val allocated: Long,
    val adjusted: Long? = null,
    val status: FreeAllocationStatus = FreeAllocationStatus.EXPECTED,
    val note: String? = null,
)

enum class SurrenderRequestStatus { SUBMITTED, CONFIRMED, FAILED, CANCELLED }

data class SurrenderRequestView(
    val id: UUID,
    val installationId: UUID,
    val complianceYear: Int,
    val productCode: String,
    val quantity: Long,
    val instructionId: UUID,
    val status: SurrenderRequestStatus,
    val requestedBy: String,
    val requestedAt: Instant,
)

/** A surrender for a compliance year; the quantity defaults to what is still owed. */
data class RequestSurrender(val complianceYear: Int, val quantity: Long? = null, val productCode: String? = null)

enum class EmissionsBasis { VERIFIED, MONTHLY_ESTIMATES, FORECAST, NONE }

enum class ComplianceStatus {
    /** Emissions not yet verified. */
    OPEN,

    /** Verified, not yet fully surrendered, deadline ahead. */
    VERIFIED,

    /** Verified emissions fully surrendered. */
    COMPLIANT,

    /** The surrender deadline passed with emissions not surrendered. */
    NON_COMPLIANT,
}

/** One installation's position for one compliance year. */
data class InstallationCompliance(
    val installation: InstallationView,
    val complianceYear: Int,
    val emissions: Long,
    val emissionsBasis: EmissionsBasis,
    val surrendered: Long,
    val held: Long,
    val freeAllocation: FreeAllocationView?,
    val surrenderDeadline: LocalDate,
    val status: ComplianceStatus,
) {
    val outstanding: Long get() = maxOf(0, emissions - surrendered)

    /** Held plus free allocation still to come, less what is owed: negative is short. */
    val net: Long get() = held + (freeAllocation?.outstanding ?: 0) - outstanding
}

/** The entity's totals over its installations, for the position ladder. */
data class EntityCompliance(
    val legalEntityId: UUID,
    val complianceYear: Int,
    /** Firm deliveries count towards the year only when they arrive by this date. */
    val surrenderDeadline: LocalDate,
    val installations: List<InstallationCompliance>,
    /** Registry accounts linked to installations; holdings elsewhere form the entity's pool. */
    val installationAccounts: Set<UUID>,
) {
    val verifiedEmissions: Long? get() = installations.takeIf {
        it.isNotEmpty() && it.all { i ->
            i.emissionsBasis == EmissionsBasis.VERIFIED
        }
    }?.sumOf { it.emissions }

    val estimatedEmissions: Long get() = installations.sumOf { it.emissions }

    val freeAllocationExpected: Long get() = installations.sumOf { it.freeAllocation?.entitlement ?: 0 }

    val freeAllocationReceived: Long get() = installations.sumOf { it.freeAllocation?.received ?: 0 }

    val surrendered: Long get() = installations.sumOf { it.surrendered }
}

enum class DeadlineKind { VERIFIED_EMISSIONS, FREE_ALLOCATION, SURRENDER }

data class DeadlineView(
    val installationId: UUID,
    val installationName: String,
    val kind: DeadlineKind,
    val complianceYear: Int,
    val dueOn: LocalDate,
    val met: Boolean,
)

interface EtsComplianceQuery {
    fun installations(legalEntityId: UUID? = null, activeOnly: Boolean = true): List<InstallationView>

    fun installation(id: UUID): InstallationView?

    fun emissions(installationId: UUID, complianceYear: Int? = null): List<EmissionRecordView>

    fun freeAllocation(installationId: UUID, year: Int): FreeAllocationView?

    fun compliance(installationId: UUID, complianceYear: Int): InstallationCompliance

    fun entityCompliance(legalEntityId: UUID, complianceYear: Int): EntityCompliance

    fun deadlines(legalEntityId: UUID, from: LocalDate, to: LocalDate): List<DeadlineView>

    fun surrenderRequests(installationId: UUID): List<SurrenderRequestView>
}

interface EtsComplianceCommand {
    fun register(command: RegisterInstallation): InstallationView

    fun recordEmissions(installationId: UUID, command: RecordEmissions): EmissionRecordView

    fun recordVerifiedEmissions(installationId: UUID, command: RecordVerifiedEmissions): EmissionRecordView

    fun setFreeAllocation(installationId: UUID, year: Int, command: SetFreeAllocation): FreeAllocationView

    fun requestSurrender(installationId: UUID, command: RequestSurrender): SurrenderRequestView
}

data class VerifiedEmissionsSubmitted(val record: EmissionRecordView, val installation: InstallationView, val metadata: EventMetadata = EventMetadata()) :
    DomainEvent by metadata

data class VerifiedEmissionsApproved(val record: EmissionRecordView, val installation: InstallationView, val metadata: EventMetadata = EventMetadata()) :
    DomainEvent by metadata

data class SurrenderAttributed(
    val installationId: UUID,
    val legalEntityId: UUID,
    val complianceYear: Int,
    val quantity: Long,
    val movementSeq: Long,
    val metadata: EventMetadata = EventMetadata(),
) : DomainEvent by metadata
