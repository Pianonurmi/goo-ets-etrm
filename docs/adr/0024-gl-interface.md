# ADR 0024: GL interface with exactly-once exports, layouts as data, acknowledgement, reconciliation and gated hard close

- Status: accepted (the SAP layout is unverified)
- Date: 2026-10-10

## Context
Until now a GL export wrote the whole period again on every run, in one fixed layout, and nothing followed it up:
- an adjustment after the first export was either missed or exported twice;
- nobody recorded whether the general ledger took the file;
- a period could be hard-closed before its figures reached the general ledger.

Posting rules decide what the books say, but one accountant could change them alone.

## Decision
- **Exactly once**: `gl_export_entry` records which export carried each journal entry. A unique index admits one
  active export per entry.
  - An export takes the period's entries not carried yet: the first is `PERIOD`, later ones `DELTA`.
  - With nothing new, the export is refused.
  - Rejecting an export (the general ledger refused the file) deactivates its entries, so the next export carries
    them again.
- **Layouts as data** (`gl_export_format.layout`): a delimiter, a header flag, and either the built-in generic line
  layout or a list of columns. Each column takes a source (entity code, posting date with a pattern, period month,
  entry and line number, GL account, debit/credit flag with its letters, amount and currency, base amount and
  currency, description), optionally cut to a length.
  - Seeds: `GENERIC_CSV` (verified, our own layout) and `SAP_FLAT` (FI document lines; `verified = false` until
    checked against the receiving system).
  - Lines carry absolute amounts and a side flag, never signed amounts.
- **Status**: GENERATED → DELIVERED → ACKNOWLEDGED (with the GL's document reference). GENERATED or DELIVERED →
  REJECTED (with a reason). Every change publishes `GlExportStatusChanged` for the audit trail.
- **Reconciliation**: closing balances per GL account (debit positive, base currency, non-memo accounts) of the
  sub-ledger, of what was exported, and of the general ledger as imported (`gl_balance`). The two differences show
  what was not exported and what the general ledger does not have.
- **Hard close gates**, configurable (`etrm.ledger.hard-close-gates`):
  - everything exported;
  - the latest export acknowledged;
  - the period end run.

  A hard close that fails one is refused with every reason.
- **Posting rule changes** go through `POSTING_RULE_CHANGE` (four eyes, ACCOUNTANT). Applying a change bumps the
  rule's version and records it in `posting_rule_history`. Shipped default rules still come from the versioned
  resource (ADR 0016's seeder); entity overrides and new rules go through the inbox.

## Consequences
- The month-end order is fixed by the gates: period end run, soft close, export, deliver, acknowledge, reconcile,
  hard close. Entities that do not use an external GL can switch gates off.
- Exports made before this change have no entry records, so their periods may export again once. Dev data only.
