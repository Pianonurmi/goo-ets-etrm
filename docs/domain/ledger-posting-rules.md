# Posting rules

Facts (`PostingFacts`): `amounts` (`cost`, `proceeds`, `notional`, `quantity`, settlement: `net`, `vat`, `gross`,
`selfAssessedVat`, `amount`, `deliveredValue`, `openCommitment`, `fxGain`, `fxLoss`), `attributes` (`acquisitionSource`,
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
| EUA_FREE_ALLOCATION_FAIR_VALUE / _INVENTORY | HoldingChanged | FREE_ALLOCATION, FAIR_VALUE, classification | 1610 / 1410 | 2400 Deferred income |
| EUA_SURRENDER / _INVENTORY | HoldingChanged | SURRENDER, cost, classification | 2300 Emission provision | 1610 / 1410 |
| EUA_SALE_COST / _INVENTORY, EUA_SALE_PROCEEDS | HoldingChanged | SALE, classification | 4010 / 1500 | 1610 or 1410 / 3010 |
| TRADE_SALE_ / TRADE_PURCHASE_COMMITMENT_RELEASE | DeliveryRecorded | side, memo open | 9009 memo contra | 9000 / 9001 memo |
| TRADE_SALE_ / TRADE_PURCHASE_COMMITMENT_REVERSAL | CommitmentReversed | side | 9009 memo contra | 9000 / 9001 memo |
| INVOICE_RECEIVABLE | InvoiceIssued | RECEIVABLE | 1510 Invoiced receivables (gross) | 1500 (net), 2500 Output VAT |
| INVOICE_PAYABLE | InvoiceIssued | PAYABLE | 2200 (net), 1700 Input VAT | 2210 Invoiced payables (gross) |
| INVOICE_RECEIVABLE_CREDIT / INVOICE_PAYABLE_CREDIT | InvoiceIssued | credit note | the reverse of the two above | |
| VAT_REVERSE_CHARGE (and _CREDIT) | InvoiceIssued | PAYABLE, self-assessed VAT | 1700 Input VAT | 2500 Output VAT |
| PAYMENT_RECEIVED / PAYMENT_MADE | PaymentRecorded | INCOMING / OUTGOING | 1900 Bank / 1520 Payments made, unapplied | 2600 Receipts, unapplied / 1900 |
| RECEIVABLE_PAID / PAYABLE_PAID | InvoiceSettled | PAYMENT | 2600 / 2210 | 1510 / 1520 |
| RECEIVABLE_NETTED / PAYABLE_NETTED | InvoiceSettled | NETTING | 1950 Netting clearing / 2210 | 1510 / 1950 |
| FX_GAIN_* / FX_LOSS_* | RealisedFx | direction | 1510 or 2210 / 4900 FX losses | 3900 FX gains / 1510 or 2210 |
| MTM_PL / MTM_OCI | PeriodEndMtm | treatment | 1800 derivative assets / 3500 or 2950 (losses: 4500 or 2950) | 3500 or 2950 / 2800 derivative liabilities |
| NRV_GOO / NRV_ALLOWANCE | PeriodEndNrv | asset | 4310 NRV write-down | 1490 / 1491 valuation allowance |
| EMISSION_PROVISION | PeriodEndProvision | | 4200 Emission costs | 2300 Emission provision |
| FREE_ALLOCATION_GRANT_RELEASE | PeriodEndGrantRelease | | 2400 Deferred income | 4210 Grant released |
| FX_REVALUATION_RECEIVABLE / _PAYABLE | PeriodEndFxRevaluation | account | 1510 or 2210 / 4910 unrealised FX losses | 3910 unrealised FX gains / 1510 or 2210 |

Costing: certificates use specific identification (lot cost basis). Allowances use cost layers per account and
product consumed FIFO or weighted average per the entity's `allowanceCostingMethod`; nominal free allocation creates a
zero-cost layer, fair-value free allocation a layer at the fair value supplied with the movement.

Period-end rules carry increase and decrease amounts (gain and loss for FX), so the same rule moves a balance up or
down; see [ADR 0021](../adr/0021-period-end-accounting.md). They post through the adjustment path on the last day of
the period, and MtM and FX revaluation are reversed on the first day of the next one.

Commitment memos are kept per delivery schedule (`commitment_memo`). A confirmation posts the value of the quantity
still to deliver at the fixed price; each delivery releases its share of what was posted, and the delivery that
completes the schedule releases the remainder. An amendment or a cancellation reverses whatever is still open, and
the re-confirmed trade posts a new memo. Index legs are memoed at the official curve price on the trade date plus the
spread; formula legs, and schedules without a curve price, get no memo. Releases and reversals are
recorded once per event, so replays change nothing.

Default rules ship as a versioned set (`default-posting-rules.json`). When the shipped version is newer than the one
last applied (`ledger_seed`), default rules are brought in line with it and defaults no longer shipped are
deactivated. Entity-specific overrides are never changed.

Periods move `OPEN` → `SOFT_CLOSED` → `CLOSED`; a soft close can be reopened, a hard close is final (a database
trigger refuses any change to a closed period). `OPEN` accepts all postings, `SOFT_CLOSED` accepts manual adjustments
and reversals, `CLOSED` accepts nothing. Reversals of entries in closed periods post on the current date.

FX: postings convert to the entity's base currency with market data's `FxRates` (direct, inverse, or crossed through
EUR; see [market data](market-data.md#fx)). Rates are maintained in market data, no longer in the ledger.

Settlement (ADR 0014) books no revenue: invoices move accrued receivables and payables (from deliveries and receipts)
to invoiced ones and add VAT; a lot's cost basis that differs from the supplier's invoice leaves a variance in 2200.
Cash passes through the unapplied accounts until it is allocated to invoices; netting passes through 1950. A credit
note is booked when issued, so its allocation to the original invoice posts nothing. A receipt or payment of a foreign
currency invoice at another rate than the invoice date's realises an FX result in base currency.

A line whose amount is zero is left out (no VAT line on a reverse-charged invoice); an entry needs at least one line.
Automatic postings dated in a closed or soft-closed period go to the first day of the next open period, keeping the
original date as dimension `originalDate` and in the description. Default accounts introduced later are added to
existing charts the next time the entity posts.
