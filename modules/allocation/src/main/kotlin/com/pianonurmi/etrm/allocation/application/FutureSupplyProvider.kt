package com.pianonurmi.etrm.allocation.application

import com.pianonurmi.etrm.allocation.domain.FutureSupply
import com.pianonurmi.etrm.inventory.api.AcquisitionSource
import com.pianonurmi.etrm.inventory.api.CertificateRange
import com.pianonurmi.etrm.inventory.api.LotFilter
import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.LotView
import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.product.api.EnergyCarrier
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.product.api.criteria.CriteriaService
import com.pianonurmi.etrm.product.api.criteria.LotAttribute
import com.pianonurmi.etrm.referencedata.api.ProductionDeviceView
import com.pianonurmi.etrm.referencedata.api.ProductionForecastQuery
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.trade.api.DeliveryObligationView
import com.pianonurmi.etrm.trade.api.DeliveryStatus
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeQuery
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * ```yaml
 * etrm.allocation.future:
 *   horizon-months: 12          # expected issuance counted for production months up to this far ahead
 *   purchase-validity-months: 12  # a purchase's certificates are assumed valid this long after its delivery window opens
 * ```
 */
@ConfigurationProperties(prefix = "etrm.allocation.future")
data class AllocationFutureSettings(val horizonMonths: Int = 12, val purchaseValidityMonths: Long = 12)

/** Which figure of expected issuance to count: plans take the cautious P90, views the central P50. */
enum class Quantile { P50, P90 }

/**
 * Supply still to come for an entity (ADR 0038), as stand-in lots the problem can take:
 * - **contracted:** the open quantity of each GoO purchase schedule (BUY), available from its delivery date, assumed
 *   valid for [AllocationFutureSettings.purchaseValidityMonths] from the start of its delivery window;
 * - **expected:** the entity's own devices, per production month from last month to the horizon: the forecast (or
 *   capacity × load factor) less what has been issued for that month, available after the device's issuance lag and
 *   valid for twelve months after the production month.
 */
