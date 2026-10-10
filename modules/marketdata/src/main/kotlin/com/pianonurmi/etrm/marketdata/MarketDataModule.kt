package com.pianonurmi.etrm.marketdata

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Market data: instruments with vendor aliases, prices and FX rates (bitemporal), import formats as data, price
 * indices and their fixings, and the ECB reference rate adapter. Other modules read prices and rates through its API.
 */
@PackageInfo
@ApplicationModule(
    displayName = "Market data",
    allowedDependencies = ["referencedata :: api", "product :: api", "workflow :: api"],
)
class MarketDataModule
