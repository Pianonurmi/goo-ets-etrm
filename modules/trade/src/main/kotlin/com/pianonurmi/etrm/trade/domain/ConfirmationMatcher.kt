package com.pianonurmi.etrm.trade.domain

import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.product.api.criteria.Criterion
import com.pianonurmi.etrm.product.api.criteria.VintageRule
import com.pianonurmi.etrm.trade.api.ConfirmationTerms
import com.pianonurmi.etrm.trade.api.DifferenceSeverity
import com.pianonurmi.etrm.trade.api.FieldDifference
import com.pianonurmi.etrm.trade.api.LegTerms
import com.pianonurmi.etrm.trade.api.PartyTerms
import java.math.BigDecimal
import java.time.temporal.ChronoUnit

/** How far confirmed numbers and dates may differ and still match; set per product family. */
data class ConfirmationTolerance(
    val price: BigDecimal = BigDecimal("0.0001"),
    val amount: BigDecimal = BigDecimal("0.01"),
    val quantity: BigDecimal = BigDecimal.ZERO,
    val deliveryDateDays: Long = 0,
)

/** Field-level comparison of our confirmation with the counterparty's. Differences of severity NOTE do not block a match. */
object ConfirmationMatcher {
    fun compare(ours: ConfirmationTerms, theirs: ConfirmationTerms, tolerance: ConfirmationTolerance): List<FieldDifference> = buildList {
        exact("tradeDate", ours.tradeDate, theirs.tradeDate)
        exact("productFamily", ours.productFamily, theirs.productFamily)
        theirs.tradeRef?.let { exact("tradeRef", ours.tradeRef, it) }
        if (ours.counterpartyTradeRef != null && theirs.counterpartyTradeRef != null) {
            exact("counterpartyTradeRef", ours.counterpartyTradeRef, theirs.counterpartyTradeRef, DifferenceSeverity.NOTE)
        }
        if (ours.masterAgreement != null && theirs.masterAgreement != null) {
            text("masterAgreement", ours.masterAgreement, theirs.masterAgreement, DifferenceSeverity.NOTE)
        }
        if (ours.legs.size != theirs.legs.size) {
            add(FieldDifference("legs", ours.legs.size.toString(), theirs.legs.size.toString()))
            return@buildList
        }
        val theirLegs = theirs.legs.associateBy { it.legNo }
        ours.legs.forEach { leg ->
            val other = theirLegs[leg.legNo]
            if (other == null) add(FieldDifference("legs[${leg.legNo}]", "present", null)) else addAll(compareLeg(leg, other, tolerance))
        }
    }

    fun matches(differences: List<FieldDifference>): Boolean = differences.none { it.severity == DifferenceSeverity.MISMATCH }

    private fun compareLeg(ours: LegTerms, theirs: LegTerms, tolerance: ConfirmationTolerance): List<FieldDifference> = buildList {
        val p = "legs[${ours.legNo}]"
        party("$p.seller", ours.seller, theirs.seller)
        party("$p.buyer", ours.buyer, theirs.buyer)
        text("$p.product", ours.product, theirs.product)
        text("$p.unit", ours.unit, theirs.unit)
        text("$p.currency", ours.currency, theirs.currency)
        number("$p.quantity", ours.quantity, theirs.quantity, tolerance.quantity)
        number("$p.price", ours.price, theirs.price, tolerance.price)
        number("$p.amount", ours.amount, theirs.amount, tolerance.amount)
        account("$p.sellerAccount", ours.sellerAccount, theirs.sellerAccount)
        account("$p.buyerAccount", ours.buyerAccount, theirs.buyerAccount)
        if (ours.deliveries.size != theirs.deliveries.size) {
            add(FieldDifference("$p.deliveries", ours.deliveries.size.toString(), theirs.deliveries.size.toString()))
        } else {
            ours.deliveries.sortedBy { it.deliveryDate }.zip(theirs.deliveries.sortedBy { it.deliveryDate }).forEachIndexed { i, (o, t) ->
                val d = "$p.deliveries[${i + 1}]"
                if (kotlin.math.abs(ChronoUnit.DAYS.between(o.deliveryDate, t.deliveryDate)) > tolerance.deliveryDateDays) {
                    add(FieldDifference("$d.deliveryDate", o.deliveryDate.toString(), t.deliveryDate.toString()))
                }
                number("$d.quantity", o.quantity, t.quantity, tolerance.quantity)
                if (o.paymentDate != null && t.paymentDate != null) exact("$d.paymentDate", o.paymentDate, t.paymentDate)
            }
        }
        val ourAttributes = normalise(ours.attributes)
        val theirAttributes = normalise(theirs.attributes)
        (ourAttributes.keys + theirAttributes.keys).sorted().forEach { key ->
            if (ourAttributes[key] != theirAttributes[key]) add(FieldDifference("$p.attributes.$key", ourAttributes[key], theirAttributes[key]))
        }
    }

