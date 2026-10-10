package com.pianonurmi.etrm.settlement.domain

import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.settlement.api.InvoiceFilter
import com.pianonurmi.etrm.settlement.api.InvoiceView
import com.pianonurmi.etrm.settlement.api.SettlementItemFilter
import com.pianonurmi.etrm.settlement.api.SettlementItemView
import com.pianonurmi.etrm.settlement.api.VatDetermination
import com.pianonurmi.etrm.settlement.api.VatRuleStatus
import com.pianonurmi.etrm.settlement.api.VatRuleView
import com.pianonurmi.etrm.settlement.api.VatScope
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

/** Invoice amounts: two decimals, commercial rounding, VAT computed per line. */
object Amounts {
    fun money(value: BigDecimal): BigDecimal = value.setScale(2, RoundingMode.HALF_UP)

    fun vat(net: BigDecimal, ratePercent: BigDecimal): BigDecimal = money(net * ratePercent / BigDecimal(100))
}

object VatRules {
    /** EU member states (ISO codes; Greece is GR here, EL in VAT numbers). */
    val EU = setOf(
        "AT",
        "BE",
        "BG",
        "CY",
        "CZ",
        "DE",
        "DK",
        "EE",
        "ES",
        "FI",
        "FR",
        "GR",
        "HR",
        "HU",
        "IE",
        "IT",
        "LT",
        "LU",
        "LV",
        "MT",
        "NL",
        "PL",
        "PT",
        "RO",
        "SE",
        "SI",
        "SK",
    )

    fun scope(supplierCountry: String, customerCountry: String): VatScope = when {
        supplierCountry == customerCountry -> VatScope.DOMESTIC
        customerCountry !in EU && supplierCountry in EU -> VatScope.EXPORT
        supplierCountry !in EU && customerCountry in EU -> VatScope.IMPORT
        supplierCountry in EU && customerCountry in EU -> VatScope.INTRA_EU
        else -> VatScope.EXPORT
    }

    /**
     * The approved rule valid on the tax point that fits the supply most specifically: a named product family beats
     * `*`, a named supplier country beats `*`, a named scope beats ANY. Two equally specific rules are an error.
     */
    fun select(
        rules: List<VatRuleView>,
        family: ProductFamily,
        supplierCountry: String,
        customerCountry: String,
        taxPointDate: LocalDate,
    ): VatDetermination {
        val scope = scope(supplierCountry, customerCountry)
        val candidates = rules.filter {
            it.status == VatRuleStatus.APPROVED &&
                taxPointDate >= it.validFrom && (it.validTo == null || taxPointDate < it.validTo) &&
                (it.productFamily == "*" || it.productFamily == family.name) &&
                (it.supplierCountry == "*" || it.supplierCountry == supplierCountry) &&
                (it.scope == VatScope.ANY || it.scope == scope)
        }
        if (candidates.isEmpty()) {
            return VatDetermination(
                null,
                "No approved VAT rule for $family, $supplierCountry to $customerCountry ($scope) on $taxPointDate",
            )
        }
        val best = candidates.groupBy(::specificity).maxBy { it.key }.value
        if (best.size > 1) return VatDetermination(null, "VAT rules ${best.joinToString { "${it.code} v${it.version}" }} are equally specific")
        return VatDetermination(best.single(), null)
    }

    private fun specificity(rule: VatRuleView): Int =
        (if (rule.productFamily != "*") 4 else 0) + (if (rule.supplierCountry != "*") 2 else 0) + (if (rule.scope != VatScope.ANY) 1 else 0)
}

interface VatRuleRepository {
    fun all(): List<VatRuleView>

    fun find(id: UUID, forUpdate: Boolean = false): VatRuleView?

    fun nextVersion(code: String): Int

    fun insert(rule: VatRuleView)

    fun update(rule: VatRuleView)
}

data class PriceFixingView(val scheduleId: UUID, val tradeId: UUID, val price: BigDecimal, val currency: String, val note: String?, val fixedBy: String)

interface SettlementItemRepository {
    /** Inserts unless an item for the delivery exists; returns false when it did. */
    fun insert(item: SettlementItemView): Boolean

    fun update(item: SettlementItemView)

    fun find(id: UUID, forUpdate: Boolean = false): SettlementItemView?

    fun find(filter: SettlementItemFilter, forUpdate: Boolean = false): List<SettlementItemView>

    fun forSchedule(scheduleId: UUID): List<SettlementItemView>

    fun priceFixing(scheduleId: UUID): PriceFixingView?

    fun upsertPriceFixing(fixing: PriceFixingView)
}

interface NumberSeriesRepository {
    /** Takes the next number of the series in the current transaction (row lock until commit). */
    fun next(legalEntityId: UUID, series: String, fiscalYear: Int): Long
}

interface InvoiceRepository {
    fun insert(invoice: InvoiceView)

    fun update(invoice: InvoiceView)

    fun find(id: UUID, forUpdate: Boolean = false): InvoiceView?

    fun find(filter: InvoiceFilter): List<InvoiceView>
}
