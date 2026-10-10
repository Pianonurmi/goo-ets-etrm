package com.pianonurmi.etrm.registry.adapter.gateway

import com.pianonurmi.etrm.platform.text.Csv
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.registry.api.RegistryInstructionView
import com.pianonurmi.etrm.registry.domain.GatewayResult
import com.pianonurmi.etrm.registry.domain.RegistryGateway
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/** Test and demo gateway: every instruction is confirmed immediately with a synthetic reference. */
@Component
@Order(10)
class SimulatorRegistryGateway : RegistryGateway {
    private val counter = AtomicLong(1)

    override fun supports(registryCode: String, integration: String): Boolean = integration == "SIMULATOR"

    override fun execute(instruction: RegistryInstructionView): GatewayResult =
        GatewayResult.Confirmed("SIM-${"%08d".format(counter.getAndIncrement())}", mapOf("simulated" to true))
}

/**
 * Manual gateway for registries without an API (e.g. the Union Registry): the instruction becomes a task for a
 * registry operator who executes it in the registry's own UI (with the registry's four-eyes) and confirms here.
 */
@Component
@Order(20)
class ManualRegistryGateway : RegistryGateway {
    override fun supports(registryCode: String, integration: String): Boolean = integration == "MANUAL"

    override fun execute(instruction: RegistryInstructionView): GatewayResult = GatewayResult.Sent(null, mapOf("mode" to "manual"))
}

/**
 * File gateway: writes an instruction pack (CSV) to an outbox directory for upload to the registry; confirmation
 * arrives via statement import or operator confirmation.
 */
@Component
@Order(30)
class FileRegistryGateway(
    @Value("\${etrm.registry.outbox-dir:build/registry-outbox}") private val outboxDir: String,
    private val referenceData: ReferenceDataQuery,
) : RegistryGateway {
    private val log = LoggerFactory.getLogger(FileRegistryGateway::class.java)

    override fun supports(registryCode: String, integration: String): Boolean = integration == "FILE"

    override fun execute(instruction: RegistryInstructionView): GatewayResult = runCatching {
        val dir = Path.of(outboxDir, instruction.registryCode)
        Files.createDirectories(dir)
        val file = dir.resolve("${DateTimeFormatter.ISO_INSTANT.format(instruction.createdAt).replace(":", "")}-${instruction.instructionKey}.csv")
        val fromAccount = referenceData.requireRegistryAccount(instruction.fromAccountId).accountNumber
        Files.writeString(file, InstructionPack.csv(instruction, fromAccount))
        log.info("Wrote registry instruction pack {}", file)
        GatewayResult.Sent(null, mapOf("file" to file.toString()))
    }.getOrElse { GatewayResult.Failed("Could not write instruction pack: ${it.message}") }
}

/**
 * One instruction as an RFC 4180 CSV pack for upload or manual execution in the registry. Accounts are registry
 * account numbers (never internal ids); cancellation details are spread over their own columns.
 */
object InstructionPack {
    val HEADER = listOf(
        "instruction_key",
        "type",
        "registry",
        "from_account",
        "to_registry",
        "to_account",
        "cert_no_start",
        "cert_no_end",
        "quantity_mwh",
        "cancellation_purpose",
        "beneficiary_name",
        "beneficiary_id",
        "consumption_country",
        "consumption_period_start",
        "consumption_period_end",
    )

    fun csv(i: RegistryInstructionView, fromAccountNumber: String): String {
        val c = i.cancellation
        return Csv.write(
            HEADER,
            listOf(
                listOf(
                    i.instructionKey,
                    i.type.name,
                    i.registryCode,
                    fromAccountNumber,
                    i.toRegistryCode,
                    i.toAccountRef,
                    i.range.start,
                    i.range.end,
                    i.quantityMwh,
                    c?.purpose?.name,
                    c?.beneficiaryName,
                    c?.beneficiaryId,
                    c?.consumptionCountry,
                    c?.consumptionPeriod?.start,
                    c?.consumptionPeriod?.endExclusive,
                ),
            ),
        )
    }
}