    private fun MutableList<FieldDifference>.exact(field: String, ours: Any?, theirs: Any?, severity: DifferenceSeverity = DifferenceSeverity.MISMATCH) {
        if (ours != theirs) add(FieldDifference(field, ours?.toString(), theirs?.toString(), severity))
    }

    private fun MutableList<FieldDifference>.text(field: String, ours: String, theirs: String, severity: DifferenceSeverity = DifferenceSeverity.MISMATCH) {
        if (key(ours) != key(theirs)) add(FieldDifference(field, ours, theirs, severity))
    }

    private fun MutableList<FieldDifference>.number(field: String, ours: BigDecimal, theirs: BigDecimal, tolerance: BigDecimal) {
        if ((ours - theirs).abs() > tolerance) add(FieldDifference(field, ours.toPlainString(), theirs.toPlainString()))
    }

    /** Parties match on LEI; a counterparty that leaves it out is noted, not refused. */
    private fun MutableList<FieldDifference>.party(field: String, ours: PartyTerms, theirs: PartyTerms) {
        when {
            ours.lei != null && theirs.lei != null -> text("$field.lei", ours.lei, theirs.lei)
            theirs.lei == null -> add(FieldDifference("$field.lei", ours.lei, null, DifferenceSeverity.NOTE))
            else -> add(FieldDifference("$field.lei", null, theirs.lei, DifferenceSeverity.NOTE))
        }
    }

    private fun MutableList<FieldDifference>.account(field: String, ours: String?, theirs: String?) {
        when {
            ours != null && theirs != null -> text(field, ours, theirs)
            ours != theirs -> add(FieldDifference(field, ours, theirs, DifferenceSeverity.NOTE))
        }
    }

    private fun normalise(attributes: Map<String, String>): Map<String, String> =
        attributes.entries.associate { (k, v) -> key(k) to v.trim().uppercase() }

    private fun key(text: String): String = text.trim().uppercase().replace(Regex("[\\s-]+"), "_")
}

/**
 * The product attributes a guarantee-of-origin criterion states, in confirmation vocabulary. Conjunctions of simple
 * terms become one attribute each; anything else is stated as the criterion itself.
 */
object CriterionTerms {
    fun attributes(criterion: Criterion?): Map<String, String> {
        if (criterion == null || criterion == Criterion.MatchAll) return emptyMap()
        val terms = if (criterion is Criterion.All) criterion.of else listOf(criterion)
        val simple = terms.mapNotNull(::term)
        return if (simple.size == terms.size) simple.toMap() else mapOf("CRITERION" to Json.mapper.writeValueAsString(criterion))
    }

    private fun term(c: Criterion): Pair<String, String>? = when (c) {
        is Criterion.Equals -> c.attribute.name to c.value
        is Criterion.InSet -> c.attribute.name to c.values.sorted().joinToString("|")
        is Criterion.DescendantOf -> c.attribute.name to c.code
        is Criterion.CountryInRegion -> "REGION" to c.region
        is Criterion.LabelsAny -> "LABELS" to c.labels.sorted().joinToString("|")
        is Criterion.LabelsAll -> "LABELS" to c.labels.sorted().joinToString("+")
        is Criterion.DeviceAgeAtDelivery -> "DEVICE_AGE_YEARS" to "${c.minYears}-${c.maxYears}"
        is Criterion.Vintage -> "VINTAGE" to when (val r = c.rule) {
            is VintageRule.ProductionYear -> r.year.toString()
            is VintageRule.Explicit -> "${r.from}/${r.toExclusive}"
            else -> Json.mapper.writeValueAsString(r)
        }
        else -> null
    }
}
