package com.pianonurmi.etrm.product.domain

import com.pianonurmi.etrm.product.api.criteria.AttributeKind
import com.pianonurmi.etrm.product.api.criteria.BoundCriterion
import com.pianonurmi.etrm.product.api.criteria.CriteriaSubject

/**
 * In-memory evaluation with the same NULL semantics as the SQL compiler: a comparison against a missing value is
 * false, and `Not` of a missing-value comparison is therefore true (SQL side: `NOT COALESCE(x, false)`).
 */
object CriteriaEvaluator {
    fun matches(bound: BoundCriterion, subject: CriteriaSubject): Boolean = when (bound) {
        is BoundCriterion.Equals -> when (bound.attribute.kind) {
            AttributeKind.BOOLEAN -> subject.bool(bound.attribute)?.let { it == bound.value.toBooleanStrict() } ?: false
            else -> subject.string(bound.attribute)?.let { it == bound.value } ?: false
        }
        is BoundCriterion.InSet -> subject.string(bound.attribute)?.let { it in bound.values } ?: false
        is BoundCriterion.NumberRange -> subject.number(bound.attribute)?.let { v ->
            (bound.min == null || v >= bound.min) && (bound.max == null || v <= bound.max)
        } ?: false
        is BoundCriterion.DateRange -> subject.date(bound.attribute)?.let { d ->
            (bound.from == null || !d.isBefore(bound.from)) && (bound.toExclusive == null || d.isBefore(bound.toExclusive))
        } ?: false
        is BoundCriterion.PathDescendantOf -> subject.taxonomyPath(bound.attribute)?.let { path ->
            path == bound.ancestorPath || path.startsWith(bound.ancestorPath + ".")
        } ?: false
        is BoundCriterion.LabelsAny -> subject.labels.any { it in bound.labels }
        is BoundCriterion.LabelsAll -> bound.labels.all { it in subject.labels }
        is BoundCriterion.ProductionPeriodWithin -> bound.window.containsRange(subject.productionPeriod)
        is BoundCriterion.All -> bound.of.all { matches(it, subject) }
        is BoundCriterion.AnyOf -> bound.of.any { matches(it, subject) }
        is BoundCriterion.Not -> !matches(bound.of, subject)
        BoundCriterion.MatchAll -> true
        is BoundCriterion.MatchNone -> false
    }
}
