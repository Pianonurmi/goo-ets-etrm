package com.pianonurmi.etrm.product.api.criteria

import com.pianonurmi.etrm.platform.time.LocalDateRange
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Context needed to resolve delivery-relative and taxonomy-relative predicates. Implementations are built by the
 * product module from the taxonomy snapshots; tests can supply lambdas.
 */
data class BindContext(
    val deliveryDate: LocalDate,
    val regionCountries: (String) -> Set<String>,
    val taxonomyPath: (LotAttribute, String) -> String?,
)

/**
 * Criterion with every predicate resolved to concrete values. Both the in-memory evaluator and the SQL compiler
 * consume this form, which is what makes their equivalence testable.
 */
sealed interface BoundCriterion {
    data class Equals(val attribute: LotAttribute, val value: String) : BoundCriterion

    data class InSet(val attribute: LotAttribute, val values: Set<String>) : BoundCriterion

    data class NumberRange(val attribute: LotAttribute, val min: BigDecimal?, val max: BigDecimal?) : BoundCriterion

    data class DateRange(val attribute: LotAttribute, val from: LocalDate?, val toExclusive: LocalDate?) : BoundCriterion

    /** Resolved ltree path of the ancestor; a lot matches when its path is the ancestor path or below it. */
    data class PathDescendantOf(val attribute: LotAttribute, val ancestorPath: String) : BoundCriterion

    data class LabelsAny(val labels: Set<String>) : BoundCriterion

    data class LabelsAll(val labels: Set<String>) : BoundCriterion

    /** Production period contained in [window]. */
    data class ProductionPeriodWithin(val window: LocalDateRange) : BoundCriterion

    data class All(val of: List<BoundCriterion>) : BoundCriterion

    data class AnyOf(val of: List<BoundCriterion>) : BoundCriterion

    data class Not(val of: BoundCriterion) : BoundCriterion

    data object MatchAll : BoundCriterion

    /** A predicate that can never match, e.g. a taxonomy code that does not exist. Documented rather than silently true. */
    data class MatchNone(val reason: String) : BoundCriterion
}

/** Read access to one certificate lot (or lot class) for in-memory evaluation. */
interface CriteriaSubject {
    fun string(attribute: LotAttribute): String?

    fun bool(attribute: LotAttribute): Boolean?

    fun number(attribute: LotAttribute): BigDecimal?

    fun date(attribute: LotAttribute): LocalDate?

    /** ltree path of a taxonomy attribute, e.g. `RENEWABLE.WIND.WIND_ONSHORE`. */
    fun taxonomyPath(attribute: LotAttribute): String?

    val labels: Set<String>

    val productionPeriod: LocalDateRange
}

/** Column mapping supplied by the module that owns the lot table, so the compiler stays table-agnostic. */
interface CriteriaColumns {
    fun string(attribute: LotAttribute): org.jooq.Field<String?>

    fun bool(attribute: LotAttribute): org.jooq.Field<Boolean?>

    fun number(attribute: LotAttribute): org.jooq.Field<BigDecimal?>

    fun date(attribute: LotAttribute): org.jooq.Field<LocalDate?>

    /** ltree typed column. */
    fun taxonomyPath(attribute: LotAttribute): org.jooq.Field<*>

    /** text[] column. */
    val labels: org.jooq.Field<Array<String?>?>

    /** daterange typed column. */
    val productionPeriod: org.jooq.Field<*>
}
