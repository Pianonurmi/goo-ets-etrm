package com.pianonurmi.etrm.allocation

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/** Matching engine: assigns certificate lots to obligations and reports coverage. */
@PackageInfo
@ApplicationModule(
    displayName = "Allocation",
    allowedDependencies = [
        "referencedata :: api",
        "product :: api",
        "product :: criteria",
        "inventory :: api",
        "trade :: api",
        "registry :: api",
        "workflow :: api",
    ],
)
class AllocationModule
