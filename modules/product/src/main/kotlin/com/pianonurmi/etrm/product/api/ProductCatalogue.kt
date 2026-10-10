package com.pianonurmi.etrm.product.api

import com.pianonurmi.etrm.platform.money.QuantityUnit

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
