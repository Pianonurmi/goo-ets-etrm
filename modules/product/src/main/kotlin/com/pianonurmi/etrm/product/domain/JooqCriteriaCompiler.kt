package com.pianonurmi.etrm.product.domain

import com.pianonurmi.etrm.product.api.criteria.AttributeKind
import com.pianonurmi.etrm.product.api.criteria.BoundCriterion
import com.pianonurmi.etrm.product.api.criteria.CriteriaColumns
import org.jooq.Condition
import org.jooq.impl.DSL

/**
 * Compiles a bound criterion to a jOOQ [Condition] over the columns the inventory module exposes.
 * Three-valued SQL logic is normalised so that every leaf is either true or false, never NULL.
 */
object JooqCriteriaCompiler {
    fun compile(bound: BoundCriterion, c: CriteriaColumns): Condition = when (bound) {
        is BoundCriterion.Equals -> when (bound.attribute.kind) {
            AttributeKind.BOOLEAN -> nn(c.bool(bound.attribute).eq(bound.value.toBooleanStrict()))
            else -> nn(c.string(bound.attribute).eq(bound.value))
        }
        is BoundCriterion.InSet -> nn(c.string(bound.attribute).`in`(bound.values))
        is BoundCriterion.NumberRange -> {
            val field = c.number(bound.attribute)
            nn(
                DSL.and(
                    listOfNotNull(
                        bound.min?.let { field.ge(it) },
                        bound.max?.let { field.le(it) },
                    ),
                ),
            )
        }
        is BoundCriterion.DateRange -> {
            val field = c.date(bound.attribute)
            nn(
                DSL.and(
                    listOfNotNull(
                        bound.from?.let { field.ge(it) },
                        bound.toExclusive?.let { field.lt(it) },
                    ),
                ),
            )
        }
        is BoundCriterion.PathDescendantOf ->
            nn(DSL.condition("{0} <@ text2ltree({1})", c.taxonomyPath(bound.attribute), DSL.`val`(bound.ancestorPath)))
        is BoundCriterion.LabelsAny -> nn(DSL.condition("{0} && {1}::text[]", c.labels, DSL.`val`(bound.labels.toTypedArray())))
        is BoundCriterion.LabelsAll -> nn(DSL.condition("{0} @> {1}::text[]", c.labels, DSL.`val`(bound.labels.toTypedArray())))
        is BoundCriterion.ProductionPeriodWithin -> nn(
            DSL.condition(
                "{0} <@ daterange({1}, {2}, '[)')",
                c.productionPeriod,
                DSL.`val`(bound.window.start),
                DSL.`val`(bound.window.endExclusive),
            ),
        )
        is BoundCriterion.All -> DSL.and(bound.of.map { compile(it, c) })
        is BoundCriterion.AnyOf -> DSL.or(bound.of.map { compile(it, c) })
        is BoundCriterion.Not -> DSL.not(compile(bound.of, c))
        BoundCriterion.MatchAll -> DSL.trueCondition()
        is BoundCriterion.MatchNone -> DSL.falseCondition()
    }

    /** `COALESCE(cond, false)`: turns SQL NULL into false so NOT behaves like the in-memory evaluator. */
    private fun nn(condition: Condition): Condition = DSL.condition(DSL.coalesce(DSL.field(condition), DSL.inline(false)))
}
