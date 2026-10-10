# Settlement

From delivery to cash: settlement items, invoices, payments and netting. The `settlement` module owns them. The
ledger books them from settlement events (see ADR 0014).

## Settlement items

`DeliveryRecorded` (from trade) creates one item per delivery.

| Field | Meaning |
|---|---|
| direction | `RECEIVABLE` when we sold, `PAYABLE` when we bought |
| price | the leg's fixed price, or the price fixed for an index or formula schedule (`POST /api/v1/settlement/price-fixings`) |
| net amount | quantity × price, rounded commercially to cents |
| VAT | the rule's treatment and rate; VAT is computed per line |

An item that cannot be priced or taxed is `NEEDS_ATTENTION` with the reason. Fix the cause (fix the price, approve
a VAT rule, add a VAT registration), then `POST /api/v1/settlement/items/{id}/reevaluate`. Fixing a price
re-evaluates that schedule's items.

## Invoices

| Kind | Who issues | Series |
|---|---|---|
| `SALES` | us, from receivable items (invoice run) | `INV` |
| `SELF_BILLED` | us, for purchases from counterparties with a self-billing agreement | `SB` |
| `PURCHASE` | the supplier; we register it and match it to payable items | `PI` (our registration number) |
| `CREDIT_NOTE`, `PURCHASE_CREDIT_NOTE` | reversals | `CN`, `PC` |

1. **Invoice run:** `POST /api/v1/settlement/invoice-runs` (entity, up-to date, optional counterparty and invoice
   date). It drafts one invoice per counterparty, currency, direction and VAT treatment.
2. **Purchase invoices:** `POST /api/v1/settlement/purchase-invoices`. The supplier's net and VAT must match our
   items within 0.05; a difference is booked as an adjustment line.
3. **Submit:** `POST /invoices/{id}/submit` opens `INVOICE_APPROVAL`. A different person approves (issue) or
   rejects (back to draft). `POST /invoices/{id}/discard` releases a draft's items.
4. **Issue:**
   - gap-free number;
   - supplier and customer snapshots (name, billing address, VAT number for the country, LEI);
   - bank account (ours on sales; the supplier's on purchases);
   - due date = invoice date + payment terms, rolled to the next TARGET2 business day;
   - legal notes;
   - PDF.
5. **Export:** `GET /api/v1/settlement/invoices/export?legalEntityId=&from=&to=&format=csv|json` (issued invoices).

## Reference data used

- Counterparty: payment terms, settlement currency, `nettingAgreed`, `selfBilling`, invoice e-mail.
- Party details under `/api/v1/reference/parties/{LEGAL_ENTITY|COUNTERPARTY}/{id}`:
  - addresses (registered, billing);
  - VAT registrations per country;
  - bank accounts (IBAN with checksum, BIC), changed only with four eyes (`BANK_ACCOUNT` change requests).

## Payments

- **Recording:** `POST /api/v1/settlement/payments` (direction `INCOMING` or `OUTGOING`, amount, currency, value
  date, bank reference, optional counterparty and remittance), or a bank statement CSV
  (`POST /payments/csv?legalEntityId=`, columns `bank_reference,value_date,amount,currency[,counterparty,remittance]`,
  negative amounts are outgoing).
  - The bank reference is unique per entity, so importing a statement again changes nothing.
  - A payment is applied to the listed invoices, else to invoices whose number (or the supplier's reference) appears
    in the remittance information.
  - What is left stays unapplied until it is allocated with `POST /payments/{id}/allocations`.
- **Allocations** (payment, credit note, netting) are append-only. They set the invoice's paid amount and its
  status (`UNPAID`, `PARTIALLY_PAID`, `PAID`).
- **Settled trades:** when every delivery of a trade is invoiced and every such invoice is paid (and not credited),
  a delivered trade becomes `SETTLED`.

## Credit notes

`POST /invoices/{id}/credit-note` drafts a full reversal:
- `CREDIT_NOTE` for sales;
- a negative `SELF_BILLED` for self-billed invoices;
- `PURCHASE_CREDIT_NOTE` for purchases.

It goes through the same four-eyes approval. On issue it settles the original (marked `credited`) and reopens the
original's items, so the next invoice run invoices them again. A credit note against an invoice that was already
paid keeps the unused amount open, to be refunded or netted.

## Netting

Netting is only allowed with counterparties that have `nettingAgreed`.

1. **Prepare:** `POST /netting` (entity, counterparty, currency, value date). It takes every issued, unpaid invoice
   due by the value date and computes the receivable and payable totals and the net. The statement PDF is rendered.
   Both directions are required.
2. **Agree:** `POST /netting/{id}/agree` records the counterparty's agreement and reference. It must be a different
   person from the preparer.
3. **Settle:** `POST /netting/{id}/settle` with the payment of the net amount (unless it is zero). The smaller side
   is offset in full against the larger; the payment settles the rest.

## Reports

| Endpoint | Content |
|---|---|
| `GET /reports/ageing?legalEntityId=&asOf=` | outstanding by counterparty, direction and currency: not due, 1–30, 31–60, 61–90, over 90 days |
| `GET /reports/cash-forecast?legalEntityId=&from=&to=` | open invoices by due date, plus delivered but uninvoiced items expected after payment terms |
| `GET /reports/vat?legalEntityId=&period=YYYY-MM` | issued invoices by tax point: net and VAT by direction, treatment and rate |
| `GET /reports/ec-sales-list?legalEntityId=&period=YYYY-MM` | intra-EU B2B sales (cross-border reverse charge) by customer VAT number |
