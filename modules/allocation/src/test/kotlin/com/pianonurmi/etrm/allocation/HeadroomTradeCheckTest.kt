package com.pianonurmi.etrm.allocation

import com.pianonurmi.etrm.allocation.api.BindingSupply
import com.pianonurmi.etrm.allocation.api.DemandHeadroom
import com.pianonurmi.etrm.allocation.api.HeadroomCheck
import com.pianonurmi.etrm.allocation.api.HeadroomQuery
import com.pianonurmi.etrm.allocation.api.HeadroomResult
import com.pianonurmi.etrm.allocation.api.SupplyTier
import com.pianonurmi.etrm.allocation.application.HeadroomTradeCheck
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.product.api.criteria.Criterion
import com.pianonurmi.etrm.trade.api.DeliveryScheduleView
import com.pianonurmi.etrm.trade.api.FindingSeverity
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeLegView
import com.pianonurmi.etrm.trade.api.TradeView
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The GOO_HEADROOM pre-trade finding (ADR 0036): built from a trade's sell deliveries, never blocking. */
class HeadroomTradeCheckTest {
    private val entity = UUID.randomUUID()
    private val hydro = Criterion.energySource("HYDRO")

    private fun delivery(no: Int, quantity: String, delivered: String = "0") = DeliveryScheduleView(
        id = UUID.randomUUID(),
        scheduleNo = no,
        deliveryWindow = com.pianonurmi.etrm.platform.time.LocalDateRange.ofMonth(java.time.YearMonth.of(2026, 11)),
        deliveryDate = LocalDate.of(2026, 11, 30),
        quantity = BigDecimal(quantity),
        deliveredQuantity = BigDecimal(delivered),
        paymentDate = null,
        status = com.pianonurmi.etrm.trade.api.DeliveryStatus.entries.first(),
    )

    private fun trade(family: ProductFamily, side: Side, deliveries: List<DeliveryScheduleView>): TradeView {
        val leg = mockk<TradeLegView> {
            every { legNo } returns 1
            every { this@mockk.side } returns side
            every { criterion } returns hydro
            every { counterpartyAccountRef } returns "SIMULATOR:SIM-CP-1"
            every { this@mockk.deliveries } returns deliveries
        }
        return mockk {
            every { productFamily } returns family
            every { legalEntityId } returns entity
            every { legs } returns listOf(leg)
        }
    }

    private fun result(vararg demands: DemandHeadroom, joint: Long?) = HeadroomResult(
        holderEntityId = entity,
        tier = SupplyTier.HELD,
        stamp = "s",
        computedAt = Instant.now(),
        demands = demands.toList(),
        requestedMwh = demands.sumOf { it.requestedMwh },
        jointMwh = joint,
        fits = joint != null && joint >= demands.sumOf { it.requestedMwh },
    )

    @Test
    fun `only GoO sales are checked`() {
        val headroom = mockk<HeadroomQuery>()
        val check = HeadroomTradeCheck(headroom)
        check.check(trade(ProductFamily.EMISSION_ALLOWANCE, Side.SELL, listOf(delivery(1, "10")))).shouldBeEmpty()
        check.check(trade(ProductFamily.GUARANTEE_OF_ORIGIN, Side.BUY, listOf(delivery(1, "10")))).shouldBeEmpty()
        // Fully delivered: nothing left to sell.
        check.check(trade(ProductFamily.GUARANTEE_OF_ORIGIN, Side.SELL, listOf(delivery(1, "10", delivered = "10")))).shouldBeEmpty()
    }

    @Test
    fun `figures not prepared yet give a note, never a block`() {
        val headroom = mockk<HeadroomQuery> { every { checkIfReady(any()) } returns null }
        HeadroomTradeCheck(headroom).check(trade(ProductFamily.GUARANTEE_OF_ORIGIN, Side.SELL, listOf(delivery(1, "10")))).single().let {
            it.code shouldBe "GOO_HEADROOM"
            it.severity shouldBe FindingSeverity.INFO
        }
    }

    @Test
    fun `a delivery that does not fit warns with what limits it, and the trade's own schedules are left out`() {
        val asked = slot<HeadroomCheck>()
        val short = DemandHeadroom("leg 1, delivery 1", 11, 4, 4, false, listOf(BindingSupply("NORDIC_HYDRO", 2026, 4)))
        val headroom = mockk<HeadroomQuery> { every { checkIfReady(capture(asked)) } returns result(short, joint = 4) }
        val schedule = delivery(1, "10.5", delivered = "0")
        val findings = HeadroomTradeCheck(headroom).check(trade(ProductFamily.GUARANTEE_OF_ORIGIN, Side.SELL, listOf(schedule)))

        findings.single().let {
            it.severity shouldBe FindingSeverity.WARN
            it.message shouldContain "only 4 MWh"
            it.message shouldContain "NORDIC_HYDRO 2026"
        }
        asked.captured.excludeSourceRefs shouldBe setOf(schedule.id)
        asked.captured.demands.single().let {
            it.quantityMwh shouldBe 11
            it.targetRegistryCode shouldBe "SIMULATOR"
            it.criterion shouldBe hydro
        }
    }

    @Test
    fun `deliveries that fit one by one but not together give one warning`() {
        val each = listOf(
            DemandHeadroom("leg 1, delivery 1", 10, 15, 15, true, emptyList()),
            DemandHeadroom("leg 1, delivery 2", 10, 15, 15, true, emptyList()),
        )
        val headroom = mockk<HeadroomQuery> { every { checkIfReady(any()) } returns result(*each.toTypedArray(), joint = 15) }
        val findings = HeadroomTradeCheck(headroom).check(
            trade(ProductFamily.GUARANTEE_OF_ORIGIN, Side.SELL, listOf(delivery(1, "10"), delivery(2, "10"))),
        )
        findings.single().message shouldContain "together they need 20 MWh and only 15 MWh"
    }
}
