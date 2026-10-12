package com.pianonurmi.etrm.allocation.api

/**
 * How firm supply is (ADR 0038). Coverage and available to sell are reported cumulatively: [HELD] counts certificates
 * held now, [CONTRACTED] adds the open quantity of GoO purchases, [EXPECTED] adds the expected issuance of own devices.
 */
enum class SupplyTier { HELD, CONTRACTED, EXPECTED }
