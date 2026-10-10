package com.pianonurmi.etrm.settlement.adapter.rest

import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.settlement.api.FixPrice
import com.pianonurmi.etrm.settlement.api.InvoiceCommand
import com.pianonurmi.etrm.settlement.api.InvoiceFilter
import com.pianonurmi.etrm.settlement.api.InvoiceKind
import com.pianonurmi.etrm.settlement.api.InvoiceQuery
import com.pianonurmi.etrm.settlement.api.InvoiceRun
import com.pianonurmi.etrm.settlement.api.InvoiceStatus
import com.pianonurmi.etrm.settlement.api.InvoiceView
import com.pianonurmi.etrm.settlement.api.ProposeVatRule
import com.pianonurmi.etrm.settlement.api.RegisterPurchaseInvoice
import com.pianonurmi.etrm.settlement.api.SettlementItemCommand
import com.pianonurmi.etrm.settlement.api.SettlementItemFilter
import com.pianonurmi.etrm.settlement.api.SettlementItemQuery
import com.pianonurmi.etrm.settlement.api.SettlementItemStatus
import com.pianonurmi.etrm.settlement.api.SettlementItemView
import com.pianonurmi.etrm.settlement.api.VatDetermination
import com.pianonurmi.etrm.settlement.api.VatRuleCommand
import com.pianonurmi.etrm.settlement.api.VatRuleQuery
import com.pianonurmi.etrm.settlement.api.VatRuleView
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1/settlement")
@Tag(name = "Settlement", description = "Settlement items, price fixing, VAT rules and invoices")
class SettlementController(
    private val itemCommand: SettlementItemCommand,
    private val itemQuery: SettlementItemQuery,
    private val vatCommand: VatRuleCommand,
    private val vatQuery: VatRuleQuery,
    private val invoiceCommand: InvoiceCommand,
    private val invoiceQuery: InvoiceQuery,
) {
    @GetMapping("/items")
    fun items(
        @RequestParam(required = false) legalEntityId: UUID?,
        @RequestParam(required = false) counterpartyId: UUID?,
        @RequestParam(required = false) tradeId: UUID?,
        @RequestParam(required = false) status: Set<SettlementItemStatus>?,
    ): List<SettlementItemView> = itemQuery.items(SettlementItemFilter(legalEntityId, counterpartyId, status, tradeId))

    @PostMapping("/items/{id}/reevaluate")
    fun reevaluate(
        @PathVariable id: UUID,
    ): SettlementItemView = itemCommand.reevaluate(id)

    /** Fixes the price of an index or formula delivery schedule. */
    @PostMapping("/price-fixings")
    fun fixPrice(
        @RequestBody body: FixPrice,
    ): List<SettlementItemView> = itemCommand.fixPrice(body)

    @GetMapping("/vat-rules")
    fun vatRules(
        @RequestParam(defaultValue = "false") includeRetired: Boolean,
    ): List<VatRuleView> = vatQuery.rules(includeRetired)

    @PostMapping("/vat-rules")
    @ResponseStatus(HttpStatus.CREATED)
    fun proposeVatRule(
        @RequestBody body: ProposeVatRule,
    ): VatRuleView = vatCommand.propose(body)

    /** Which rule applies to a supply: for checking rule changes before they take effect. */
    @GetMapping("/vat-rules/determination")
    fun determine(
        @RequestParam productFamily: ProductFamily,
        @RequestParam supplierCountry: String,
        @RequestParam customerCountry: String,
        @RequestParam taxPointDate: LocalDate,
    ): VatDetermination = vatQuery.determine(productFamily, supplierCountry, customerCountry, taxPointDate)

    @PostMapping("/invoice-runs")
    @ResponseStatus(HttpStatus.CREATED)
    fun run(
        @RequestBody body: InvoiceRun,
    ): List<InvoiceView> = invoiceCommand.run(body)

    @PostMapping("/purchase-invoices")
    @ResponseStatus(HttpStatus.CREATED)
    fun registerPurchaseInvoice(
        @RequestBody body: RegisterPurchaseInvoice,
    ): InvoiceView = invoiceCommand.registerPurchaseInvoice(body)

    @GetMapping("/invoices")
    fun invoices(
        @RequestParam(required = false) legalEntityId: UUID?,
        @RequestParam(required = false) counterpartyId: UUID?,
        @RequestParam(required = false) kind: Set<InvoiceKind>?,
        @RequestParam(required = false) status: Set<InvoiceStatus>?,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
    ): List<InvoiceView> = invoiceQuery.invoices(InvoiceFilter(legalEntityId, counterpartyId, kind, status, from, to))

    @GetMapping("/invoices/{id}")
    fun invoice(
        @PathVariable id: UUID,
    ): InvoiceView = invoiceQuery.invoice(id) ?: throw NotFoundException("Invoice", id)

    @PostMapping("/invoices/{id}/submit")
    fun submit(
        @PathVariable id: UUID,
    ): InvoiceView = invoiceCommand.submit(id)

    @PostMapping("/invoices/{id}/discard")
    fun discard(
        @PathVariable id: UUID,
        @RequestBody body: DiscardRequest,
    ): InvoiceView = invoiceCommand.discard(id, body.reason)

    /** Invoices as CSV (`format=csv`) or JSON. */
    @GetMapping("/invoices/export")
    fun export(
        @RequestParam legalEntityId: UUID,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
        @RequestParam(defaultValue = "csv") format: String,
    ): ResponseEntity<Any> {
        val filter = InvoiceFilter(legalEntityId = legalEntityId, statuses = setOf(InvoiceStatus.ISSUED), from = from, toExclusive = to, limit = 10_000)
        return if (format.equals("json", ignoreCase = true)) {
            ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(invoiceQuery.invoices(filter))
        } else {
            ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=invoices.csv")
                .contentType(MediaType("text", "csv"))
                .body(invoiceQuery.export(filter))
        }
    }
}

data class DiscardRequest(val reason: String)
