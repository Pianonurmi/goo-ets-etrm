package com.pianonurmi.etrm.platform.db

import com.pianonurmi.etrm.platform.time.LocalDateRange
import org.jooq.postgres.extensions.types.LocalDateRange as PgLocalDateRange

/**
 * A PostgreSQL `daterange` column read as a closed-open [LocalDateRange]. The columns that hold periods are bounded on
 * both sides (a check constraint or the writer guarantees it), so an unbounded side is a broken row and fails loudly.
 */
fun PgLocalDateRange.toLocalDateRange(column: String): LocalDateRange = LocalDateRange(
    checkNotNull(lower()) { "$column has no lower bound" },
    checkNotNull(upper()) { "$column has no upper bound" },
)
