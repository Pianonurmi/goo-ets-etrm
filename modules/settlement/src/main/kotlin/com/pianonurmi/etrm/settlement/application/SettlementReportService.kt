package com.pianonurmi.etrm.settlement.application

import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.settlement.api.AgeingRow
import com.pianonurmi.etrm.settlement.api.CashForecastRow
import com.pianonurmi.etrm.settlement.api.EcSalesListRow
import com.pianonurmi.etrm.settlement.api.InvoiceFilter
import com.pianonurmi.etrm.settlement.api.InvoiceKind
import com.pianonurmi.etrm.settlement.api.InvoiceStatus
import com.pianonurmi.etrm.settlement.api.PaymentStatus
import com.pianonurmi.etrm.settlement.api.SettlementDirection
import com.pianonurmi.etrm.settlement.api.SettlementItemFilter
import com.pianonurmi.etrm.settlement.api.SettlementItemStatus
import com.pianonurmi.etrm.settlement.api.SettlementReports
import com.pianonurmi.etrm.settlement.api.VatReportRow
import com.pianonurmi.etrm.settlement.api.VatTreatment
import com.pianonurmi.etrm.settlement.domain.InvoiceRepository
import com.pianonurmi.etrm.settlement.domain.SettlementItemRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
@Transactional(readOnly = true)
class SettlementReportService(
    private val invoices: InvoiceRepository,
    private val items: SettlementItemRepository,
    private val referenceData: ReferenceDataQuery,
    private val actorProvider: ActorProvider,
) : SettlementReports {
    /** Receivables, payables and VAT of one entity: finance, risk and audit roles with access to the entity. */
    private fun authorise(legalEntityId: UUID) {
        val actor = actorProvider.current()
        actor.requireRole(Role.ACCOUNTANT, Role.BACK_OFFICE, Role.MIDDLE_OFFICE, Role.RISK, Role.AUDITOR)
        actor.requireEntity(legalEntityId)
    }

    override fun ageing(legalEntityId: UUID, asOf: LocalDate): List<AgeingRow> = authorise(legalEntityId).let { openInvoices(legalEntityId) }
        .groupBy { Triple(it.counterpartyId, it.direction, it.currency) }
        .map { (key, group) ->
            fun bucket(range: LongRange?) = group.filter { inv ->
                val overdue = ChronoUnit.DAYS.between(inv.dueDate!!, asOf)
                if (range == null) overdue <= 0 else overdue in range
            }.sumOf { it.outstanding }
            AgeingRow(
                counterpartyId = key.first,
                direction = key.second,
                currency = key.third,
                notDue = bucket(null),
                days1to30 = bucket(1L..30L),
                days31to60 = bucket(31L..60L),
                days61to90 = bucket(61L..90L),
                over90 = bucket(91L..Long.MAX_VALUE),
            )
        }
        .sortedWith(compareBy({ it.direction }, { it.counterpartyId.toString() }))

    /** Open invoices by due date, plus delivered but not yet invoiced items expected after the counterparty's terms. */
    override fun cashForecast(legalEntityId: UUID, from: LocalDate, toExclusive: LocalDate): List<CashForecastRow> {
        authorise(legalEntityId)
        val invoiced = openInvoices(legalEntityId).map { Triple(maxOf(it.dueDate!!, from), it.currency, it.direction) to it.outstanding }
            .map { (key, amount) -> Flow(key.first, key.second, key.third, amount, "invoiced") }
        val uninvoiced = items.find(SettlementItemFilter(legalEntityId = legalEntityId, statuses = setOf(SettlementItemStatus.OPEN), limit = 10_000))
            .map { item ->
                val terms = referenceData.counterparty(item.counterpartyId)?.paymentTermsDays?.toLong() ?: 0
                Flow(maxOf(item.deliveredOn.plusDays(terms), from), item.currency, item.direction, item.netAmount!! + item.vatAmount!!, "expected")
            }
        return (invoiced + uninvoiced).filter { it.date < toExclusive }
            .groupBy { Triple(it.date, it.currency, it.basis) }
            .map { (key, flows) ->
                CashForecastRow(
                    key.first,
                    key.second,
                    flows.filter { it.direction == SettlementDirection.RECEIVABLE }.sumOf { it.amount },
                    flows.filter { it.direction == SettlementDirection.PAYABLE }.sumOf { it.amount },
                    key.third,
                )
            }
            .sortedWith(compareBy({ it.date }, { it.currency }, { it.basis }))
    }

    override fun vatReport(legalEntityId: UUID, from: LocalDate, toExclusive: LocalDate): List<VatReportRow> = authorise(legalEntityId).let {
        issued(legalEntityId)
    }
        .filter { it.taxPointDate >= from && it.taxPointDate < toExclusive }
        .flatMap { inv -> inv.lines.map { inv to it } }
        .groupBy { (inv, line) -> listOf(inv.direction, line.vatTreatment, line.vatRate.stripTrailingZeros(), inv.currency) }
        .map { (key, lines) ->
            VatReportRow(
                direction = key[0] as SettlementDirection,
                treatment = key[1] as VatTreatment,
                rate = key[2] as BigDecimal,
                net = lines.sumOf { it.second.netAmount },
                vat = lines.sumOf { it.second.vatAmount },
                currency = key[3] as String,
            )
        }
        .sortedWith(compareBy({ it.direction }, { it.treatment }, { it.rate }))

    override fun ecSalesList(legalEntityId: UUID, from: LocalDate, toExclusive: LocalDate): List<EcSalesListRow> = authorise(legalEntityId).let {
        issued(legalEntityId)
    }
        .filter { it.direction == SettlementDirection.RECEIVABLE && it.taxPointDate >= from && it.taxPointDate < toExclusive }
        .flatMap { inv -> inv.lines.filter { it.vatTreatment == VatTreatment.REVERSE_CHARGE_CROSS_BORDER }.map { inv to it } }
        .groupBy { (inv, _) -> Triple(inv.customer?.vatNumber ?: "missing", inv.customer?.country ?: "", inv.currency) }
        .map { (key, lines) -> EcSalesListRow(key.first, key.second, lines.sumOf { it.second.netAmount }, key.third) }
        .sortedBy { it.customerVatNumber }

    private data class Flow(val date: LocalDate, val currency: String, val direction: SettlementDirection, val amount: BigDecimal, val basis: String)

    private fun issued(legalEntityId: UUID) =
        invoices.find(InvoiceFilter(legalEntityId = legalEntityId, statuses = setOf(InvoiceStatus.ISSUED), limit = 100_000))

    private fun openInvoices(legalEntityId: UUID) = issued(legalEntityId)
        .filter { it.paymentStatus != PaymentStatus.PAID && it.outstanding.signum() > 0 && it.kind != InvoiceKind.CREDIT_NOTE && it.dueDate != null }
}
