package com.pianonurmi.etrm.events

import com.pianonurmi.etrm.inventory.api.AcquisitionSource
import com.pianonurmi.etrm.inventory.api.CancellationDetails
import com.pianonurmi.etrm.inventory.api.CancellationPurpose
import com.pianonurmi.etrm.inventory.api.CertificateRange
import com.pianonurmi.etrm.inventory.api.LotReceived
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.LotView
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.product.api.EnergyCarrier
import com.pianonurmi.etrm.registry.api.InstructionConfirmed
import com.pianonurmi.etrm.registry.api.InstructionFailed
import com.pianonurmi.etrm.registry.api.InstructionStatus
import com.pianonurmi.etrm.registry.api.InstructionType
import com.pianonurmi.etrm.registry.api.RegistryInstructionView
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** Events that leave the application carry no beneficiary name or id (review finding S7); everything else stays. */
class ExternalPayloadTest {
    private val configuration = EventExternalizationConfig().eventExternalization()

    private val cancellation = CancellationDetails(
        purpose = CancellationPurpose.ON_BEHALF_OF_THIRD_PARTY,
        beneficiaryName = "Maija Meikäläinen",
        beneficiaryId = "FI-120345-678X",
        consumptionCountry = "FI",
        consumptionPeriod = LocalDateRange.ofMonth(YearMonth.of(2026, 3)),
        registryCancellationRef = "CXL-1",
    )

    @Test
    fun `a confirmed cancellation leaves without the beneficiary`() {
        val event = InstructionConfirmed(instruction(), lot().copy(status = LotStatus.CANCELLED, cancellation = cancellation))

        val mapped = configuration.map(event) as InstructionConfirmed
        mapped.instruction.cancellation shouldBe cancellation.copy(beneficiaryName = null, beneficiaryId = null)
        mapped.lot!!.cancellation shouldBe cancellation.copy(beneficiaryName = null, beneficiaryId = null)
        mapped.eventId shouldBe event.eventId

        val json = Json.mapper.writeValueAsString(mapped)
        json shouldNotContain "Meikäläinen"
        json shouldNotContain "120345"
        json shouldContain "CXL-1"
        json shouldContain "ON_BEHALF_OF_THIRD_PARTY"
        configuration.getHeadersFor(mapped)["etrm-event-type"] shouldBe "InstructionConfirmed"
        configuration.getHeadersFor(mapped)["etrm-event-id"] shouldBe event.eventId.toString()
    }

    @Test
    fun `a failed cancellation and a lot event leave without the beneficiary`() {
        val failed = configuration.map(InstructionFailed(instruction(), "refused")) as InstructionFailed
        failed.instruction.cancellation!!.beneficiaryName shouldBe null
        failed.instruction.cancellation!!.beneficiaryId shouldBe null

        val received = configuration.map(LotReceived(lot().copy(cancellation = cancellation))) as LotReceived
        received.lot.cancellation!!.beneficiaryName shouldBe null
        received.lot.cancellation!!.consumptionCountry shouldBe "FI"
    }

    @Test
    fun `events without a cancellation are unchanged`() {
        val event = LotReceived(lot())
        configuration.map(event) shouldBe event
    }

    private fun instruction() = RegistryInstructionView(
        id = UUID.randomUUID(),
        instructionKey = "plan-1-line-1",
        type = InstructionType.CANCELLATION,
        registryCode = "FINGRID",
        holderEntityId = UUID.randomUUID(),
        fromAccountId = UUID.randomUUID(),
        toRegistryCode = null,
        toAccountRef = null,
        lotId = UUID.randomUUID(),
        range = CertificateRange.of(1, 100),
        quantity = 100,
        cancellation = cancellation,
        planRef = null,
        lineRef = null,
        obligationRef = null,
        status = InstructionStatus.CONFIRMED,
        externalRef = "REG-1",
        attempts = 1,
        lastError = null,
        submittedBy = "ops",
        confirmedBy = "registry",
        createdAt = Instant.parse("2026-04-01T10:00:00Z"),
        sentAt = Instant.parse("2026-04-01T10:00:01Z"),
        confirmedAt = Instant.parse("2026-04-01T10:00:02Z"),
        version = 2,
    )

    private fun lot(): LotView {
        val id = UUID.randomUUID()
        val period = LocalDateRange.ofMonth(YearMonth.of(2026, 1))
        return LotView(
            id = id,
            holderEntityId = UUID.randomUUID(),
            registryAccountId = UUID.randomUUID(),
            registryCode = "FINGRID",
            issuanceRef = "ISS-$id",
            range = CertificateRange.of(1, 100),
            quantityMwh = 100,
            energyCarrier = EnergyCarrier.ELECTRICITY,
            certificateScheme = "EECS_GO",
            issuingBody = "IB",
            issuingCountry = "FI",
            deviceId = null,
            deviceGsrn = "GSRN-1",
            deviceName = "Hydro 1",
            deviceCountry = "FI",
            deviceRegion = null,
            deviceCommissioningDate = LocalDate.of(2015, 1, 1),
            deviceCapacityKw = BigDecimal(10_000),
            energySourceCode = "HYDRO",
            energySourcePath = "RENEWABLE/HYDRO",
            technologyCode = null,
            technologyPath = null,
            productionPeriod = period,
            issueDate = period.endExclusive.plusDays(10),
            expiryDate = period.endExclusive.plusMonths(12),
            supportProduction = false,
            supportInvestment = false,
            supportSchemeCode = null,
            labels = emptySet(),
            status = LotStatus.HELD,
            rootLotId = id,
            parentLotId = null,
            acquisitionSource = AcquisitionSource.ISSUANCE,
            acquisitionRef = null,
            acquisitionDate = period.endExclusive,
            costBasisPerMwh = BigDecimal("1.20"),
            costCurrency = "EUR",
            reservationRef = null,
            instructionRef = null,
            attributes = emptyMap(),
            cancellation = null,
            version = 1,
        )
    }
}
