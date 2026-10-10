package com.pianonurmi.etrm.etscompliance.domain

import com.pianonurmi.etrm.etscompliance.api.DeadlineKind
import com.pianonurmi.etrm.etscompliance.api.EmissionKind
import com.pianonurmi.etrm.etscompliance.api.EmissionRecordView
import com.pianonurmi.etrm.etscompliance.api.EmissionStatus
import com.pianonurmi.etrm.etscompliance.api.EmissionsBasis
import com.pianonurmi.etrm.etscompliance.api.FreeAllocationView
import com.pianonurmi.etrm.etscompliance.api.InstallationView
import com.pianonurmi.etrm.etscompliance.api.SetFreeAllocation
import com.pianonurmi.etrm.etscompliance.api.SurrenderRequestStatus
import com.pianonurmi.etrm.etscompliance.api.SurrenderRequestView
import java.time.LocalDate
import java.util.UUID

data class DeadlineRule(val kind: DeadlineKind, val effectiveFromYear: Int, val yearOffset: Int, val month: Int, val day: Int)

/** Regulatory deadlines from rules as data: the latest rule in force for the compliance year applies. */
class Deadlines(private val rules: List<DeadlineRule>) {
    fun dueOn(kind: DeadlineKind, complianceYear: Int): LocalDate {
        val rule = rules.filter { it.kind == kind && it.effectiveFromYear <= complianceYear }.maxByOrNull { it.effectiveFromYear }
            ?: error("No $kind deadline rule for $complianceYear")
        return LocalDate.of(complianceYear + rule.yearOffset, rule.month, rule.day)
    }
}

/** Which emissions count for a year: approved verified emissions, else the sum of monthly estimates, else the forecast. */
object EmissionsBasisRule {
    fun of(records: List<EmissionRecordView>): Pair<Long, EmissionsBasis> {
        val approved = records.filter { it.status == EmissionStatus.APPROVED }
        approved.firstOrNull { it.kind == EmissionKind.VERIFIED }?.let { return it.quantityTco2e to EmissionsBasis.VERIFIED }
        val monthly = approved.filter { it.kind == EmissionKind.MONTHLY_ESTIMATE }
        if (monthly.isNotEmpty()) return monthly.sumOf { it.quantityTco2e } to EmissionsBasis.MONTHLY_ESTIMATES
        return approved.firstOrNull { it.kind == EmissionKind.FORECAST }?.let { it.quantityTco2e to EmissionsBasis.FORECAST } ?: (0L to EmissionsBasis.NONE)
    }
}

data class SurrenderRecord(
    val movementSeq: Long,
    val installationId: UUID,
    val complianceYear: Int,
    val productCode: String,
    val quantity: Long,
    val explicit: Boolean,
    val surrenderedOn: LocalDate,
)

interface EtsComplianceRepository {
    fun installations(legalEntityId: UUID?, activeOnly: Boolean): List<InstallationView>

    fun installation(id: UUID): InstallationView?

    fun installationByAccount(registryAccountId: UUID): InstallationView?

    fun insertInstallation(installation: InstallationView)

    fun emissions(installationId: UUID, complianceYear: Int?): List<EmissionRecordView>

    fun emission(id: UUID, lock: Boolean = false): EmissionRecordView?

    fun insertEmission(record: EmissionRecordView)

    fun setEmissionStatus(id: UUID, status: EmissionStatus, approvedBy: String? = null, at: java.time.Instant? = null)

    fun freeAllocation(installationId: UUID, year: Int): SetFreeAllocation?

    fun upsertFreeAllocation(installationId: UUID, year: Int, allocation: SetFreeAllocation, actor: String)

    fun surrendered(installationId: UUID, complianceYear: Int): Long

    /** Records a surrender once per movement; returns whether it was recorded now. */
    fun insertSurrender(record: SurrenderRecord): Boolean

    fun insertRequest(request: SurrenderRequestView, idempotencyKey: String)

    fun requestByKey(idempotencyKey: String): SurrenderRequestView?

    fun requestCount(installationId: UUID, complianceYear: Int): Int

    fun requests(installationId: UUID): List<SurrenderRequestView>

    fun setRequestStatus(instructionId: UUID, status: SurrenderRequestStatus): Boolean

    fun deadlineRules(): List<DeadlineRule>

    /** Records a deadline reminder stage once; returns whether it is new. */
    fun recordAlert(installationId: UUID, kind: DeadlineKind, complianceYear: Int, daysBefore: Int, taskSubject: UUID): Boolean

    /** Task subjects of the reminders raised for a deadline. */
    fun alertSubjects(installationId: UUID, kind: DeadlineKind, complianceYear: Int): List<UUID>
}
