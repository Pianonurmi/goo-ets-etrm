package com.pianonurmi.etrm.referencedata

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/** Reference data: legal entities, books, counterparties, registries and accounts, production devices, calendars. */
@PackageInfo
@ApplicationModule(displayName = "Reference data", allowedDependencies = ["taxonomy :: api", "workflow :: api"])
class ReferenceDataModule
