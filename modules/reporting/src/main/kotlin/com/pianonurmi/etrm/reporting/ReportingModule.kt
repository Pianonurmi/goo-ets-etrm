package com.pianonurmi.etrm.reporting

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/** Report catalogue and export log, EMIR trade events and completeness, MiFID ancillary activity. Nothing depends on it. */
@PackageInfo
@ApplicationModule(
    displayName = "Reporting",
    allowedDependencies = [
        "referencedata :: api",
        "product :: api",
        "trade :: api",
        "inventory :: api",
        "marketdata :: api",
        "valuation :: api",
        "etscompliance :: api",
        "risk :: api",
        "ledger :: api",
    ],
)
class ReportingModule
