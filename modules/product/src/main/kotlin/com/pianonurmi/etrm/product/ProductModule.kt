package com.pianonurmi.etrm.product

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/** Product catalogue and the GoO criteria language. */
@PackageInfo
@ApplicationModule(displayName = "Product", allowedDependencies = ["taxonomy :: api"])
class ProductModule
