package com.pianonurmi.etrm.position

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/** Positions: GoO coverage view, expiry ladder, EU ETS compliance ladder, daily snapshots. */
@PackageInfo
@ApplicationModule(
    displayName = "Position",
    allowedDependencies = [
        "referencedata :: api", "product :: api", "product :: criteria", "inventory :: api", "trade :: api", "allocation :: api", "etscompliance :: api",
    ],
)
class PositionModule
