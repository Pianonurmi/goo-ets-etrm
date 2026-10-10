# GL export and security

## GL export
`POST /api/v1/ledger/exports/{period}?legalEntityId=...&format=GENERIC_CSV|SAP_FLAT` (accountants with access to the
entity) exports the period's journal entries that no earlier export carried ([ADR 0024](../adr/0024-gl-interface.md)):

- **Exactly once**: each journal entry is carried by one export (`gl_export_entry`). The period's first export is
  `PERIOD`. Later ones are `DELTA` and carry adjustments posted since, or the entries of a rejected export. With
  nothing new the export is refused.
- **Formats as data** (`gl_export_format`; `GET /api/v1/ledger/export-formats`):
  - `GENERIC_CSV`: one RFC 4180 row per line, with `entry_number, posting_date, period, account, gl_mapping, debit,
    credit, currency, base_debit, base_credit, description, source_type, source_ref, reversal_of, dimensions`.
  - `SAP_FLAT`: `;`-separated FI document lines with `BUKRS` (entity code), `BLDAT`/`BUDAT` (yyyyMMdd), `MONAT`,
    `XBLNR` (entry number), `BUZEI`, `HKONT` (GL mapping), `SHKZG` (S/H), `WRBTR`/`WAERS`, `DMBTR`/`HWAER`, `SGTXT`.
    **Not verified** against a real SAP interface specification (`verified = false`).
  - Amounts are absolute with two decimals. Memo accounts (commitments) stay in the sub-ledger.
- **Refused** while the period is open, when the journal hash chain does not verify, or when an exported account has
  no GL mapping. Every export is numbered per entity, stored with its SHA-256, and publishes `GlExported`.
- **Delivery and acknowledgement**:
  - `POST /api/v1/ledger/exports/{id}/delivered`;
  - `POST /api/v1/ledger/exports/{id}/acknowledge` with the general ledger's document reference;
  - `POST /api/v1/ledger/exports/{id}/reject` with a reason, which releases the export's entries for the next one.
- **Reconciliation**: `PUT /api/v1/ledger/gl-balances/{period}` imports the general ledger's closing balances per GL
  account (debit positive). `GET /api/v1/ledger/reconciliation?legalEntityId=&period=` then compares, per GL account:
  - the sub-ledger closing balance;
  - what was exported (not rejected);
  - the GL balance.

  `notExported` is the sub-ledger less what was exported; `glDifference` is what was exported less the GL balance.
- **Hard close gates** (`etrm.ledger.hard-close-gates`, all on by default):
  - `ALL_EXPORTED`: every entry of the period is in a non-rejected export;
  - `LAST_EXPORT_ACKNOWLEDGED`;
  - `PERIOD_END_RUN`.

  `GET /api/v1/ledger/periods/{period}/hard-close-blockers` lists what is still missing.
- **Posting rules** change through `PUT /api/v1/ledger/rules`. This creates a pending change that another accountant
  approves in the inbox (`POSTING_RULE_CHANGE`). Every applied version is kept
  (`GET /api/v1/ledger/rules/{code}/history`).

## Security
- Production: OIDC resource server (`spring.security.oauth2.resourceserver.jwt.issuer-uri`), roles from the `roles` claim, legal-entity scope from `legal_entities`.
- `dev` / `test` profiles: no token; `X-Actor` and `X-Roles` headers identify the caller so four-eyes flows can be exercised.
- Roles: TRADER, MIDDLE_OFFICE, BACK_OFFICE, REGISTRY_OPERATOR, ACCOUNTANT, RISK, AUDITOR, ADMIN. Segregation of duties is enforced in services (approver ≠ requester) and database constraints.
- Role and legal-entity checks: trade capture, amendment and cancellation (TRADER or MIDDLE_OFFICE), approval (MIDDLE_OFFICE or RISK), confirmation (MIDDLE_OFFICE or BACK_OFFICE); ledger periods, exports and rules (ACCOUNTANT), FX rates (ACCOUNTANT or MIDDLE_OFFICE), ledger queries (entity access); settlement reports (finance, risk and audit roles with entity access); ETS installations and emissions (MIDDLE_OFFICE, BACK_OFFICE or RISK; verified emissions approved by a second person); risk limits and credit support (RISK, four eyes); reference data maintenance (MIDDLE_OFFICE; registry accounts and devices also BACK_OFFICE and REGISTRY_OPERATOR); credit limits are risk limits since ADR 0022.
