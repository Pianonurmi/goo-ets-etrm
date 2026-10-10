package com.pianonurmi.etrm.product.domain

import com.pianonurmi.etrm.product.api.criteria.BoundCriterion

/**
 * Decides whether every certificate matching [narrow] also matches [broad], on bound criteria. The test is sound but
 * not complete: `true` is a proof, `false` only means no proof was found. That is what mapping an ad-hoc delivery
 * criterion to a standard bucket needs, since a wrong mapping would value a trade on the wrong curve.
 *
 * NULL semantics follow [CriteriaEvaluator]: a comparison against a missing value is false. `Not` is therefore only
 * matched structurally; contraposition is not valid when values can be missing.
 */
object CriteriaImplication {
    fun implies(narrow: BoundCriterion, broad: BoundCriterion): Boolean = when {
        broad == BoundCriterion.MatchAll || narrow is BoundCriterion.MatchNone || narrow == broad -> true
        broad is BoundCriterion.All -> broad.of.all { implies(narrow, it) }
        narrow is BoundCriterion.AnyOf -> narrow.of.all { implies(it, broad) }
        narrow is BoundCriterion.All -> narrow.of.any { implies(it, broad) } || (broad is BoundCriterion.AnyOf && broad.of.any { implies(narrow, it) })
        broad is BoundCriterion.AnyOf -> broad.of.any { implies(narrow, it) }
        else -> atom(narrow, broad)
    }

    private fun atom(narrow: BoundCriterion, broad: BoundCriterion): Boolean = when (narrow) {
        is BoundCriterion.Equals -> when (broad) {
            is BoundCriterion.Equals -> broad.attribute == narrow.attribute && broad.value == narrow.value
            is BoundCriterion.InSet -> broad.attribute == narrow.attribute && narrow.value in broad.values
            else -> false
        }
        is BoundCriterion.InSet -> when (broad) {
            is BoundCriterion.InSet -> broad.attribute == narrow.attribute && broad.values.containsAll(narrow.values)
            is BoundCriterion.Equals -> broad.attribute == narrow.attribute && narrow.values == setOf(broad.value)
            else -> false
        }
        is BoundCriterion.NumberRange ->
            broad is BoundCriterion.NumberRange && broad.attribute == narrow.attribute &&
                (broad.min == null || (narrow.min != null && narrow.min >= broad.min)) &&
                (broad.max == null || (narrow.max != null && narrow.max <= broad.max))
        is BoundCriterion.DateRange ->
            broad is BoundCriterion.DateRange && broad.attribute == narrow.attribute &&
                (broad.from == null || (narrow.from != null && !narrow.from.isBefore(broad.from))) &&
                (broad.toExclusive == null || (narrow.toExclusive != null && !narrow.toExclusive.isAfter(broad.toExclusive)))
        is BoundCriterion.PathDescendantOf ->
            broad is BoundCriterion.PathDescendantOf && broad.attribute == narrow.attribute &&
                (narrow.ancestorPath == broad.ancestorPath || narrow.ancestorPath.startsWith(broad.ancestorPath + "."))
        is BoundCriterion.LabelsAll -> when (broad) {
            is BoundCriterion.LabelsAll -> narrow.labels.containsAll(broad.labels)
            is BoundCriterion.LabelsAny -> narrow.labels.any { it in broad.labels }
            else -> false
        }
        is BoundCriterion.LabelsAny -> broad is BoundCriterion.LabelsAny && broad.labels.containsAll(narrow.labels)
        is BoundCriterion.ProductionPeriodWithin -> broad is BoundCriterion.ProductionPeriodWithin && broad.window.containsRange(narrow.window)
        else -> false
    }
}
