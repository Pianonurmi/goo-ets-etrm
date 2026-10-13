package com.pianonurmi.etrm.clearing

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Exchange clearing of EUA futures (ADR 0046): clearing accounts at a clearing house, daily variation margin from the
 * official settlement prices, the clearing member's statements with initial margin, and margin calls. Posts nothing
 * itself; the ledger listens to its events, and settlement and risk read its api.
 */
@PackageInfo
@ApplicationModule(
    displayName = "Clearing",
    allowedDependencies = ["referencedata :: api", "product :: api", "trade :: api", "marketdata :: api", "workflow :: api"],
)
class ClearingModule
