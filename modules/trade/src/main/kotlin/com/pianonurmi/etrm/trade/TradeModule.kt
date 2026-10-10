package com.pianonurmi.etrm.trade

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/** Deal capture and trade lifecycle. */
@PackageInfo
@ApplicationModule(
    displayName = "Trade",
    allowedDependencies = ["referencedata :: api", "product :: api", "product :: criteria", "workflow :: api", "document :: api"],
)
class TradeModule
