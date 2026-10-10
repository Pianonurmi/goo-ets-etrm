package com.pianonurmi.etrm.product.api.criteria

import java.math.BigDecimal
import java.util.UUID

/** Stored criteria reference. Criteria are immutable and deduplicated by content hash. */
data class CriteriaRef(val id: UUID, val hash: String, val specificity: BigDecimal, val summary: String)

interface CriteriaService {
    /** Parses a versioned JSON envelope, upcasting older schema versions. */
    fun parse(json: String): Criterion

    fun toJson(criterion: Criterion): String

    fun bind(criterion: Criterion, context: BindContext): BoundCriterion

    /** Builds a context from the current taxonomy for the given delivery date. */
    fun contextFor(deliveryDate: java.time.LocalDate): BindContext

    fun evaluate(bound: BoundCriterion, subject: CriteriaSubject): Boolean

    fun compile(bound: BoundCriterion, columns: CriteriaColumns): org.jooq.Condition

    /** Static specificity score: higher means more constrained. */
    fun specificity(criterion: Criterion): BigDecimal

    /** Human readable one-line summary for blotters and confirmations. */
    fun describe(criterion: Criterion): String

    fun store(criterion: Criterion, createdBy: String? = null): CriteriaRef

    fun load(id: UUID): Criterion

    fun ref(id: UUID): CriteriaRef
}
