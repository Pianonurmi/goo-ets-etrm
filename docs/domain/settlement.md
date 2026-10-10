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
