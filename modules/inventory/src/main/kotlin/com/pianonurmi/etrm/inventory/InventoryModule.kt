package com.pianonurmi.etrm.inventory

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/** Certificate lots, allowance holdings and registry reconciliation. */
@PackageInfo
@ApplicationModule(
    displayName = "Inventory",
    allowedDependencies = ["taxonomy :: api", "referencedata :: api", "product :: api", "product :: criteria"],
)
class InventoryModule
