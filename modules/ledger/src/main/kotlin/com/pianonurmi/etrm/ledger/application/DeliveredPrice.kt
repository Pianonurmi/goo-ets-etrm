package com.pianonurmi.etrm.ledger.application

import com.pianonurmi.etrm.marketdata.api.IndexFixings
import com.pianonurmi.etrm.trade.api.DeliveryObligationView
import com.pianonurmi.etrm.trade.api.PriceType
import java.math.BigDecimal

/**
 * The unit price a delivery is made at, in the leg's currency: a fixed leg at its price, an index leg at the index
 * fixing for the delivery date plus the leg's spread (as settlement prices it). Null while that is not known, or for
 * a formula-priced leg: an index leg's `price` is only the spread and must never be booked as the price.
 */
internal fun deliveredUnitPrice(delivery: DeliveryObligationView, fixings: IndexFixings?): BigDecimal? = when (delivery.priceType) {
    PriceType.FIXED -> delivery.price
    PriceType.INDEX ->
        delivery.priceIndex
            ?.let { index -> fixings?.fixing(index, delivery.deliveryDate) }
            ?.takeIf { it.currency == delivery.priceCurrency.currencyCode }
            ?.let { it.value + delivery.price }
    PriceType.FORMULA -> null
}
