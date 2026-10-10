# Roadmap

| Increment | Scope | Status |
|---|---|---|
| 1 | Foundation, core domain, ledger, API (no UI): reference data, taxonomy, product and criteria language, trade, inventory, registry ports and simulator, allocation engine, position, ledger, workflow, audit, CI, docs | delivered |
| 2 | Registry integrations (Grexel-based APIs, hub message formats, Union Registry statement import), reconciliation break workflow, settlement and invoicing with VAT and netting, counterparty confirmation matching, full approval inbox | in progress (see the milestone list below) |
| 3 | Valuation and market data (EEX/ICE, GoO platforms, ECB FX), curves, MtM and P&L explain, risk limits and credit exposure, ETS compliance (installations, verified emissions, free allocation, surrender), EMIR/MiFID data, reporting | planned |
| 4 | Web UI (React + TypeScript): blotter, inventory explorer with criteria builder, allocation workbench, coverage and expiry dashboards, ledger browser, approval inbox, admin | planned |
| 5 | Scale and operations: Kafka externalisation of events, Helm/Kubernetes, HA PostgreSQL, partitioning of version and event tables, exact (CP-SAT) solver, performance and DR drills, penetration test | planned |

Known gaps to close before production: AIB and registry external code lists, entity accounting policy sign-off,
GL interface format, market data vendor for bucket marks.

## Increment 2 milestones

| Milestone | Scope | Status |
|---|---|---|
| M0 | Foundations: savepoint fix for allocation approval, ShedLock, registry HTTP client factory, idempotent deliveries, registry and reconciliation fixes, `document` module | done |
| M1 | Approval inbox: workflow handler SPI, assignment, comments, SLA and escalation, filters, bulk decisions, scope enforcement | done |
| M2 | Registry framework: outbox dispatch outside the transaction, statement formats, confirmation by statement, four-eyes on manual instructions, four-eyes master data changes | done |
| M3 | Counterparty registry accounts with Union Registry trusted-list status | planned |
| M4 | Union Registry allowance instructions, EUA sell delivery, purchase receipt matching, allowance reconciliation | planned |
| M5 | Reconciliation break workflow | planned |
| M6 | Trade confirmation matching | planned |
| M7 | Settlement: invoices, VAT rules, payments, netting, ledger postings | planned |
| M8 | Registry API adapters (G-REX, NECS) | planned |
| M9 | Documentation consolidation | planned |
