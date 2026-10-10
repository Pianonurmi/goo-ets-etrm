# ADR 0014: Settlement as its own module; VAT rules as approved, effective-dated data

- Status: accepted
- Date: 2026-10-10

## Context
Increment 2 adds invoicing, VAT, payments and netting. VAT treatment of certificates and allowances differs by
country and changes over time. The EUA domestic reverse charge under Art. 199a lapses on 31 December 2026 unless it
is extended. Germany reverse-charges guarantees of origin; Finland and Sweden standard-rate them.

Invoices are legal documents. They need gap-free numbers per issuer and year, frozen party details, and wording
required by the VAT Directive (Art. 226).

## Decision
- A new module, `settlement`, depends on the APIs of reference data, product, trade, workflow and document.
  - The ledger listens to its events; settlement never calls the ledger.
  - Settlement books no revenue. Revenue and cost of sale stay with the delivery postings. Settlement events drive
    AR/AP, VAT, payments and netting.
- **Settlement items:** each `DeliveryRecorded` creates one settlement item, keyed by the delivery id (idempotent).
  - Creating an item never fails the delivery.
  - When something is missing, the item is parked as `NEEDS_ATTENTION` with the reason. Examples: an index price
    that is not fixed, no matching VAT rule, a customer without a VAT number for cross-border reverse charge.
  - A price fixing or a re-evaluation releases the item.
- **VAT rules** are rows in `vat_rule`.
  - Each rule has: product family or `*`, supplier country or `*`, scope (DOMESTIC, INTRA_EU, IMPORT, EXPORT, ANY),
    treatment, rate, validity, and legal text.
  - The rule chosen is the approved one valid on the tax point (the delivery date) that fits most specifically.
    Two equally specific rules are an error, not a guess.
  - A new rule or version is a DRAFT until a second person approves it (`VAT_RULE_APPROVAL`; the database refuses
    self-approval).
  - Seeds are marked `requires_sign_off`. If Art. 199a is extended, that is a new rule version, not a code change.
- **Invoices** go DRAFT → PENDING_APPROVAL → ISSUED (four eyes, `INVOICE_APPROVAL`). On approval, in the same
  transaction:
  - the number is taken from a locked `number_series` row (`<series>-<year>-<seq>`);
  - supplier, customer and bank details are frozen;
  - the due date is set from the counterparty's payment terms, rolled to a TARGET2 business day;
  - legal notes are added (reverse charge, self-billing);
  - the PDF is rendered.

  A trigger keeps issued invoices and their lines immutable.
- **Kinds:**
  - sales invoices are drafted by an invoice run;
  - self-billed invoices, where a self-billing agreement exists;
  - purchase invoices are registered from the supplier's invoice and matched to our payable items within 0.05; the
    rounding difference becomes an adjustment line;
  - credit notes.

## Consequences
- A rollback returns the number, so numbers stay gap-free. Concurrent issuers for one series queue on the row.
- The tax adviser must sign off the VAT seeds before production. Determination can be checked in advance with
  `GET /api/v1/settlement/vat-rules/determination`.
