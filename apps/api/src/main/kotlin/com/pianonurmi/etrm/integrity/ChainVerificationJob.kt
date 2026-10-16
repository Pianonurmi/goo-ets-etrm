package com.pianonurmi.etrm.integrity

import com.pianonurmi.etrm.audit.api.AuditCommand
import com.pianonurmi.etrm.audit.api.AuditQuery
import com.pianonurmi.etrm.document.api.DocumentCommand
import com.pianonurmi.etrm.document.api.StoreDocument
import com.pianonurmi.etrm.inventory.api.AllowanceHoldingQuery
import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.ledger.api.LedgerQuery
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.workflow.api.WorkflowQuery
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `etrm.integrity.*`: the key that signs the daily chain-head digest (`ETRM_INTEGRITY_SIGNING_KEY`), and the minimum
 * age of an archive cut.
 */
@ConfigurationProperties("etrm.integrity")
data class IntegrityProperties(
    val signingKey: String? = null,
    /** How old an archive cut must at least be; the longest retention of the books by default (data-protection.md). */
    val archiveMinAge: Period = Period.ofYears(10),
)

@Configuration
@EnableConfigurationProperties(IntegrityProperties::class)
class IntegrityConfiguration

/** What one verification found; every list names the records whose chain does not verify. */
data class ChainReport(
    val at: Instant,
    val auditBroken: List<Long>,
    val auditUnsealed: Long,
    val auditHead: String?,
    val lotsBroken: List<UUID>,
    val holdingsBroken: List<UUID>,
    val journalBroken: Map<String, List<UUID>>,
    /** Approval tasks whose history (`task_event`) does not verify. */
    val tasksBroken: List<UUID> = emptyList(),
    val archiveCut: Instant? = null,
) {
    val ok: Boolean
        get() = auditBroken.isEmpty() && auditUnsealed == 0L && lotsBroken.isEmpty() && holdingsBroken.isEmpty() &&
            journalBroken.values.all { it.isEmpty() } && tasksBroken.isEmpty()
}

/**
 * Verifies every hash chain each night (review finding S6): the audit trail's seals, the lot journal, the allowance
 * movements, each legal entity's journal and the approval tasks' histories. The gauge `etrm.integrity.chain.ok{chain}` is 1 when a chain verified
 * and 0 when it did not; the alert rule fires on 0. The result goes to the audit trail, and a digest with the audit
 * head (which, through the events it seals, covers the postings and lot events of the day) is stored as a document,
 * signed with `etrm.integrity.signing-key` when one is configured, for the operator to keep outside the database.
 */
@Component
class ChainVerificationJob(
    private val auditQuery: AuditQuery,
    private val audit: AuditCommand,
    private val ledger: LedgerQuery,
    private val lots: LotQuery,
    private val holdings: AllowanceHoldingQuery,
    private val tasks: WorkflowQuery,
    private val referenceData: ReferenceDataQuery,
    private val documents: DocumentCommand,
    private val properties: IntegrityProperties,
    private val clock: Clock,
    meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(ChainVerificationJob::class.java)
    private val status = ConcurrentHashMap<String, Double>()

    init {
        CHAINS.forEach { chain ->
            Gauge.builder("etrm.integrity.chain.ok") { status[chain] ?: Double.NaN }
                .description("1 when the chain verified in the last check, 0 when it did not")
                .tag("chain", chain)
                .register(meters)
        }
    }

    @Scheduled(cron = "\${etrm.integrity.verify-cron:0 40 2 * * *}", zone = "UTC")
    @SchedulerLock(name = "integrity-chain-verification", lockAtMostFor = "PT2H")
    fun run(): ChainReport {
        val report = verify()
        val digest = digest(report)
        val date = LocalDate.ofInstant(report.at, ZoneOffset.UTC)
        val document = documents.store(
            StoreDocument(
                kind = DIGEST_KIND,
                ownerType = DIGEST_OWNER,
                ownerRef = UUID.nameUUIDFromBytes("integrity-digest:$date".toByteArray()),
                legalEntityId = null,
                title = "Chain heads $date",
                fileName = "chain-heads-$date.json",
                mimeType = "application/json",
                bytes = digest.toByteArray(),
            ),
        )
        audit.record(EVENT_TYPE, DIGEST_OWNER, document.id.toString(), summary(report) + mapOf("documentId" to document.id, "sha256" to document.sha256))
        return report
    }

    /** Verifies every chain and updates the gauges, without storing a digest (the nightly [run], and the DR drill). */
    fun verify(): ChainReport {
        audit.seal(catchUp = true)
        val trail = auditQuery.verifyTrail()
        val report = ChainReport(
            at = clock.instant(),
            auditBroken = trail.brokenSeqs,
            auditUnsealed = trail.unsealed,
            auditHead = trail.head,
            lotsBroken = lots.verifyJournal(),
            holdingsBroken = holdings.verifyMovements(),
            journalBroken = referenceData.legalEntities().associate { it.code to ledger.verifyJournal(it.id) },
            tasksBroken = tasks.verifyEvents(),
            archiveCut = auditQuery.archiveCut(),
        )
        status["audit"] = flag(report.auditBroken.isEmpty() && report.auditUnsealed == 0L)
        status["lots"] = flag(report.lotsBroken.isEmpty())
        status["allowances"] = flag(report.holdingsBroken.isEmpty())
        status["journal"] = flag(report.journalBroken.values.all { it.isEmpty() })
        status["tasks"] = flag(report.tasksBroken.isEmpty())
        if (report.ok) {
            log.info("All hash chains verify; audit head {}", report.auditHead)
        } else {
            log.error("Hash chains do not verify: {}", summary(report))
        }
        return report
    }

    private fun summary(report: ChainReport): Map<String, Any?> = mapOf(
        "ok" to report.ok,
        "auditHead" to report.auditHead,
        "auditBroken" to report.auditBroken.take(MAX_LISTED),
        "auditUnsealed" to report.auditUnsealed,
        "lotsBroken" to report.lotsBroken.take(MAX_LISTED),
        "holdingsBroken" to report.holdingsBroken.take(MAX_LISTED),
        "journalBroken" to report.journalBroken.filterValues { it.isNotEmpty() }.mapValues { it.value.take(MAX_LISTED) },
        "tasksBroken" to report.tasksBroken.take(MAX_LISTED),
        "archiveCut" to report.archiveCut?.toString(),
    )

    /** The digest: the heads and results as canonical JSON, with an HMAC-SHA256 over it when a key is configured. */
    internal fun digest(report: ChainReport): String {
        val payload = Json.canonical(mapOf("at" to report.at.toString()) + summary(report))
        val signature = properties.signingKey?.takeIf { it.isNotBlank() }?.let { key ->
            val mac = Mac.getInstance(HMAC)
            mac.init(SecretKeySpec(key.toByteArray(), HMAC))
            HexFormat.of().formatHex(mac.doFinal(payload.toByteArray()))
        }
        return Json.canonical(mapOf("payload" to Json.mapper.readTree(payload), "algorithm" to signature?.let { HMAC }, "signature" to signature))
    }

    private fun flag(ok: Boolean) = if (ok) 1.0 else 0.0

    companion object {
        val CHAINS = listOf("audit", "lots", "allowances", "journal", "tasks")
        const val EVENT_TYPE = "ChainVerification"
        const val DIGEST_KIND = "INTEGRITY_DIGEST"
        const val DIGEST_OWNER = "Integrity"
        private const val HMAC = "HmacSHA256"
        private const val MAX_LISTED = 50
    }
}