@Component
@EnableConfigurationProperties(AllocationFutureSettings::class)
class FutureSupplyProvider(
    private val trades: TradeQuery,
    private val referenceData: ReferenceDataQuery,
    private val forecasts: ProductionForecastQuery,
    private val lots: LotQuery,
    private val criteria: CriteriaService,
    private val clock: Clock,
    private val settings: AllocationFutureSettings = AllocationFutureSettings(),
) {
    fun supply(holderEntityId: UUID, quantile: Quantile): List<ProblemBuilder.FutureLot> =
        contracted(holderEntityId) + expected(holderEntityId, quantile)

    private fun contracted(holderEntityId: UUID): List<ProblemBuilder.FutureLot> =
        trades.openObligations(holderEntityId, Side.BUY, ProductFamily.GUARANTEE_OF_ORIGIN)
            .filter { it.status != DeliveryStatus.CANCELLED && it.status != DeliveryStatus.DELIVERED }
            .mapNotNull { schedule ->
                val open = (schedule.quantity - schedule.deliveredQuantity).setScale(0, RoundingMode.DOWN).toLong()
                // Schedules read in bulk carry the id of their criteria, not the criteria themselves.
                val criterion = schedule.criterion ?: schedule.criteriaId?.let(criteria::load)
                if (open <= 0 || criterion == null) return@mapNotNull null
                val future = FutureSupply.Contracted(
                    scheduleId = schedule.scheduleId,
                    tradeNumber = schedule.tradeNumber,
                    criterion = criterion,
                    bucketCode = schedule.bucketCode,
                    availableFrom = schedule.deliveryDate,
                )
                ProblemBuilder.FutureLot(future, purchaseStandIn(holderEntityId, schedule, future, open), open)
            }

    private fun expected(holderEntityId: UUID, quantile: Quantile): List<ProblemBuilder.FutureLot> {
        val today = LocalDate.now(clock)
        val from = YearMonth.from(today).minusMonths(1)
        val to = YearMonth.from(today).plusMonths(settings.horizonMonths.toLong())
        val registries = referenceData.registries().associate { it.id to it.code }
        val context = criteria.contextFor(today)
        return referenceData.productionDevices(ownDevicesOnly = true)
            .filter { it.active && it.ownerId == holderEntityId }
            .flatMap { device ->
                val issued = issuedByMonth(holderEntityId, device.gsrn)
                forecasts.expected(device.id, from, to).mapNotNull { expected ->
                    val already = issued[expected.month] ?: 0L
                    val p50 = (expected.expectedMwh - already).coerceAtLeast(0)
                    val p90 = (expected.p90Mwh - already).coerceAtLeast(0)
                    val quantity = if (quantile == Quantile.P90) p90 else p50
                    if (quantity <= 0) return@mapNotNull null
                    val future = FutureSupply.Expected(
                        deviceId = device.id,
                        gsrn = device.gsrn,
                        deviceName = device.name,
                        month = expected.month,
                        p50Mwh = p50,
                        p90Mwh = p90,
                        availableFrom = maxOf(today, expected.month.atEndOfMonth().plusDays(device.issuanceLagDays.toLong())),
                    )
                    val standIn = issuanceStandIn(holderEntityId, device, future, quantity, registries[device.registryId]) { attribute, code ->
                        context.taxonomyPath(attribute, code)
                    }
                    ProblemBuilder.FutureLot(future, standIn, quantity)
                }
            }
    }

    /** Certificates issued for the device per production month: received root lots in any status. */
    private fun issuedByMonth(holderEntityId: UUID, gsrn: String): Map<YearMonth, Long> =
        lots.lots(LotFilter(holderEntityId = holderEntityId, deviceGsrn = gsrn, limit = ISSUED_LOTS_LIMIT))
            .filter { it.parentLotId == null && it.acquisitionSource == AcquisitionSource.ISSUANCE }
            .groupBy { YearMonth.from(it.productionPeriod.start) }
            .mapValues { (_, issued) -> issued.sumOf { it.quantityMwh } }

    /** A purchase is only known by its criteria; the stand-in carries no certificate attributes and is never evaluated. */
    private fun purchaseStandIn(holderEntityId: UUID, schedule: DeliveryObligationView, future: FutureSupply, quantity: Long): LotView {
        val account = schedule.deliveryAccountId?.let { referenceData.registryAccount(it) }
        return standIn(
            holderEntityId = holderEntityId,
            ref = future.ref,
            quantity = quantity,
            registryAccountId = account?.id ?: idOf("account:${future.ref}"),
            registryCode = account?.registryCode ?: FutureSupply.UNKNOWN_REGISTRY,
            deviceGsrn = "PURCHASE-${schedule.tradeNumber}",
            country = UNKNOWN,
            productionPeriod = schedule.deliveryWindow,
            availableFrom = future.availableFrom,
            expiry = schedule.deliveryWindow.start.plusMonths(settings.purchaseValidityMonths),
        )
    }

    private fun issuanceStandIn(
        holderEntityId: UUID,
        device: ProductionDeviceView,
        future: FutureSupply.Expected,
        quantity: Long,
        registryCode: String?,
        path: (LotAttribute, String) -> String?,
    ): LotView = standIn(
        holderEntityId = holderEntityId,
        ref = future.ref,
        quantity = quantity,
        registryAccountId = idOf("account:${device.gsrn}"),
        registryCode = registryCode ?: FutureSupply.UNKNOWN_REGISTRY,
        deviceGsrn = device.gsrn,
        country = device.country,
        productionPeriod = LocalDateRange.ofMonth(future.month),
        availableFrom = future.availableFrom,
        // A GoO is valid for twelve months after the end of its production period.
        expiry = future.month.plusMonths(GOO_VALIDITY_MONTHS).atDay(1),
    ).copy(
        deviceId = device.id,
        deviceName = device.name,
        deviceRegion = device.regionCode,
        deviceCommissioningDate = device.commissioningDate,
        deviceCapacityKw = device.capacityKw,
        energySourceCode = device.energySourceCode,
        energySourcePath = path(LotAttribute.ENERGY_SOURCE, device.energySourceCode) ?: device.energySourceCode,
        technologyCode = device.technologyCode,
        technologyPath = path(LotAttribute.TECHNOLOGY, device.technologyCode),
        supportProduction = device.supportSchemeCodes.isNotEmpty(),
        supportInvestment = false,
        supportSchemeCode = device.supportSchemeCodes.firstOrNull(),
        labels = device.labelCodes,
    )

    @Suppress("LongParameterList") // a lot has many fields; the stand-in sets those every stand-in needs
    private fun standIn(
        holderEntityId: UUID,
        ref: String,
        quantity: Long,
        registryAccountId: UUID,
        registryCode: String,
        deviceGsrn: String,
        country: String,
        productionPeriod: LocalDateRange,
        availableFrom: LocalDate,
        expiry: LocalDate,
    ): LotView {
        val id = idOf(ref)
        return LotView(
            id = id,
            holderEntityId = holderEntityId,
            registryAccountId = registryAccountId,
            registryCode = registryCode,
            issuanceRef = ref,
            range = CertificateRange.of(1, quantity),
            quantityMwh = quantity,
            energyCarrier = EnergyCarrier.ELECTRICITY,
            certificateScheme = "EECS_GO",
            issuingBody = UNKNOWN,
            issuingCountry = country,
            deviceId = null,
            deviceGsrn = deviceGsrn,
            deviceName = null,
            deviceCountry = country,
            deviceRegion = null,
            deviceCommissioningDate = null,
            deviceCapacityKw = null,
            energySourceCode = UNKNOWN,
            energySourcePath = "",
            technologyCode = null,
            technologyPath = null,
            productionPeriod = productionPeriod,
            issueDate = availableFrom,
            expiryDate = expiry,
            supportProduction = null,
            supportInvestment = null,
            supportSchemeCode = null,
            labels = emptySet(),
            status = LotStatus.HELD,
            rootLotId = id,
            parentLotId = null,
            acquisitionSource = AcquisitionSource.ISSUANCE,
            acquisitionRef = ref,
            acquisitionDate = availableFrom,
            costBasisPerMwh = null,
            costCurrency = null,
            reservationRef = null,
            instructionRef = null,
            attributes = mapOf("futureSupply" to ref),
            cancellation = null,
            version = 0,
        )
    }

    private fun idOf(name: String): UUID = UUID.nameUUIDFromBytes(name.toByteArray())

    private companion object {
        const val UNKNOWN = "UNKNOWN"
        const val GOO_VALIDITY_MONTHS = 13L
        const val ISSUED_LOTS_LIMIT = 10_000
    }
}
