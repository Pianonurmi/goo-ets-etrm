# GL export and security

## GL export
`POST /api/v1/ledger/exports/{period}?legalEntityId=...` renders every journal line of the period as CSV
(`entry_number, posting_date, period, account, gl_mapping, debit, credit, currency, base_debit, base_credit, description,
source_type, source_ref, dimensions`), stores it with a SHA-256 content hash and returns it. `gl_mapping` carries the
external GL account from the chart of accounts.

## Security
- Production: OIDC resource server (`spring.security.oauth2.resourceserver.jwt.issuer-uri`), roles from the `roles` claim, legal-entity scope from `legal_entities`.
- `dev` / `test` profiles: no token; `X-Actor` and `X-Roles` headers identify the caller so four-eyes flows can be exercised.
- Roles: TRADER, MIDDLE_OFFICE, BACK_OFFICE, REGISTRY_OPERATOR, ACCOUNTANT, RISK, AUDITOR, ADMIN. Segregation of duties is enforced in services (approver ≠ requester) and database constraints.
