package com.pianonurmi.etrm.etscompliance

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * EU ETS compliance: installations and aircraft operators, emissions and verified reports, free allocation,
 * surrenders by compliance year, deadlines, and the compliance position per installation.
 */
@PackageInfo
@ApplicationModule(
    displayName = "ETS compliance",
    allowedDependencies = ["referencedata :: api", "product :: api", "inventory :: api", "registry :: api", "workflow :: api"],
)
class EtsComplianceModule
