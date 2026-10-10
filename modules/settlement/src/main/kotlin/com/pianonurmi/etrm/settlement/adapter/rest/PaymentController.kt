package com.pianonurmi.etrm.settlement.adapter.rest

import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.settlement.api.AgeingRow
import com.pianonurmi.etrm.settlement.api.AllocatePayment
import com.pianonurmi.etrm.settlement.api.AllocationView
import com.pianonurmi.etrm.settlement.api.CashForecastRow
import com.pianonurmi.etrm.settlement.api.EcSalesListRow
import com.pianonurmi.etrm.settlement.api.InvoiceCommand
import com.pianonurmi.etrm.settlement.api.InvoiceView
import com.pianonurmi.etrm.settlement.api.NettingCommand
import com.pianonurmi.etrm.settlement.api.NettingQuery
import com.pianonurmi.etrm.settlement.api.NettingStatementView
import com.pianonurmi.etrm.settlement.api.PaymentCommand
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
) {
    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    fun record(
        @RequestBody body: RecordPayment,
    ): PaymentView = payments.record(body)

    /** Bank statement lines: `bank_reference,value_date,amount,currency[,counterparty,remittance]`. */
    @PostMapping("/payments/csv")
    @ResponseStatus(HttpStatus.CREATED)
    fun importCsv(
        @RequestParam legalEntityId: UUID,
        @RequestBody content: String,
    ): List<PaymentView> = payments.importCsv(legalEntityId, content)

    @GetMapping("/payments")
    fun list(
        @RequestParam legalEntityId: UUID,
        @RequestParam(required = false) counterpartyId: UUID?,
        @RequestParam(defaultValue = "false") unappliedOnly: Boolean,
    ): List<PaymentView> = paymentQuery.payments(legalEntityId, counterpartyId, unappliedOnly)

    @PostMapping("/payments/{id}/allocations")
    fun allocate(
        @PathVariable id: UUID,
        @RequestBody body: AllocatePayment,
    ): PaymentView = payments.allocate(id, body)

    @GetMapping("/invoices/{id}/allocations")
    fun allocations(
        @PathVariable id: UUID,
    ): List<AllocationView> = paymentQuery.allocations(id)

    @PostMapping("/invoices/{id}/credit-note")
    @ResponseStatus(HttpStatus.CREATED)
    fun creditNote(
        @PathVariable id: UUID,
        @RequestBody body: DiscardRequest,
    ): InvoiceView = invoices.creditNote(id, body.reason)

    @PostMapping("/netting")
    @ResponseStatus(HttpStatus.CREATED)
    fun prepareNetting(
        @RequestBody body: PrepareNetting,
    ): NettingStatementView = netting.prepare(body)

    @GetMapping("/netting")
    fun nettingStatements(
        @RequestParam legalEntityId: UUID,
        @RequestParam(required = false) counterpartyId: UUID?,
    ): List<NettingStatementView> = nettingQuery.statements(legalEntityId, counterpartyId)

    @GetMapping("/netting/{id}")
    fun nettingStatement(
        @PathVariable id: UUID,
    ): NettingStatementView = nettingQuery.statement(id) ?: throw NotFoundException("NettingStatement", id)

    @PostMapping("/netting/{id}/agree")
    fun agree(
        @PathVariable id: UUID,
        @RequestBody(required = false) body: AgreeNetting?,
    ): NettingStatementView = netting.agree(id, body?.counterpartyRef)

    @PostMapping("/netting/{id}/settle")
    fun settle(
        @PathVariable id: UUID,
        @RequestBody(required = false) body: SettleNetting?,
    ): NettingStatementView = netting.settle(id, body?.paymentId)

    @PostMapping("/netting/{id}/cancel")
    fun cancelNetting(
        @PathVariable id: UUID,
        @RequestBody body: DiscardRequest,
    ): NettingStatementView = netting.cancel(id, body.reason)

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
}

data class AgreeNetting(val counterpartyRef: String? = null)

data class SettleNetting(val paymentId: UUID? = null)
