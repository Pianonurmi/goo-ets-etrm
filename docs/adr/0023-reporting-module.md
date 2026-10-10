# ADR 0023: Reports from a catalogue, every export logged; EMIR captured as events, exported, not transmitted

- Status: accepted (the EMIR field layout is not verified)
- Date: 2026-10-10

## Context
- Finance, risk, operations, compliance and auditors need reports across modules. Each report reads several
  modules' APIs.
- What was handed out must be reproducible: who produced which report, with which parameters, and what it said.
- EMIR asks for a record of each reportable derivative's lifecycle and daily valuations (for FC and NFC+). The
  user decided on capture and export without trade repository connectivity.

## Decision
- A new module `reporting` (schema `etrm_reporting`). It reads referencedata, product, trade, inventory, marketdata,
  valuation, etscompliance, risk and ledger; nothing depends on it.
- **Report catalogue**: each report has a code, a title, the roles that may run it, its parameters, and its
  columns. Reports run as the requesting user, so the modules they read apply their own entity and role checks too.

  | Report | Rows | Roles |
  |---|---|---|
  | MTM_BY_TRADE | official valuation per schedule | risk, middle office, finance, audit |
  | PNL_DAILY | MtM of each official run and its change | same |
  | PNL_EXPLAIN | components between two runs | same |
  | POSITION_BY_BOOK | open quantity and MtM by book and product | same |
  | GOO_EXPIRY | live certificates by expiry month | operations, finance, audit |
  | CREDIT_EXPOSURE | exposure per counterparty | risk, middle office, audit |
  | LIMIT_UTILISATION | limits with their latest measurement | same |
  | ETS_COMPLIANCE_STATUS | installations for a compliance year | compliance roles, audit |
  | EMIR_TRADE_ACTIVITY | EMIR events in a period with their fields | same |
  | MIFID_ANCILLARY_ACTIVITY | allowance trades of a year by purpose | same |
  | GL_TRIAL_BALANCE | a period's movements per account | accountant, audit |
  | GL_RECONCILIATION | sub-ledger, exported and GL closing balances per GL account (ADR 0024) | accountant, audit |
- **Every run is an export**: `GET /api/v1/reports/{code}?format=csv|json&...`.
  - The run is numbered per report and entity (`report_export.seq`).
  - The content and its SHA-256 are stored, append-only, and the response headers carry both.
  - A `ReportExported` event goes to the audit trail.
  - Earlier exports can be fetched again as handed out (`GET /api/v1/reports/exports/{id}`).
  - CSV is RFC 4180 through `platform.text.Csv`, with plain decimal amounts.
- **EMIR events** are recorded by listeners:
  - a confirmation is NEWT the first time and MODI after that;
  - a cancellation of a reported trade is TERM;
  - a manual EROR corrects a trade reported by mistake;
  - a valuation sign-off gives VALU for FC and NFC+ entities.

  Events are unique per trade, action and source, so replays record nothing. They never fail the transaction that
  published them. A completeness check compares the latest events with `emir_field_rule`. Field names follow EMIR
  Refit and are flagged unverified.

## Consequences
- Report output is reproducible and audited, at the cost of storing every export's content. Retention and
  archiving come with the increment 5 data lifecycle work.
- EMIR reporting stays a manual or delegated submission from the export until compliance verifies the layout and a
  trade repository connection is chosen.
