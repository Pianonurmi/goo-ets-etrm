# 1. Introduction and goals

The system is an enterprise Energy Trading and Risk Management (ETRM) platform for a European company group with
several legal entities trading **Guarantees of Origin (GoO)** and **EU ETS emission allowances (EUA, EUAA)**.

## Requirements overview

| Capability | Increment 1 scope |
|---|---|
| Reference data | Legal entities with accounting policies, books, counterparties, registries and accounts, production devices, calendars; transaction-time history |
| Taxonomy | EECS-aligned energy source / technology hierarchies, labels, support schemes, region sets |
| Product | Commodity and instrument catalogue, GoO criteria language, standard product buckets |
| Trade | Deal capture with legs and delivery schedules, versions, four-eyes approval, confirmation, deliveries, intercompany mirroring |
| Inventory | Certificate lots as ranges with full EECS attributes, lifecycle, hash-chained journal, bitemporal history; allowance holdings; registry reconciliation |
| Allocation | Matching of lots to obligations (sales, intercompany, disclosure), coverage analysis, plans with soft and hard reservations |
| Registry | Transfer routes, instruction state machine, simulator / manual / file gateways, CSV statement import |
| Position | GoO coverage position by bucket and vintage, expiry ladder, EU ETS compliance ladder, snapshots |
| Ledger | Double-entry sub-ledger with posting rules as data, costing, periods, FX, GL export |
| Workflow, audit | Approval inbox with segregation of duties, append-only audit trail |

## Quality goals

| Priority | Goal | Why |
|---|---|---|
| 1 | **Correctness and auditability**: every position, journal entry and certificate state is reproducible as of any past date; no silent data loss | Regulated assets, external audits, registry reconciliation |
| 2 | **Allocation value**: cheapest-to-deliver matching that keeps scarce certificates for the obligations that need them | This is where the GoO book makes money |
| 3 | **Operational safety**: four-eyes on transfers, cancellations, surrenders and plans; segregation of duties | Irreversible registry actions |
| 4 | **Evolvability**: strict module boundaries, so modules can be extracted or replaced | Registries, exchanges and accounting policies change |
| 5 | **Performance**: allocation over 100 k lots and 5 k obligations in under a minute | Nightly and intraday allocation runs |

## Stakeholders

| Role | Expectations |
|---|---|
| Traders | Fast capture, clear criteria, live coverage view |
| Middle office / risk | Approval control, positions, limits (later), reconciliation breaks |
| Back office / registry operators | Instruction tracking, statement import, confirmation workflow |
| Accountants | Balanced, reconciled sub-ledger; period control; GL export |
| Auditors / regulators | Immutable trail, as-of reconstruction |
| Developers | Clear module contracts, strong tests, reproducible builds |
