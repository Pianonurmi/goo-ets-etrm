package com.pianonurmi.etrm.settlement.adapter.rest

import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.security.AllowedRoles
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.settlement.api.AgeingRow
import com.pianonurmi.etrm.settlement.api.AllocatePayment
import com.pianonurmi.etrm.settlement.api.AllocationView
import com.pianonurmi.etrm.settlement.api.CashForecastRow
import com.pianonurmi.etrm.settlement.api.EcSalesListRow
import com.pianonurmi.etrm.settlement.api.InvoiceCommand
import com.pianonurmi.etrm.settlement.api.InvoiceQuery
import com.pianonurmi.etrm.settlement.api.InvoiceView
import com.pianonurmi.etrm.settlement.api.NettingCommand
import com.pianonurmi.etrm.settlement.api.NettingQuery
import com.pianonurmi.etrm.settlement.api.NettingStatementView
import com.pianonurmi.etrm.settlement.api.PaymentCommand
import com.pianonurmi.etrm.settlement.api.PaymentPage
import com.pianonurmi.etrm.settlement.api.PaymentQuery
import com.pianonurmi.etrm.settlement.api.PaymentView
import com.pianonurmi.etrm.settlement.api.PrepareNetting
import com.pianonurmi.etrm.settlement.api.RecordPayment
import com.pianonurmi.etrm.settlement.api.SettlementReports
import com.pianonurmi.etrm.settlement.api.VatReportRow
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

