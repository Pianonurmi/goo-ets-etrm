package com.pianonurmi.etrm.settlement

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Settlement of delivered trades: settlement items per delivery, VAT rules as data, invoices with gap-free numbers,
 * payments and netting. Posts nothing itself; the ledger listens to its events.
 */
@PackageInfo
@ApplicationModule(
    displayName = "Settlement",
    allowedDependencies = ["referencedata :: api", "product :: api", "trade :: api", "workflow :: api", "document :: api", "marketdata :: api"],
)
class SettlementModule
