package com.pianonurmi.etrm.valuation

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/** Mark-to-market of open trades against official curves, end-of-day runs with sign-off, and P&L explain. */
@PackageInfo
@ApplicationModule(
    displayName = "Valuation",
    allowedDependencies = [
        "referencedata :: api",
        "product :: api",
        "product :: criteria",
        "trade :: api",
        "marketdata :: api",
        "workflow :: api",
    ],
)
class ValuationModule
