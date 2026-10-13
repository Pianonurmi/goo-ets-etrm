package com.pianonurmi.etrm.ledger

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/** Double-entry sub-ledger: chart of accounts, posting rules, journal, costing, periods, period end, GL export. */
@PackageInfo
@ApplicationModule(
    displayName = "Ledger",
    allowedDependencies = [
        "referencedata :: api",
        "product :: api",
        "product :: criteria",
        "inventory :: api",
        "trade :: api",
        "registry :: api",
        "allocation :: api",
        "settlement :: api",
        "marketdata :: api",
        "valuation :: api",
        "etscompliance :: api",
        "workflow :: api",
        "document :: api",
        "clearing :: api",
    ],
)
class LedgerModule
