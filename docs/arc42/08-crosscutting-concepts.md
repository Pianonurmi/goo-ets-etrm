# 8. Cross-cutting concepts

- **Identifiers**: UUIDv7 everywhere (`Ids.newId()`); human-readable numbers for trades (`T-2026-000123`) and journal entries.
- **Time**: instants in UTC; business periods as closed-open `LocalDateRange`; entity time zone for reporting only.
- **Money and quantities**: `BigDecimal` value classes; certificates are whole MWh, allowances whole tCO2e; never floating point.
- **Versioning and optimistic locking**: every mutable aggregate carries `version`; commands state the expected version; stale updates fail with HTTP 409.
- **Bitemporality**: `reference_history` (master data), `trade_version` (trades), `certificate_lot_version` (lots) with transaction-time and valid-time periods.
- **Append-only journals**: `lot_event`, `journal_entry`/`journal_line`, `audit_event` are protected by triggers and hash chains; verification endpoints report broken chains.
- **Events**: Spring application events implementing `DomainEvent`, published inside the writing transaction and delivered to in-process listeners (audit, ledger, allocation, workflow). Consumers are idempotent on `eventId`.
- **Idempotency**: external-facing commands take idempotency keys (allocation plans, registry instructions); journal postings are unique per (event, rule).
- **Segregation of duties**: approver ≠ requester for trades, plans and tasks, enforced in services and by database check constraints.
- **Security**: OIDC resource server; roles from token claims; `dev`/`test` profiles accept `X-Actor`/`X-Roles` headers.
- **Errors**: domain exceptions map to RFC 9457 problem details (404, 409, 422, 403, 400).
- **Configuration as data**: posting rules, specificity weights, bucket marks, transfer routes live in tables with APIs.
