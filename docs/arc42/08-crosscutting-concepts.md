# 8. Cross-cutting concepts

- **Identifiers**: UUIDv7 everywhere (`Ids.newId()`); human-readable numbers for trades (`T-2026-000123`) and journal entries.
- **Time**: instants in UTC; business periods as closed-open `LocalDateRange`; entity time zone for reporting only.
- **Money and quantities**: `BigDecimal` value classes; certificates are whole MWh, allowances whole tCO2e; never floating point.
- **Versioning and optimistic locking**: every mutable aggregate carries `version`; commands state the expected version; stale updates fail with HTTP 409.
- **Bitemporality**: `reference_history` (master data), `trade_version` (trades), `certificate_lot_version` (lots) with transaction-time and valid-time periods.
- **Append-only journals**: `lot_event`, `journal_entry`/`journal_line`, `audit_event` are protected by triggers and hash chains; verification endpoints report broken chains.
- **Events**: Spring application events implementing `DomainEvent`, published inside the writing transaction and delivered to in-process listeners (audit, ledger, allocation, workflow). Consumers are idempotent on `eventId`.
- **Idempotency**: external-facing commands take idempotency keys (allocation plans, registry instructions, payments by bank reference, confirmations by reference); deliveries are unique per source key (ADR 0015); settlement items per delivery; journal postings per (event, rule).
- **Segregation of duties**: approver ≠ requester for trades, plans, registry instructions, master data and bank account changes, VAT rules, invoices, confirmation overrides, break acceptance and netting agreement; enforced by the inbox (task four-eyes), in services and by database check constraints.
- **Security**: OIDC resource server; roles from token claims; `dev`/`test` profiles accept `X-Actor`/`X-Roles` headers.
- **Errors**: domain exceptions map to RFC 9457 problem details (404, 409, 422, 403, 400).
- **Configuration as data**: posting rules, specificity weights, transfer routes, import formats and curve definitions live in tables with APIs.
- **Approval inbox**: owning modules register task types (`TaskHandler`) and apply decisions in the same transaction (ADR 0011).
- **Savepoints**: a caught domain exception from a transactional service would leave the transaction rollback-only; callers that tolerate a refusal (bulk decisions, delivery listeners) run the call in a savepoint (`Savepoints.attempt`).
- **Listeners that must not fail their trigger**: delivery recording from registry confirmations and receipts, settlement items from deliveries; problems are parked (`NEEDS_ATTENTION`, unmatched receipt) instead of thrown.
- **Outbox**: registry calls happen outside database transactions and are claimed with `SKIP LOCKED` and a lease (ADR 0012); ShedLock serialises jobs across instances.
- **Legal documents**: confirmations, invoices and netting statements are rendered once to PDF and stored immutable with a SHA-256 (ADR 0013); issued invoices and their lines are frozen by triggers; invoice numbers are gap-free per entity, series and year.
- **Data with sign-off**: VAT rules and standard rates are effective-dated rows approved by a second person; seeds are marked as needing tax sign-off.
- **Measure and post the difference**: period-end postings, risk utilisation and the emission provision are computed as the balance required now; the posting is the difference to what is booked, so re-runs are idempotent and late changes post only themselves.
- **Official numbers under four eyes**: curve snapshots and valuation runs become official only when a second person signs them off. Consumers (valuation, period end, risk, EMIR) read official data only, and a later official version supersedes an earlier one without deleting it.
- **SPIs instead of cycles**: a module that must take part in another's decision implements an interface the owner publishes in its `api` (`trade.api.TradeApprovalCheck`). The owner resolves it lazily.
- **Unverified external layouts**: EMIR field rules, the SAP GL layout, registry API mappings and PFE add-ons carry `verified = false` until checked against their specification.
- **Exports are evidence**: GL exports and reports are numbered and stored with their content and SHA-256, and each publishes an audited event.

