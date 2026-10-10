package com.pianonurmi.etrm.product.api

import com.pianonurmi.etrm.platform.money.QuantityUnit
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/** Top-level product families handled by the system. */
enum class ProductFamily { GUARANTEE_OF_ORIGIN, EMISSION_ALLOWANCE }

/** Energy carriers for guarantees of origin. Electricity is in scope; others are modelled for later increments. */
enum class EnergyCarrier(val unit: QuantityUnit) {
    ELECTRICITY(QuantityUnit.MWH),
    GAS(QuantityUnit.MWH),
    HYDROGEN(QuantityUnit.MWH),
    HEAT_COOLING(QuantityUnit.MWH),
}

/** Contractual instrument shapes. Physical GoO and allowance deliveries use SPOT or FORWARD. */
enum class InstrumentType { SPOT, FORWARD, FUTURE, OPTION, SWAP, AUCTION, INTERNAL_TRANSFER }

data class AllowanceProductView(
    val code: String,
    val name: String,
    val jurisdiction: String,
    val phase: String?,
    val unit: QuantityUnit,
    val registryCode: String,
    val active: Boolean,
)

interface ProductCatalogueQuery {
    fun allowanceProducts(activeOnly: Boolean = true): List<AllowanceProductView>

    fun allowanceProduct(code: String): AllowanceProductView?
}

/** A listed allowance futures contract: delivery on [deliveryDate] in lots of [contractSize]. */
data class FuturesContractView(
    val code: String,
    val allowanceProductCode: String,
    /** ISO 10383 market identifier of the exchange, e.g. `XEEE` for EEX. */
    val venueMic: String,
    val deliveryMonth: YearMonth,
    val deliveryDate: LocalDate,
    val lastTradingDay: LocalDate,
    val contractSize: BigDecimal,
    val currency: String,
    val active: Boolean,
)

data class CreateFuturesContract(
    val code: String,
    val allowanceProductCode: String,
    val venueMic: String,
    val deliveryMonth: YearMonth,
    val deliveryDate: LocalDate,
    val lastTradingDay: LocalDate,
    val contractSize: BigDecimal,
    val currency: String = "EUR",
)

interface FuturesContractQuery {
    fun futuresContracts(allowanceProductCode: String? = null, activeOnly: Boolean = true): List<FuturesContractView>

    fun futuresContract(code: String): FuturesContractView?
}

interface FuturesContractCommand {
    fun create(command: CreateFuturesContract): FuturesContractView

    fun deactivate(code: String): FuturesContractView
}
