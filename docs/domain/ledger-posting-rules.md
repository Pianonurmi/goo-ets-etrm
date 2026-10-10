# Posting rules

Facts (`PostingFacts`): `amounts` (`cost`, `proceeds`, `notional`, `quantity`, settlement: `net`, `vat`, `gross`,
`selfAssessedVat`, `amount`, `deliveredValue`, `fxGain`, `fxLoss`), `attributes` (`acquisitionSource`,
`side`, `movementType`, `allowanceClassification`, `freeAllocationPolicy`, derived `hasCost`, `hasProceeds`), `dimensions`
(trade, lot, device, counterparty, registry account, bucket).

| Rule | Event | Condition | Debit | Credit |
|---|---|---|---|---|
| GOO_ISSUANCE | LotReceived | ISSUANCE, cost | 1400 Inventory GoO | 4400 Own generation |
| GOO_PURCHASE_RECEIPT | LotReceived | TRANSFER_IN, cost | 1400 | 2200 Accrued payables |
| GOO_SALE_DELIVERY_REVENUE | SaleDelivered | proceeds | 1500 Accrued receivables | 3000 Revenue |
| GOO_SALE_DELIVERY_COGS | SaleDelivered | cost | 4000 COGS | 1400 |
| GOO_DISCLOSURE_CANCELLATION | LotCancelled | cost | 4100 Disclosure cost | 1400 |
| GOO_EXPIRY_WRITE_OFF | LotExpired | cost | 4300 Write-off | 1400 |
| TRADE_SALE_COMMITMENT / TRADE_PURCHASE_COMMITMENT | TradeConfirmed | side | 9000 / 9001 memo | 9009 memo contra |
| EUA_PURCHASE_INTANGIBLE / _INVENTORY | HoldingChanged | PURCHASE, classification | 1610 / 1410 | 2200 |
| EUA_FREE_ALLOCATION_FAIR_VALUE | HoldingChanged | FREE_ALLOCATION, FAIR_VALUE | 1610 | 2400 Deferred income |
| EUA_SURRENDER | HoldingChanged | SURRENDER, cost | 2300 Emission provision | 1610 |
| EUA_SALE_COST / EUA_SALE_PROCEEDS | HoldingChanged | SALE | 4010 / 1500 | 1610 / 3010 |
| TRADE_SALE_ / TRADE_PURCHASE_COMMITMENT_RELEASE | DeliveryRecorded | side, fixed price | 9009 memo contra | 9000 / 9001 memo |
| INVOICE_RECEIVABLE | InvoiceIssued | RECEIVABLE | 1510 Invoiced receivables (gross) | 1500 (net), 2500 Output VAT |
| INVOICE_PAYABLE | InvoiceIssued | PAYABLE | 2200 (net), 1700 Input VAT | 2210 Invoiced payables (gross) |
| INVOICE_RECEIVABLE_CREDIT / INVOICE_PAYABLE_CREDIT | InvoiceIssued | credit note | the reverse of the two above | |
| VAT_REVERSE_CHARGE (and _CREDIT) | InvoiceIssued | PAYABLE, self-assessed VAT | 1700 Input VAT | 2500 Output VAT |
| PAYMENT_RECEIVED / PAYMENT_MADE | PaymentRecorded | INCOMING / OUTGOING | 1900 Bank / 1520 Payments made, unapplied | 2600 Receipts, unapplied / 1900 |
| RECEIVABLE_PAID / PAYABLE_PAID | InvoiceSettled | PAYMENT | 2600 / 2210 | 1510 / 1520 |
| RECEIVABLE_NETTED / PAYABLE_NETTED | InvoiceSettled | NETTING | 1950 Netting clearing / 2210 | 1510 / 1950 |
| FX_GAIN_* / FX_LOSS_* | RealisedFx | direction | 1510 or 2210 / 4900 FX losses | 3900 FX gains / 1510 or 2210 |

Costing: certificates use specific identification (lot cost basis). Allowances use cost layers per account and
product consumed FIFO or weighted average per the entity's `allowanceCostingMethod`; nominal free allocation creates a
zero-cost layer, fair-value free allocation a layer at the fair value supplied with the movement.

Periods: `OPEN` accepts all postings; `SOFT_CLOSED` accepts manual adjustments and reversals; `CLOSED` accepts nothing
and cannot be reopened. Reversals of entries in closed periods post on the current date.

Settlement (ADR 0014) books no revenue: invoices move accrued receivables and payables (from deliveries and receipts)
to invoiced ones and add VAT; a lot's cost basis that differs from the supplier's invoice leaves a variance in 2200.
Cash passes through the unapplied accounts until it is allocated to invoices; netting passes through 1950. A credit
note is booked when issued, so its allocation to the original invoice posts nothing. A receipt or payment of a foreign
currency invoice at another rate than the invoice date's realises an FX result in base currency.

A line whose amount is zero is left out (no VAT line on a reverse-charged invoice); an entry needs at least one line.
Automatic postings dated in a closed or soft-closed period go to the first day of the next open period, keeping the
original date as dimension `originalDate` and in the description. Default accounts introduced later are added to
existing charts the next time the entity posts.
