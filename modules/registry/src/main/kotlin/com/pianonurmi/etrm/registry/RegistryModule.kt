package com.pianonurmi.etrm.registry

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/** Registry integration: transfer routes, instructions and their execution through registry gateways. */
@PackageInfo
@ApplicationModule(
    displayName = "Registry",
    allowedDependencies = ["referencedata :: api", "inventory :: api", "product :: api", "workflow :: api", "document :: api"],
)
class RegistryModule
