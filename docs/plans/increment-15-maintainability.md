# Increment 15: maintainability

**Status: outline.** Follows [increment 14](increment-14-production-readiness.md) (0.14.0). Planned in detail when it
starts; this page fixes the scope and what increment 14 handed over.

## Context

Increment 14 closed what an auditor or an operator would refuse in the
[enterprise readiness review](../reviews/2026-10-enterprise-readiness.md). The review's maintainability findings
(C1, C2, C5–C7, C10, W8, W9) were held back on purpose, and increment 14 left a list of smaller items it found on the
way. Finding ids refer to the review.

## Scope

| Milestone | Content |
|---|---|
| M0 | Test seams (C2): Spring slices per module with the module's own context, non-null ports, wiring factories in `testFixtures` instead of hand-wired services with `Savepoints.NONE`; one migration order for tests and the application. Then the savepoint paths without a Spring-context test get one (risk pre-trade and concentration checks) |
| M1 | Large services split (C1): `RiskService`, `PeriodEndService`, `TradeService`, `ValuationService`, `AllocationService`, `RegistryInstructionService`, each into a few cohesive services behind the same module API; then `config/detekt/detekt.yml` tightened (complexity and size limits back to defaults where the split allows) |
| M2 | Module APIs (C5, C6): sealed scopes and value types in `api` packages; shared four-eyes, role and paging helpers in `platform`; keyset paging for invoices, payments and valuation results |
| M3 | Time and data (C7, S7 rest): the clock in repositories and event metadata; `TimeSourceTest` extended to tests (ADR 0050); `InvoiceService` dates in the entity's zone; beneficiary data out of hash-chained payloads (a table referenced by id, or per-subject encryption) and chain verification from a signed checkpoint, so partitions past retention can be archived and dropped ([data protection](../operations/data-protection.md)) |
| M4 | Build and supply chain (C10, S8 rest): jOOQ codegen inputs declared and the configuration cache on; the demo seeder out of the production jar; Gradle dependency locking and `verification-metadata.xml` with a workflow that refreshes them on Dependabot branches (or Renovate); the Gradle wrapper's distribution checksum ([supply chain](../operations/supply-chain.md#pinning)) |
| M5 | Web (W4, W6 rest, W8, W9): Vitest coverage over the whole tree with a ratchet and end-to-end journeys that create their own data; cursor paging for settlement items, invoices, payments and valuation results; roles per operation in the OpenAPI document (`x-roles`) and `permissions.ts` generated from it; large pages split; client errors reported to the backend; the remaining direct `formatMoney` calls through `Money` |
| M6 | Handed over by increment 14 (each small, each with a test): a confirmation of the original trade reaching a mirror still pending approval; account changes on amendment reaching the mirror; credit exposure marked incomplete when an MtM result is not OK; the month-average fixing retried when the month closes; the margin cascade stopping with a clear status on a missing price; the registry client's open-circuit flag (if O7 still wants it after a quarter of the parked-publication metrics); the smaller integrity items of D11 (plan proposal and approval under the entity's advisory lock before the plan is read, workflow `open` as an insert that tolerates the unique index, PDF rendering outside the transaction, unkeyed manual deliveries); one retry, not every waiter, after a failed `PortfolioFlowCache` build; concurrency tests for the remaining row locks (obligations, payments and invoices, lot reservation, registry, clearing, ledger, risk, market data, workflow, trade) |
| M7 | Operations: the CloudNativePG Barman Cloud plugin instead of the in-tree `barmanObjectStore` before operator 1.31; load test on a dedicated environment against the NFR table, `stress` and the batch scenario included ([load testing](../operations/load-testing.md)) |
| M8 | Close: arc42, the review's status lines, release 0.15.0 |

## Outside the code

The UAT period with real users after 0.14.0 feeds this increment's priorities. The external penetration test, the
accounting, VAT and PFE sign-offs and the registry, GL and EMIR specifications stay with the owner (issues from
increment 14 M0).
