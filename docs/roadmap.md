# Roadmap

| Increment | Scope | Status |
|---|---|---|
| 1 | Foundation, core domain, ledger, API (no UI): reference data, taxonomy, product and criteria language, trade, inventory, registry ports and simulator, allocation engine, position, ledger, workflow, audit, CI, docs | delivered |
| 2 | Registry integrations (outbox, statement formats, Union Registry, API channel), reconciliation break workflow, settlement and invoicing with VAT and netting, counterparty confirmation matching, full approval inbox | delivered (G-REX and NECS mappings wait for their specifications; see the milestone list below) |
| 3 | Valuation and market data (file imports, ECB FX), curves, MtM and P&L explain, period-end accounting, risk limits and credit exposure, ETS compliance (installations, verified emissions, free allocation, surrender), EMIR/MiFID data, reporting, GL interface | delivered (vendor feeds, EMIR layout and SAP layout wait for specifications; see the architecture plan's status) |
| 4 | Web UI (React + TypeScript, bundled in the API jar): approval inbox, trade blotter and capture, inventory explorer with criteria builder, allocation workbench, coverage and expiry dashboards, market data and curves, valuation, risk, ETS compliance, ledger, month end, reports, admin | in progress |
| 5 | Scale and operations: Kafka externalisation of events, Helm/Kubernetes, HA PostgreSQL, partitioning of version and event tables, exact (CP-SAT) solver, performance and DR drills, penetration test | planned |

Known gaps to close before production: AIB and registry external code lists; sign-off of accounting policies (MtM by
book type, NRV, provision), VAT rules and PFE add-ons; the receiving GL's file specification; market data vendors for
EUA futures and GoO marks; EMIR field layout and trade repository delegation; ETS deadline rules checked by compliance.

## Increment 2 milestones

| Milestone | Scope | Status |
|---|---|---|
| M0 | Foundations: savepoint fix for allocation approval, ShedLock, registry HTTP client factory, idempotent deliveries, registry and reconciliation fixes, `document` module | done |
| M1 | Approval inbox: workflow handler SPI, assignment, comments, SLA and escalation, filters, bulk decisions, scope enforcement | done |
| M2 | Registry framework: outbox dispatch outside the transaction, statement formats, confirmation by statement, four-eyes on manual instructions, four-eyes master data changes | done |
| M3 | Counterparty registry accounts with Union Registry trusted-list status | done |
| M4 | Union Registry allowance instructions, EUA sell delivery, purchase receipt matching, allowance reconciliation | done |
| M5 | Reconciliation break workflow | done |
| M6 | Trade confirmation matching, obligations-open policy per entity | done |
| M7 | Settlement: invoices, VAT rules, payments, netting, ledger postings | done |
| M8 | Registry API adapters: API channel framework and generic REST contract done; G-REX and NECS mappings wait for the specifications | done (framework) |
| M9 | Documentation consolidation | done |

## Increment 3 milestones

| Milestone | Scope | Status |
|---|---|---|
| M0 | Fixes: final hard close, commitment memos per schedule (released and reversed), FX inverse and cross rates, bucket mapping by criteria implication, compliance ladder netting, role and entity checks, GL export correctness, versioned default rules | done |
| M1 | `marketdata` module: instruments, quotes, FX, import formats as data, ECB reference rate adapter (disabled by default), futures contract catalogue | done |
| M2 | Curves with official snapshots and sign-off; allocation marks from vintage curves; INDEX legs priced from fixings | done |
| M3 | Regulatory trade data (UTI, UPI/ISIN, venue, execution time, clearing), EMIR classification and counterparty groups, futures validation | done |
| M4 | `valuation` module: MtM per delivery schedule against official curves, end-of-day runs with sign-off, P&L explain | done |
| M5 | `etscompliance` module: installations, emissions with verified-emissions approval, free allocation, surrender attribution and requests, deadline reminders | done |
| M6 | Ledger period end: MtM by book type policy, NRV, emission provision, free allocation release, FX revaluation; policies under four eyes; index-leg memos at indicative prices | done |
| M7 | `risk` module: limits under four eyes, credit exposure (MtM, receivables, PFE, collateral), breaches, pre-trade check SPI; credit limits leave reference data | done |
| M8 | `reporting` module: report catalogue with logged, hashed and audited exports; EMIR events (NEWT/MODI/TERM/EROR/VALU) and completeness; MiFID ancillary activity | done |
| M9 | GL interface: exactly-once exports (period and delta), formats as data (generic CSV, SAP-style flat file, unverified), delivery, acknowledgement and rejection, GL balance reconciliation, hard-close gates, posting rule changes under four eyes | done |
| M10 | Documentation and end-to-end tests (`MonthEndIT`: market data to hard close) | done |

## Increment 4 milestones

| Milestone | Scope | Status |
|---|---|---|
| M0 | Backend for the UI: `ui-config` and `me`, SPA serving with cache headers and CSP, framework errors as problems, OpenAPI contract (operation ids, required, discriminated unions, problems) with a committed snapshot, entity scope on trade and lot reads, Keycloak web client (ADRs 0025–0027) | done |
| M1 | `apps/web` scaffold, Gradle and CI integration, shell, sign-in and personas, generated client, data grid, Playwright smoke test | done |
| M2 | Demo data profile (`dev,demo`): two entities, inventory, trades in several states, signed-off curves, official valuations, a period end, open approvals | done |
| M3 | Approval inbox (filters in the URL, cursor paging, bulk decisions, four-eyes cues, claim/assign, comments, history) and home | done |
| M4 | Criteria catalogue endpoint, criteria builder, inventory explorer (certificates, criteria search, allowances), lot detail with as-of and operations; manual lot operations now need back office or registry operator rights | done |
| M5 | Trade search endpoint (single query, keyset cursor), blotter, detail with versions diff, confirmations and MtM, capture and amendment with the criteria builder, confirm and cancel; REST guards for deliveries, settlement and confirmation issue | done |
| M6 | Allocation what-if and workbench; coverage, expiry and ETS dashboards | planned |
| M7 | Market data, curves, valuation | planned |
| M8 | Risk and ETS compliance | planned |
| M9 | Ledger browser, month end, reports (ADR 0028) | planned |
| M10 | Admin, reference data, taxonomy, change requests, audit | planned |
| M11 | Hardening: accessibility, performance budget, documentation | planned |
