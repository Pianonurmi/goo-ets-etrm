# GL export and security

## GL export
`POST /api/v1/ledger/exports/{period}?legalEntityId=...` (accountants with access to the entity) renders the period's
journal lines as RFC 4180 CSV (`entry_number, posting_date, period, account, gl_mapping, debit, credit, currency,
base_debit, base_credit, description, source_type, source_ref, reversal_of, dimensions`) with two-decimal amounts,
stores it with a SHA-256 content hash and an export number per entity, and returns it. `gl_mapping` carries the
external GL account from the chart of accounts. Memo accounts (commitments) stay in the sub-ledger.

The export is refused while the period is open, when the journal hash chain does not verify, or when an exported
account has no GL mapping. Every export publishes `GlExported`, which the audit trail records.

## Security
- Production: OIDC resource server (`spring.security.oauth2.resourceserver.jwt.issuer-uri`), roles from the `roles` claim, legal-entity scope from `legal_entities`.
- `dev` / `test` profiles: no token; `X-Actor` and `X-Roles` headers identify the caller so four-eyes flows can be exercised.
- Roles: TRADER, MIDDLE_OFFICE, BACK_OFFICE, REGISTRY_OPERATOR, ACCOUNTANT, RISK, AUDITOR, ADMIN. Segregation of duties is enforced in services (approver ≠ requester) and database constraints.
- Role and legal-entity checks: trade capture, amendment and cancellation (TRADER or MIDDLE_OFFICE), approval (MIDDLE_OFFICE or RISK), confirmation (MIDDLE_OFFICE or BACK_OFFICE); ledger periods, exports and rules (ACCOUNTANT), FX rates (ACCOUNTANT or MIDDLE_OFFICE), ledger queries (entity access); settlement reports (finance, risk and audit roles with entity access); compliance inputs (RISK or MIDDLE_OFFICE); reference data maintenance (MIDDLE_OFFICE; registry accounts and devices also BACK_OFFICE and REGISTRY_OPERATOR; credit limits RISK).