@RestController
@RequestMapping("/api/v1/settlement")
@Tag(name = "Payments and netting", description = "Payments, credit notes, netting statements and settlement reports")
class PaymentController(
    private val payments: PaymentCommand,
    private val paymentQuery: PaymentQuery,
    private val invoices: InvoiceCommand,
    private val netting: NettingCommand,
    private val nettingQuery: NettingQuery,
    private val reports: SettlementReports,
    private val invoiceQuery: InvoiceQuery,
    private val access: SettlementAccess,
) {
    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @AllowedRoles(Role.BACK_OFFICE, Role.ACCOUNTANT)
    fun record(
        @RequestBody body: RecordPayment,
    ): PaymentView {
        access.inScope(body.legalEntityId)
        return payments.record(body)
    }

    /** Bank statement lines: `bank_reference,value_date,amount,currency[,counterparty,remittance]`. */
    @PostMapping("/payments/csv", consumes = ["text/csv", "text/plain"])
    @ResponseStatus(HttpStatus.CREATED)
    @AllowedRoles(Role.BACK_OFFICE, Role.ACCOUNTANT)
    fun importCsv(
        @RequestParam legalEntityId: UUID,
        @RequestBody content: String,
    ): List<PaymentView> {
        access.inScope(legalEntityId)
        return payments.importCsv(legalEntityId, content)
    }

    @GetMapping("/payments")
    fun list(
        @RequestParam legalEntityId: UUID,
        @RequestParam(required = false) counterpartyId: UUID?,
        @RequestParam(defaultValue = "false") unappliedOnly: Boolean,
    ): List<PaymentView> = access.reader(legalEntityId).let { paymentQuery.payments(legalEntityId, counterpartyId, unappliedOnly) }

    /** Payments newest first, a page at a time; `cursor` is the previous page's `nextCursor`. */
    @GetMapping("/payments/search")
    fun search(
        @RequestParam legalEntityId: UUID,
        @RequestParam(required = false) counterpartyId: UUID?,
        @RequestParam(defaultValue = "false") unappliedOnly: Boolean,
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): PaymentPage = access.reader(legalEntityId).let { paymentQuery.paymentPage(legalEntityId, counterpartyId, unappliedOnly, limit, cursor) }

    @GetMapping("/payments/{id}")
    fun payment(
        @PathVariable id: UUID,
    ): PaymentView = requirePayment(id).also { access.reader(it.legalEntityId) }

    @PostMapping("/payments/{id}/allocations")
    @AllowedRoles(Role.BACK_OFFICE, Role.ACCOUNTANT)
    fun allocate(
        @PathVariable id: UUID,
        @RequestBody body: AllocatePayment,
    ): PaymentView {
        access.inScope(requirePayment(id).legalEntityId)
        return payments.allocate(id, body)
    }

    @GetMapping("/invoices/{id}/allocations")
    fun allocations(
        @PathVariable id: UUID,
    ): List<AllocationView> {
        access.reader(requireInvoice(id).legalEntityId)
        return paymentQuery.allocations(id)
    }

    @PostMapping("/invoices/{id}/credit-note")
    @ResponseStatus(HttpStatus.CREATED)
    @AllowedRoles(Role.BACK_OFFICE, Role.ACCOUNTANT)
    fun creditNote(
        @PathVariable id: UUID,
        @RequestBody body: DiscardRequest,
    ): InvoiceView {
        access.inScope(requireInvoice(id).legalEntityId)
        return invoices.creditNote(id, body.reason)
    }

    @PostMapping("/netting")
    @ResponseStatus(HttpStatus.CREATED)
    @AllowedRoles(Role.BACK_OFFICE, Role.ACCOUNTANT)
    fun prepareNetting(
        @RequestBody body: PrepareNetting,
    ): NettingStatementView {
        access.inScope(body.legalEntityId)
        return netting.prepare(body)
    }

    @GetMapping("/netting")
    fun nettingStatements(
        @RequestParam legalEntityId: UUID,
        @RequestParam(required = false) counterpartyId: UUID?,
    ): List<NettingStatementView> = access.reader(legalEntityId).let { nettingQuery.statements(legalEntityId, counterpartyId) }

    @GetMapping("/netting/{id}")
    fun nettingStatement(
        @PathVariable id: UUID,
    ): NettingStatementView = requireStatement(id).also { access.reader(it.legalEntityId) }

    @PostMapping("/netting/{id}/agree")
    @AllowedRoles(Role.BACK_OFFICE, Role.ACCOUNTANT)
    fun agree(
        @PathVariable id: UUID,
        @RequestBody(required = false) body: AgreeNetting?,
    ): NettingStatementView {
        access.inScope(requireStatement(id).legalEntityId)
        return netting.agree(id, body?.counterpartyRef)
    }

    @PostMapping("/netting/{id}/settle")
    @AllowedRoles(Role.BACK_OFFICE, Role.ACCOUNTANT)
    fun settle(
        @PathVariable id: UUID,
        @RequestBody(required = false) body: SettleNetting?,
    ): NettingStatementView {
        access.inScope(requireStatement(id).legalEntityId)
        return netting.settle(id, body?.paymentId)
    }

    @PostMapping("/netting/{id}/cancel")
    @AllowedRoles(Role.BACK_OFFICE, Role.ACCOUNTANT)
    fun cancelNetting(
        @PathVariable id: UUID,
        @RequestBody body: DiscardRequest,
    ): NettingStatementView {
        access.inScope(requireStatement(id).legalEntityId)
        return netting.cancel(id, body.reason)
    }

    @GetMapping("/reports/ageing")
    fun ageing(
        @RequestParam legalEntityId: UUID,
        @RequestParam asOf: LocalDate,
    ): List<AgeingRow> = reports.ageing(legalEntityId, asOf)

    @GetMapping("/reports/cash-forecast")
    fun cashForecast(
        @RequestParam legalEntityId: UUID,
        @RequestParam from: LocalDate,
        @RequestParam to: LocalDate,
    ): List<CashForecastRow> = reports.cashForecast(legalEntityId, from, to)

    @GetMapping("/reports/vat")
    fun vat(
        @RequestParam legalEntityId: UUID,
        @RequestParam period: YearMonth,
    ): List<VatReportRow> = reports.vatReport(legalEntityId, period.atDay(1), period.plusMonths(1).atDay(1))

    @GetMapping("/reports/ec-sales-list")
    fun ecSalesList(
        @RequestParam legalEntityId: UUID,
        @RequestParam period: YearMonth,
    ): List<EcSalesListRow> = reports.ecSalesList(legalEntityId, period.atDay(1), period.plusMonths(1).atDay(1))

    private fun requirePayment(id: UUID): PaymentView = paymentQuery.payment(id) ?: throw NotFoundException("Payment", id)

    private fun requireInvoice(id: UUID): InvoiceView = invoiceQuery.invoice(id) ?: throw NotFoundException("Invoice", id)

    private fun requireStatement(id: UUID): NettingStatementView = nettingQuery.statement(id) ?: throw NotFoundException("NettingStatement", id)
}

data class AgreeNetting(val counterpartyRef: String? = null)

data class SettleNetting(val paymentId: UUID? = null)
