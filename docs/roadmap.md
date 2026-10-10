# Roadmap

| Increment | Scope | Status |
|---|---|---|
| 1 | Foundation, core domain, ledger, API (no UI): reference data, taxonomy, product and criteria language, trade, inventory, registry ports and simulator, allocation engine, position, ledger, workflow, audit, CI, docs | delivered |
| 2 | Registry integrations (Grexel-based APIs, hub message formats, Union Registry statement import), reconciliation break workflow, settlement and invoicing with VAT and netting, counterparty confirmation matching, full approval inbox | planned |
| 3 | Valuation and market data (EEX/ICE, GoO platforms, ECB FX), curves, MtM and P&L explain, risk limits and credit exposure, ETS compliance (installations, verified emissions, free allocation, surrender), EMIR/MiFID data, reporting | planned |
| 4 | Web UI (React + TypeScript): blotter, inventory explorer with criteria builder, allocation workbench, coverage and expiry dashboards, ledger browser, approval inbox, admin | planned |
| 5 | Scale and operations: Kafka externalisation of events, Helm/Kubernetes, HA PostgreSQL, partitioning of version and event tables, exact (CP-SAT) solver, performance and DR drills, penetration test | planned |

Known gaps to close before production: AIB and registry external code lists, entity accounting policy sign-off,
GL interface format, market data vendor for bucket marks.
