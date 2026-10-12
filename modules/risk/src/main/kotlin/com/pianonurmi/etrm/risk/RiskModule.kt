package com.pianonurmi.etrm.risk

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/** Risk limits under four eyes, credit exposure and credit support, utilisation and breaches, pre-trade checks. */
@PackageInfo
@ApplicationModule(
    displayName = "Risk",
    allowedDependencies = [
        "referencedata :: api",
        "product :: api",
        "inventory :: api",
        "trade :: api",
        "marketdata :: api",
        "valuation :: api",
        "settlement :: api",
        "position :: api",
        "workflow :: api",
        "allocation :: api",
    ],
)
class RiskModule
