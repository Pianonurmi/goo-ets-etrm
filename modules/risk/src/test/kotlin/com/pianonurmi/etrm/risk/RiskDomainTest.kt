package com.pianonurmi.etrm.risk

import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.risk.api.LimitScope
import com.pianonurmi.etrm.risk.api.LimitStatus
import com.pianonurmi.etrm.risk.api.LimitType
import com.pianonurmi.etrm.risk.api.PfeAddOnView
import com.pianonurmi.etrm.risk.api.RiskLimitView
import com.pianonurmi.etrm.risk.api.UtilisationStatus
import com.pianonurmi.etrm.risk.domain.CounterpartyGroups
import com.pianonurmi.etrm.risk.domain.CreditExposure
import com.pianonurmi.etrm.risk.domain.PotentialFutureExposure
import com.pianonurmi.etrm.risk.domain.Tenor
import com.pianonurmi.etrm.risk.domain.Utilisation
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class RiskDomainTest {
    private fun d(value: String) = BigDecimal(value)

    @Test
    fun `MtM is netted over the counterparty only under a netting agreement`() {
        val mtm = listOf(d("300"), d("-100"), d("50"))
        CreditExposure.positiveMtm(mtm, netting = false) shouldBe d("350")
        CreditExposure.positiveMtm(mtm, netting = true) shouldBe d("250")
        CreditExposure.positiveMtm(listOf(d("-300")), netting = true) shouldBe BigDecimal.ZERO
    }

    @Test
    fun `exposure adds receivables and PFE, sets off payables only under netting, and deducts collateral`() {
        CreditExposure.exposure(d("300"), d("200"), d("50"), false, d("4200"), d("1000")) shouldBe d("3700.00")
        CreditExposure.exposure(d("200"), d("200"), d("50"), true, d("4200"), d("1000")) shouldBe d("3550.00")
        CreditExposure.exposure(d("0"), d("0"), d("0"), false, d("100"), d("1000")) shouldBe d("0.00")
    }

    @Test
    fun `PFE add-ons come from the first tenor band reaching the delivery`() {
        val bands = listOf(
            PfeAddOnView(ProductFamily.EMISSION_ALLOWANCE, 3, d("6"), false),
            PfeAddOnView(ProductFamily.EMISSION_ALLOWANCE, 12, d("10"), false),
            PfeAddOnView(ProductFamily.EMISSION_ALLOWANCE, 9999, d("15"), false),
        )
        PotentialFutureExposure.pct(bands, ProductFamily.EMISSION_ALLOWANCE, 2) shouldBe d("6")
        PotentialFutureExposure.pct(bands, ProductFamily.EMISSION_ALLOWANCE, 3) shouldBe d("6")
        PotentialFutureExposure.pct(bands, ProductFamily.EMISSION_ALLOWANCE, 18) shouldBe d("15")
        PotentialFutureExposure.pct(bands, ProductFamily.GUARANTEE_OF_ORIGIN, 1) shouldBe BigDecimal.ZERO
        PotentialFutureExposure.addOn(d("-70000"), d("6")) shouldBe d("4200.00")
    }

    @Test
    fun `tenor counts started months`() {
        val today = LocalDate.of(2026, 10, 10)
        Tenor.months(today, LocalDate.of(2026, 10, 10)) shouldBe 0
        Tenor.months(today, LocalDate.of(2026, 11, 10)) shouldBe 1
        Tenor.months(today, LocalDate.of(2026, 11, 11)) shouldBe 2
        Tenor.months(today, LocalDate.of(2026, 1, 1)) shouldBe 0
    }

    @Test
    fun `utilisation warns from the warning level and breaches above the limit`() {
        val limit = RiskLimitView(
            UUID.randomUUID(),
            null,
            LimitType.CREDIT_EXPOSURE,
            LimitScope(),
            d("1000"),
            "EUR",
            80,
            LocalDate.MIN,
            null,
            LimitStatus.ACTIVE,
            null,
            "r",
            "a",
            Instant.EPOCH,
            "b",
            Instant.EPOCH,
        )
        Utilisation.of(limit.id, d("500"), limit, null, Instant.EPOCH).status shouldBe UtilisationStatus.OK
        Utilisation.of(limit.id, d("800"), limit, null, Instant.EPOCH).let {
            it.status shouldBe UtilisationStatus.WARNING
            it.utilisation shouldBe d("80.00")
        }
        Utilisation.of(limit.id, d("1000"), limit, null, Instant.EPOCH).status shouldBe UtilisationStatus.WARNING
        Utilisation.of(limit.id, d("1000.01"), limit, null, Instant.EPOCH).status shouldBe UtilisationStatus.BREACH
        Utilisation.of(limit.id, d("1"), limit.copy(amount = BigDecimal.ZERO), null, Instant.EPOCH).status shouldBe UtilisationStatus.BREACH
    }

    @Test
    fun `a group limit covers the parent and everything below it`() {
        val parent = UUID.randomUUID()
        val child = UUID.randomUUID()
        val grandchild = UUID.randomUUID()
        val other = UUID.randomUUID()
        val parents = mapOf(parent to null, child to parent, grandchild to child, other to null)
        CounterpartyGroups.members(parent, parents) shouldBe setOf(parent, child, grandchild)
        CounterpartyGroups.members(child, parents) shouldBe setOf(child, grandchild)
        CounterpartyGroups.ancestry(grandchild, parents) shouldBe listOf(grandchild, child, parent)
    }
}
