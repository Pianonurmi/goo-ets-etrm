# Architecture and delivery plan

This is the plan approved at the start of the project. It holds the target architecture, domain model,
non-functional targets, the five increments and the open questions. The arc42 documents and ADRs describe what is
built; this document describes where the system is going.

## Status after increment 1

Increment 1 is delivered. These points deliberately differ from the plan below:

| Plan | Built | Why |
|---|---|---|
| JDK 25 LTS | JDK 21 LTS | Toolchain available in the build environments; moving to 25 is a toolchain change in `build-logic` |
| PostgreSQL 17 | 17 in docker-compose and CI; also verified on 16 | Both carry `ltree`, `btree_gist` and range types |
| Taxonomy closure table | `ltree` paths with GiST indexes | One mechanism for both the taxonomy and the criteria compiler (`path <@ ancestor`) |
| ArchUnit plus Spring Modulith verification | Spring Modulith verification only (`ModularityTests`) | Modulith already enforces named interfaces and allowed dependencies; ArchUnit stays in the version catalog for finer rules |
| Kotlin explicit API mode | Not enabled; `-Werror` is | Modules expose their API through Modulith named interfaces instead |
| Kover gate 85 % on domain modules | 70 % total line coverage rule, generated jOOQ code excluded | Raise per module as increments add tests |
| OWASP dependency check | Not configured; Dependabot, Trivy image scan and CycloneDX SBOM are | Add with an NVD API key in increment 5 |
| OR-Tools CP-SAT exact solver | `Solver` port only; `EXACT` falls back to greedy and records it | Native library risk; greedy plus max-flow bounds met the increment 1 needs |
| Lot table partitioned by expiry year | Not partitioned | Planned with the other partitioning work in increment 5 |
| Coverage with priority-ordered augmentation per obligation | Augmentation per priority tier | Same guarantee for higher priorities at a fraction of the cost (ADR 0007) |
| Benchmark 100 k lots × 5 k obligations | Benchmark test defaults to 20 k × 1 k; full size via system properties | Keeps CI fast; see the README for the full-size command |
| File paths in section 7 | Actual layout uses `api`, `domain`, `application`, `adapter/rest`, `adapter/jooq` per module | See `CLAUDE.md` and arc42 section 5 |

## Status after increment 2

Increment 2 is delivered in milestones M0–M9 (see the [roadmap](roadmap.md)). These points differ from the plan or
are still open:

| Plan | Built | Why |
|---|---|---|
| G-REX and NECS API adapters with contract tests from the manuals | The API channel, its security and outcome handling, and ETRM's generic REST contract; no G-REX or NECS mapping | The G-REX manual could not be downloaded from the build environment; NECS has no public API documentation. Steps in `docs/integration/registries.md` |
| `StatementSource` pulling statements over APIs | Statements are uploaded (CSV, profile-driven) | Needs the registry API specifications above |
| EUA and purchase obligations in allocation | EUA deliveries and purchase receipts work from trade schedules (`DeliveryService`); allocation obligations stay GoO sales | No allocation choice exists for fungible allowances or for receipts |
| Partial credit notes | Credit notes reverse an invoice in full; deliveries are re-invoiced | Partial corrections are rare; full reversal keeps the audit trail simple |
| Realised FX from market data | Realised FX from the ledger's FX rate table (manual or imported rates) | Market data feeds arrive in increment 3 |
| Intercompany allowance receipts matched automatically | The selling entity's sale and the buying entity's receipt are recorded; the mirror purchase trade is matched by hand | Rare; listed in `docs/domain/ets-compliance.md` |
| Commitment memos reversed on amendment and cancellation | Released on delivery only | Memo accounts are informational; to be completed with valuation in increment 3 |
| VAT and standard rate seeds | Seeded and marked as needing tax sign-off | Not tax advice; the user's tax adviser signs them off before production |

---
## Context

`pianonurmi/goo-ets-etrm` is an empty repository. The goal is an enterprise-grade Energy Trading and Risk Management (ETRM) system for a European company group with several legal entities, covering:

- **Guarantees of Origin (GoO)**: EECS electricity certificates across the AIB hub and Nordic registries (Fingrid/Finextra, Grexel-operated registries, Statnett NECS, Energinet, Elering, …). Gas, hydrogen and heat GoO are modelled in the product hierarchy but their integrations are deferred.
- **EU ETS**: EUA and EUAA allowances in the Union Registry (phase 4), with the product model open for ETS2 and UK ETS later.
- **Trading, bookkeeping (sub-ledger), portfolio and position management, risk, settlement, compliance.**

The hard problem is GoO. A certificate carries a dozen attributes (energy source hierarchy, technology, country, production device, production period, commissioning date, support, labels, …). Trades are agreed on *criteria*, not on specific certificates, so a single sale can be satisfied by many different inventory lots, and a lot can serve many sales. Value sits in allocating the cheapest eligible certificates to each obligation while keeping scarce, premium certificates for premium demand and avoiding expiry. GoO "net position" is therefore a *coverage* problem, not simple netting. EU ETS is fungible and far simpler, but carries compliance deadlines, financial-instrument regulation (MiFID II / EMIR) and distinct accounting.

Decisions already taken with the user:

| Decision | Choice |
|---|---|
| Stack | Kotlin + Spring Boot, PostgreSQL, React (UI later) |
| First increment | Architecture docs + core domain + ledger + API, no UI |
| Tenancy | One company group, several legal entities (intercompany, consolidation) |
| Market scope | Electricity GoO via AIB hub + Nordic registries; EUA/EUAA in Union Registry |

Assumptions (flag if wrong): base currency EUR with multi-currency support; default business time zone Europe/Helsinki, configurable per legal entity; package root `com.pianonurmi.etrm`; no commercial licence constraints; GL system is external (SAP-style) and receives sub-ledger postings via export.

---

## 1. Target architecture

### 1.1 Style

**Modular monolith** with strict bounded contexts, deployable as one Spring Boot application, designed so any module can be extracted into a service later. Rationale: one team, strong consistency needs between trade/inventory/ledger, transactional allocation. Microservices from day one would add network partitions into the ledger path for no benefit.

- **Hexagonal architecture per module**: `domain` (pure Kotlin, no Spring), `application` (use cases, transactions, authorization), `adapter.in` (REST, scheduled jobs, event listeners), `adapter.out` (jOOQ repositories, registry clients, GL export).
- **Compile-time boundaries** with Gradle subprojects; modules talk only via each other's `api` package (public commands/queries) or via **domain events**. Enforced with ArchUnit and Spring Modulith verification.
- **Events**: Spring Modulith event publication registry acts as a transactional outbox. Internal consumers run in-process now; an externalizer to Kafka can be enabled later without changing producers.
- **Append-only journals** for the things auditors care about: trade versions, certificate lot events, ledger journal entries, registry instructions, allocation plans. Current-state tables are projections of these, kept in the same transaction.
- **Bitemporal master and trade data**: `valid_from/valid_to` (business validity) and `recorded_at/superseded_at` (transaction time) so any report can be reproduced "as of" any date, and corrections never destroy history.

### 1.2 Technology baseline

| Concern | Choice | Notes |
|---|---|---|
| Language / runtime | Kotlin 2.x on JDK 25 LTS | Explicit API mode, `-Werror`, context parameters avoided until stable |
| Framework | Spring Boot 4.x, Spring Modulith 2.x | Virtual threads enabled |
| Build | Gradle Kotlin DSL, version catalog, convention plugins in `build-logic/` | Reproducible builds, dependency locking |
| Persistence | PostgreSQL 17, jOOQ (generated from Flyway-migrated schema), Flyway | No JPA: explicit SQL, type-safe criteria compilation, predictable performance |
| IDs | UUIDv7 | Time-ordered, index friendly |
| Money / quantities | Own value classes: `Money(BigDecimal, Currency)`, `Quantity(BigDecimal, Unit)` with units MWh, tCO2e, certificates | No floating point anywhere in domain |
| API | REST + OpenAPI 3 (springdoc), problem+json errors, ETag/If-Match optimistic concurrency, idempotency keys on commands | GraphQL deferred |
| Security | Spring Security, OIDC (Keycloak in dev, Entra ID / any OIDC in prod), RBAC + attribute rules, segregation of duties | 4-eyes approvals via `workflow` module |
| Observability | Micrometer + OpenTelemetry (traces, metrics, logs), structured JSON logs, correlation ids | Grafana stack in `ops/` |
| Testing | JUnit 5 + Kotest assertions + kotest-property, Testcontainers (PostgreSQL), ArchUnit, Spring Modulith tests, REST Assured, Gatling later | Coverage gate (Kover) ≥ 85% on domain modules |
| Quality | detekt, ktlint, OWASP dependency check, Trivy image scan, CycloneDX SBOM | All in CI |
| Jobs | Spring scheduling + ShedLock (DB-based) | Expiry sweeps, reconciliation, MtM runs |
| Optimisation | Google OR-Tools CP-SAT (optional exact solver), greedy default | Behind a `Solver` port |
| CI/CD | GitHub Actions: build, test, lint, Docker image, SBOM, scan | Helm chart + Kustomize later |
| Docs | arc42 in `docs/`, ADRs (MADR) in `docs/adr/`, C4 diagrams in Mermaid/Structurizr DSL, domain glossary | Docs live with code |

### 1.3 Repository layout

```
goo-ets-etrm/
├── build.gradle.kts, settings.gradle.kts, gradle/libs.versions.toml
├── build-logic/                 # convention plugins (kotlin, spring, jooq, test)
├── docs/
│   ├── arc42/                   # 01-introduction … 12-glossary
│   ├── adr/                     # 0001-modular-monolith.md, 0002-jooq-over-jpa.md, …
│   ├── domain/                  # goo-attribute-schema.md, criteria-language.md, allocation.md, ledger-posting-rules.md, ets-compliance.md
│   ├── integration/             # registry adapters, GL export, market data
│   └── roadmap.md
├── platform/                    # shared kernel: ids, Money, Quantity, bitemporal, events, errors, auth context
├── modules/
│   ├── referencedata/           # legal entities, counterparties, books, registry accounts, calendars, currencies, production devices
│   ├── taxonomy/                # EECS energy source / technology / label / region hierarchies
│   ├── product/                 # commodity & instrument hierarchy, GoO criteria language, product buckets
│   ├── trade/                   # deal capture, versions, confirmation, intercompany mirroring
│   ├── inventory/               # certificate lots (GoO) and allowance holdings (ETS), lifecycle, reconciliation
│   ├── allocation/              # matching engine, coverage analysis, reservations, instructions
│   ├── registry/                # ports + adapters: Fingrid/Grexel, AIB-hub registries, Union Registry (stubs in increment 1)
│   ├── position/                # positions, exposure, expiry ladder, compliance ladder
│   ├── ledger/                  # chart of accounts, posting rules, journal, inventory costing, period close, GL export
│   ├── valuation/               # market data, curves, MtM, P&L (increment 3)
│   ├── settlement/              # invoices, payments, netting, VAT (increment 2)
│   ├── etscompliance/           # installations, emissions, free allocation, surrender (increment 3)
│   ├── risk/                    # limits, credit exposure, alerts (increment 3)
│   ├── workflow/                # approvals / 4-eyes, task inbox (increment 2, minimal in 1)
│   └── audit/                   # audit log, event journal query
├── apps/
│   └── api/                     # Spring Boot application assembling modules; Flyway owner; OpenAPI
├── ops/                         # docker-compose (postgres, keycloak, grafana), later helm/
└── .github/workflows/
```

Each module: `src/main/kotlin/com/pianonurmi/etrm/<module>/{api,domain,application,adapter}` and `src/test/...`. Each module owns its DB schema (`etrm_<module>`) and Flyway migrations under `db/migration/<module>/`.

---

## 2. Domain model

### 2.1 Reference data (`referencedata`)

- **LegalEntity** (books, base currency, time zone, accounting policies: inventory costing method, EUA classification, free allocation policy).
- **Book / Portfolio** hierarchy (trading, own-use/disclosure, hedging) with strategy tags.
- **Counterparty** (legal entity, LEI, registry accounts, credit limit reference, settlement instructions, VAT treatment, master agreement: EFET GoO annex, ISDA/EFET allowances appendix).
- **RegistryAccount** (registry, account id, type: OHA / trading / person holding / GoO account, owner legal entity or counterparty, trusted account list for Union Registry).
- **ProductionDevice** (GSRN/EAN-18, name, country, NUTS region, coordinates, grid, capacity, commissioning/decommissioning dates, energy source & technology codes, support schemes, labels, owner, own-device flag for expected issuance).
- **Installation / Aircraft operator** for ETS (permit id, verifier, free allocation schedule) — lives in `etscompliance`.
- Calendars (TARGET2, registry business days), currencies, FX source config.

### 2.2 Taxonomy (`taxonomy`)

Hierarchical code sets with effective dating and external code mapping (AIB EECS codes ↔ national registry codes):

- **EnergySource** (e.g. Renewable → Wind → Onshore/Offshore; Hydro → Run-of-river/Reservoir/Pumped; Solar → PV/Thermal; Biomass → Solid/Liquid/Gaseous; Fossil → Gas/Coal/…; Nuclear).
- **Technology** (AIB T-codes).
- **Label / Independent Criteria Scheme** (EKOenergy, Bra Miljöval, naturemade star, TÜV SÜD EE01/EE02, OK-power, Grüner Strom, …).
- **SupportScheme** per country.
- **Region sets** (Nordic, Baltic, EU27, EEA, AIB-hub, custom) over ISO-3166 countries.

Query API: `isDescendantOf(code, ancestor)`, closure table for fast SQL `WITHIN` predicates.

### 2.3 Product hierarchy (`product`)

```
Commodity
├── GoO
│   ├── Electricity (EECS-GO, national GO)
│   ├── Gas, Hydrogen, Heat/Cooling     (modelled, integrations deferred)
└── Emission allowance
    ├── EUA, EUAA (EU ETS phase 4)
    └── ETS2 allowance, UK ETS allowance (future)

InstrumentType: Spot/physical transfer · Forward (physical) · Future (exchange, cleared) · Option · Swap (EUA/EUAA) · Auction purchase · Internal transfer
```

**GoO ProductSpecification** = energy carrier + a **Criteria** tree (see 2.4) + delivery options (seller's/buyer's choice, allowed registries/accounts, min/max split constraints). Standardised specifications form the **product bucket catalogue** ("Nordic Hydro 2026", "EU Wind 2026 unsupported", "Finland Solar Q2-2026 EKOenergy"), used for positions, valuation curves and reporting. Ad-hoc specifications are allowed on trades and mapped to buckets.

**Emission allowance products** are fungible per type and jurisdiction; attributes are type (EUA/EUAA), phase, and for derivatives: exchange, contract month, ISIN/UPI, clearing house.

### 2.4 GoO criteria language (`product`)

A typed predicate tree stored as versioned JSON, compiled to SQL for inventory search and evaluated in memory for single lots.

- **Two-stage tree**: an *unbound* `Criterion` (stored, context-free) is bound with a `BindContext(deliveryDate, regionResolver, taxonomyResolver)` into a *bound* tree where delivery-relative predicates become concrete ranges/sets. SQL compilation and in-memory evaluation both run on the bound tree, which makes equivalence testable.
- Typed fields (closed enum): `EnergySource`, `Technology` (taxonomy), `DeviceCountry`, `IssuingBody`, `DeviceId`, `Capacity`, `ProductionPeriod`, `Commissioning`, `Labels`, `SupportProduction`, `SupportInvestment`.
- Leaf predicates: `Equals`, `InSet`, `NumRange`, `DateRange`, `DescendantOf(taxonomy code, includes self)`, `LabelsAny`, `LabelsAll`, `CountryInRegion` (binds to `InSet(DeviceCountry)`), `DeviceAgeAtDelivery(min,max years)` (binds to `DateRange(Commissioning)`), `VintageWindow(SameCalendarYear | MonthsBefore(n) | Explicit)` (binds to production-period containment), `MatchAll`.
- Composition: `All`, `Any`, `Not`. Null semantics fixed in both engines: `Equals`/`InSet` on NULL is false; `Not(x)` compiles to `NOT COALESCE(x, false)`.
- **jOOQ compilation**: taxonomy via PostgreSQL `ltree` (`path <@ ancestor`), labels via array operators (`&&`, `@>`), vintage via `daterange` containment, ranges via `between`.
- **JSON**: polymorphic on `type`, envelope `{"v": n, "criterion": …}`, JSON Schema validated on write, `CriteriaUpcaster(v→v+1)` chain on read so historical trades never need rewriting. Criteria rows are deduplicated by body hash.
- **Specificity score** (static tiebreaker, weights in a config table not code): `All` = sum, `Any` = min, `Not` = 0.5; device equality 10, taxonomy 2 × depth, `InSet` = log2(domain/|set|), country 4, region = log2(EU27/|region|), label 5, vintage ≤ 12 months 3, device age 3. The solver uses *dynamic* constrainedness (eligible supply ÷ demand) as the primary ordering.
- Equivalence of SQL compilation and in-memory evaluation proven by property-based tests with NULLs and boundary dates in the generators.

### 2.5 Trade (`trade`)

- **Trade** aggregate: header (legal entity, book, counterparty, trader, trade date, master agreement, broker, external ids), **legs** (buy/sell, product spec or allowance type, quantity with unit, price with currency, pricing type fixed/index/formula), **delivery schedule** (one or more delivery windows with quantity, delivery registry/account, cut-off dates), payment terms, fees, status.
- **Versioning**: every amendment creates a new immutable version; current pointer; bitemporal query. Cancellations are versions with status `Cancelled`.
- **Confirmation state machine**: Draft → Pending approval (4-eyes if above threshold or outside limits) → Confirmed (counterparty confirmation matched: upload / eCM-style later) → Partially delivered → Delivered → Settled → Closed; branch to Disputed / Cancelled.
- **Intercompany**: a single capture creates mirrored trades in both legal entities with linkage; both post to their own ledgers.
- **Obligations** are derived from confirmed sell legs (deliver certificates meeting criteria by date) and from buy legs (expect receipt). Internal cancellation needs (disclosure to end customers, own consumption) are obligations too, created in `allocation` from consumption forecasts/actuals.
- Emits: `TradeCaptured`, `TradeAmended`, `TradeConfirmed`, `TradeCancelled`, `DeliveryObligationCreated/Changed`.

### 2.6 Inventory (`inventory`)

**GoO certificate lots**: a lot is a contiguous certificate number range in one registry account from one EECS issuance, so attributes are homogeneous; quantity is `end − start + 1` MWh. Full EECS attribute set (see taxonomy and 2.4), expiry date stored (default production period end + 12 months per RED II art. 19(3), issuing body may set earlier). Cost basis per MWh and acquisition reference per lot (purchase trade link or own-issuance cost policy). Cancellation record (JSONB, set only when cancelled): beneficiary, consumption country, consumption period, purpose (disclosure, voluntary, compliance), registry cancellation reference, with consumption country and year promoted for disclosure reporting.

- **Status lifecycle**: `ISSUED → HELD ⇄ RESERVED; HELD/RESERVED → IN_TRANSFER → TRANSFERRED_OUT | HELD (failed); HELD → CANCELLED | EXPIRED (job) | SPLIT; any → WITHDRAWN`. A split never mutates the parent range: it creates child lots with `parent_lot_id` and an `ltree` lineage; live set = `{HELD, RESERVED, IN_TRANSFER}`.
- **Storage**: promoted, indexed columns for everything criteria query (issuer, country, denormalised device country/region/commissioning/capacity synced by trigger, `ltree` energy-source and technology paths, `daterange` production period, expiry, support flags, `text[]` labels, status, account, holder, version, cost basis); JSONB `attributes` for label detail, issuer-specific extras and raw hub message fields.
- **Integrity**: `EXCLUDE USING gist (issuance_ref WITH =, cert_range WITH &&)` on live lots prevents overlapping ranges, the single most important invariant. Partial indexes on `(holder, account, status)` for the allocation snapshot and on `expiry_date` for the expiry job; GiST on taxonomy paths and production period; GIN on labels and attributes. Range-partition by expiry year so expired/cancelled partitions go cold.
- **History**: `lot_event` append-only journal (identity `seq`, `recorded_at` transaction time, `effective_at` valid time, actor, correlation id, payload, hash chain for tamper evidence; no UPDATE/DELETE grants), `certificate_lot` as current projection, `certificate_lot_version` with `sys_period`/`valid_period` ranges for "position as of T, recorded as of R". Projection rebuild from the journal is a tested capability.

**ETS holdings**: fungible balance per registry account and allowance type, with movement journal (allocation received, purchased, sold, surrendered, deleted). Cost layers for costing.

**Reconciliation**: import registry statements (CSV/XML/API) and diff against internal lots/holdings; break management workflow. This is the key operational control.

Emits: `LotIssued`, `LotReceived`, `LotSplit`, `LotReserved`, `LotReservationReleased`, `LotTransferInitiated`, `LotTransferred`, `LotCancelled`, `LotExpired`, `HoldingChanged`, `ReconciliationBreakDetected`.

### 2.7 Allocation & matching (`allocation`)

The core value engine. Inputs: open obligations (with criteria, quantity, delivery date, target account, constraints), available lots, scoring inputs (bucket marks from `valuation`, scarcity, expiry urgency, transfer cost/feasibility between registries, cost basis).

Pipeline:

1. **Load & classify**: one scan of live free lots into memory (~30 MB for 100 k lots), aggregated into **lot classes** sharing `(device, production period, account, labels, support flags, cost-basis bucket, expiry)`. Every criterion is class-invariant, so solvers work on 5–15 k classes and disaggregate to concrete ranges only at reservation.
2. **Eligibility**: evaluate each product bucket's bound criteria once over all classes into a bitmap; an obligation's bitmap = bucket bitmap ∧ its delivery-specific predicates. Exclude lots expiring before delivery or without a feasible transfer route to the target account (route matrix: same account 0, intra-registry cheap, cross-registry via AIB hub with fee, days and label restrictions).
3. **Scoring** (cost to deliver class to obligation, lower is better): opportunity cost = mark of the best bucket the class fits + transfer cost + scarcity (Σ over competing obligations of their demand ÷ eligible supply) − expiry urgency bonus + cost-basis/tax adjustment per entity policy.
4. **Solve**: default deterministic greedy ordering obligations by eligible-supply ÷ remaining-demand, then delivery date, then static specificity, then id; cheapest-to-deliver class first; `MIN_SHARE` quotas filled first, `MAX_SHARE_PER_DEVICE` tracked as running caps; only the last class taken is split. Optional exact solver (OR-Tools CP-SAT) for nightly batch: integer `x[class, obligation]` over top-K classes per obligation, lexicographic objective (maximise priority-weighted coverage, then minimise score), greedy solution as hint, 60 s limit, fallback to greedy. Native-library risk noted; Timefold is the pure-JVM fallback.
5. **Plan state machine**: `DRAFT → PROPOSED → APPROVED → RESERVED → INSTRUCTED → CONFIRMED | PARTIALLY_FAILED → CLOSED`, side exits `REJECTED | EXPIRED | STALE | CANCELLED`; lines carry their own states. Four-eyes enforced by role check and a DB `CHECK (approved_by IS DISTINCT FROM proposed_by)`. **Soft reservation** at PROPOSED (48 h default expiry, excluded from other plans' snapshots), **hard reservation** at APPROVED (lot status `RESERVED`) until the registry confirms. Registry instruction (transfer or cancellation) → confirmation from statement → lots transition, ledger events fire.
6. **Coverage analysis** (never commits): bipartite flow source → class (capacity qty) → obligation (edge if eligible) → sink (capacity demand), Dinic max-flow with priority-ordered augmentation (external sale > intercompany > internal disclosure, overridable) giving per-obligation shortfall; min-cut identifies jointly short obligations as a **shortage group**; residual class capacity per bucket is surplus. This is the GoO *net position* consumed by `position`: a Nordic hydro lot can serve a "Nordic" or a "Hydro" obligation, so naive netting double-counts while flow does not. Share constraints are outside the flow, so coverage is an upper bound and the solver plan a lower bound; both are reported.

Idempotency: plan requests carry an idempotency key and record the inventory `snapshot_seq`; instructions carry a unique key passed to the registry as client reference; confirmations dedupe on external reference. Concurrency: solvers read without locks; the approve → reserve commit runs under a per-holder advisory lock with optimistic `version` checks per lot, any miss marks the plan `STALE` and triggers an incremental rerun of the affected obligations only. The class/bitmap projection stays in memory, updated by inventory and obligation events, with coverage recomputed per connected component and a full rebuild on startup or sequence gap.

### 2.8 Registry integration (`registry`)

Ports: `RegistryStatementSource`, `TransferInstructionPort`, `CancellationInstructionPort`, `AccountBalancePort`. Adapters per registry, all built around **instruction → execution → confirmation**, because several registries (notably the Union Registry) have no API for account holders: the system prepares the instruction, a user executes it in the registry with the registry's own 4-eyes, and the statement import confirms it. Where an API exists (Grexel-based registries), the adapter executes directly. Cross-border GoO transfers go through the exporting registry (AIB hub). Increment 1 ships the ports, a file-import adapter and an in-memory simulator; real adapters in increment 2.

### 2.9 Position (`position`)

Position = aggregation per (legal entity, book, product bucket, delivery or compliance period) of: inventory, contracted purchases (+), sales (−), derivatives, expected own issuance (+), forecast disclosure needs (−). For GoO, coverage/shortfall from `allocation` replaces naive netting; an **expiry ladder** shows certificates by months-to-expiry versus uncovered demand. For ETS, a **compliance ladder** per installation and year: verified/forecast emissions − (holdings + free allocation + net forwards) = net short/long and hedge ratio. Read models are rebuilt from events and cached.

### 2.10 Ledger / bookkeeping (`ledger`)

A true double-entry **sub-ledger** per legal entity feeding the external GL.

- Chart of accounts per entity with GL account mapping; dimensions: book, product bucket, counterparty, cost centre.
- **Posting rules engine**: `(business event, product class, book type, entity policy) → journal template`. Rules are data, versioned, testable in isolation.
- Business events posted: trade confirmed (commitment memo accounts), delivery/receipt (inventory, COGS, revenue, purchases), invoice issued/received (AR/AP, VAT incl. reverse charge for EUAs), cash settled, MtM (derivatives at fair value under IFRS 9; trading inventory at FV less cost to sell where policy says so), impairment (lower of cost and NRV), expiry write-off, own-use cancellation (expense / disclosure cost), free allocation received (IAS 20 policy: nominal or fair value), surrender (release of provision; IAS 37 provision for emissions built from `etscompliance`).
- **Inventory costing**: GoO lots use specific identification by default (lot-level cost), with FIFO or weighted average selectable per entity/product class; EUA holdings use cost layers (FIFO/WAC).
- Journal entries immutable; corrections by reversal + repost. Accounting periods with soft/hard close; multi-currency with FX at transaction date and period-end revaluation. GL export (CSV / SAP-style journal) with reconciliation report sub-ledger ↔ GL.

### 2.11 Valuation (`valuation`, increment 3)

Market data ingestion (EEX/ICE EUA settlements, GoO broker/platform quotes per bucket, ECB FX), curve construction per product bucket and delivery period, MtM and P&L explain (price, volume, new trades, FX, time), realised vs unrealised.

### 2.12 Settlement (`settlement`, increment 2)

Invoice generation from delivered quantities and prices, self-billing, credit notes, payment schedules, netting agreements, VAT rules (reverse charge for emission allowances in most member states; GoO rules per country), cash forecast, bank file export later.

### 2.13 ETS compliance (`etscompliance`, increment 3)

Installations and aircraft operators, monitoring plan refs, emissions forecasts and verified emissions, free allocation schedule (issued by 30 June), reporting deadline (31 March), surrender deadline (30 September), surrender instruction creation, compliance status, MiFID II ancillary-activity and EMIR reporting data capture (UTI, UPI, ISIN) for allowance derivatives.

### 2.14 Risk (`risk`, increment 3)

Position limits per book/bucket, counterparty credit exposure (MtM + delivered-not-paid), concentration, expiry risk, limit breaches as workflow tasks.

### 2.15 Cross-cutting: workflow, audit, security

- **Workflow**: generic approval tasks (trade above threshold, allocation plan, registry instruction, posting rule change, master data change) with segregation of duties (requester ≠ approver, role constraints).
- **Audit**: every command records who/when/why/correlation id; the event journal plus bitemporal tables give full reconstruction; audit API for export.
- **Security**: OIDC SSO, roles (trader, middle office, back office, registry operator, accountant, risk, read-only auditor, admin), per-legal-entity and per-book access, field-level protection for cancellation beneficiaries (GDPR: data minimisation, encryption at rest, retention policy).

---

## 3. Non-functional requirements (design targets)

| Area | Target |
|---|---|
| Volumes | 10 M certificates/year in ≤ 200 k lots; 50 k trades/year; 5 k open obligations; 2 k registry instructions/year |
| Allocation | Greedy run over 100 k lots × 5 k obligations < 60 s; coverage analysis incremental < 5 s after an event |
| API | p95 < 300 ms for reads, < 1 s for commands |
| Availability | 99.9 % business hours; stateless app, horizontally scalable; PostgreSQL HA |
| Durability | RPO ≤ 15 min, RTO ≤ 4 h; point-in-time recovery |
| Retention | 10 years for trades, lots, ledger; GDPR deletion for personal data where law allows |
| Auditability | Any report reproducible as-of any past date |
| Security | SoD enforced, 4-eyes on transfers and surrenders, immutable audit |

---

## 4. Increment plan

### Increment 1 — Foundation, core domain, ledger, API (this plan's implementation scope)

Deliverables, in build order:

1. **Repo scaffold**: Gradle multi-module with convention plugins, version catalog, `platform` shared kernel (`Money`, `Quantity`, `UuidV7`, bitemporal helpers, domain event base, error model), `apps/api` with Flyway, jOOQ codegen from migrations, Testcontainers test base, ArchUnit rules, detekt/ktlint, GitHub Actions CI, `ops/docker-compose.yml` (PostgreSQL 17, Keycloak), `README.md`.
2. **Docs**: arc42 skeleton with sections 1–8 filled, ADRs 0001–0010 (modular monolith, jOOQ, bitemporality, event outbox, criteria language, lot-range storage, posting rules as data, UUIDv7, OIDC, instruction/confirmation registry pattern), domain docs for GoO attribute schema, criteria language, allocation, posting rules, glossary, roadmap.
3. **`referencedata`**: legal entities, books, counterparties, registry accounts, production devices, calendars, currencies; REST CRUD with bitemporal versions.
4. **`taxonomy`**: code sets with closure table, seed data for AIB energy source/technology codes, labels, region sets; REST read API.
5. **`product`**: commodity/instrument hierarchy, criteria language (sealed classes, JSON codec with schema version, jOOQ compiler, in-memory evaluator, specificity), product bucket catalogue; property tests for SQL ≡ in-memory.
6. **`trade`**: trade aggregate with versions, legs, delivery schedules, confirmation state machine (approval via minimal `workflow`), intercompany mirroring, obligation derivation; REST commands/queries.
7. **`inventory`**: certificate lots as ranges with split/merge, lifecycle, expiry sweep job, ETS holdings with movements, statement import (CSV) and reconciliation diff; REST.
8. **`allocation`**: eligibility, scoring (stub marks until `valuation`), greedy solver with constraints, plan/approval/reservation/instruction state machine, coverage analysis (max-flow), incremental recompute on events; `Solver` port with CP-SAT adapter behind a feature flag.
9. **`registry`**: ports, CSV statement adapter, simulator adapter for tests and demos.
10. **`position`**: read model builder, GoO coverage positions, expiry ladder, ETS holdings/contract positions, as-of queries.
11. **`ledger`**: chart of accounts, posting rules engine with seeded IFRS-style rule set, journal, costing (specific id, FIFO, WAC), periods and close, GL export CSV, reconciliation report.
12. **`audit` + minimal `workflow`**: audit log, approval tasks with SoD checks.
13. **End-to-end scenario tests** (golden path): capture GoO purchase → receive lots → capture GoO sale with criteria → coverage shows covered → allocate → approve → instruct → confirm → ledger shows inventory out, COGS, revenue; EUA buy → holdings up → compliance ladder → surrender → provision released.

### Increment 2 — Registry integrations, settlement, workflow

Real adapters (Fingrid/Finextra & other Grexel-based registries, AIB-hub member registries via export/import, Union Registry statement import and instruction packs), reconciliation break workflow, invoicing/VAT/netting, counterparty confirmation matching, full approval inbox.

### Increment 3 — Valuation, risk, ETS compliance, reporting

Market data feeds (EEX/ICE, GoO platforms, ECB FX), curves, MtM, P&L explain, limits & credit exposure, installations/emissions/free allocation/surrender, EMIR/MiFID data, reporting read models and exports, GL interface hardening.

### Increment 4 — Web UI

React + TypeScript (Vite), design system, trade blotter, inventory explorer with criteria builder, allocation workbench (plan review, what-if), coverage & expiry dashboards, ledger browser, approval inbox, admin.

### Increment 5 — Scale & operations

Kafka externalisation of events, Helm/Kubernetes, HA PostgreSQL, performance benchmarks, DR drills, penetration test, SOC-style controls documentation.

---

## 5. Key design decisions to record as ADRs

1. Modular monolith on Spring Modulith; module extraction path documented.
2. jOOQ + Flyway instead of JPA; schema-per-module.
3. Bitemporal tables for master data and trades; append-only journals for lots, ledger, instructions.
4. Transactional outbox via Spring Modulith event publication registry.
5. GoO criteria as a versioned typed predicate language, compiled to SQL and evaluated in memory.
6. Certificates stored as contiguous ranges (lots) that split, never as one row per certificate.
7. Allocation: greedy default + optional exact solver; coverage via max-flow defines GoO position.
8. Posting rules as versioned data, not code.
9. Registry integration pattern: instruction → execution (API or manual) → confirmation by statement.
10. UUIDv7 ids, UTC instants, closed-open date ranges, no floating point in domain.

---

## 6. Verification

- `./gradlew build` runs lint (detekt, ktlint), ArchUnit and Spring Modulith boundary tests, unit tests, Testcontainers integration tests, coverage gate.
- Property-based tests: random bound criteria trees (depth ≤ 5) over random lot populations with NULL commissioning dates, empty labels and boundary production periods, asserting SQL result set ≡ in-memory filter; JSON round-trip and upcaster stability; solver invariants (Σ allocated per lot ≤ quantity, every line satisfies bound criteria and share constraints, greedy byte-identical for same input, CP-SAT coverage ≥ greedy and ≤ max-flow); ledger invariants (every journal entry balances per currency; period totals reconcile to GL export).
- Golden scenarios (YAML fixtures → expected plan JSON): Nordic hydro vs generic EU wind contention; max-share-per-device forcing splits; expiring lots prioritised; infeasible cross-registry route; stale plan under concurrent reservation; self-approval rejected; soft reservation expiry; valid-time correction changing historical position; projection rebuild from `lot_event` equals current table.
- Integration tests on Testcontainers: Flyway migrations with `ltree`, overlapping-range exclusion violation, advisory-lock commit path with two concurrent reservers, Modulith outbox completion, `ApplicationModules.verify()`.
- End-to-end scenario tests from §4 item 13 executed through the REST API with REST Assured against the assembled `apps/api`.
- `docker compose up` in `ops/` + `./gradlew :apps:api:bootRun` → OpenAPI UI at `/swagger-ui`, demo data seeding profile `demo` to walk the golden path manually.
- Allocation benchmark test (JMH or plain timing in integration test) with synthetic 100 k lots / 5 k obligations against the NFR target.
- CI green on GitHub Actions for the branch `ccr-c2c9258a-sd2hhq`.

## 7. Critical files in increment 1 (where the hard logic lives)

- `modules/product/src/main/kotlin/com/pianonurmi/etrm/product/criteria/Criterion.kt` — sealed tree, bind, JSON codec, upcasters, specificity.
- `modules/product/src/main/kotlin/com/pianonurmi/etrm/product/criteria/JooqCriterionCompiler.kt` and `InMemoryCriterionEvaluator.kt`.
- `modules/inventory/src/main/resources/db/migration/inventory/V1__inventory_schema.sql` — lot, version, event journal, exclusion constraint, partitions.
- `modules/inventory/src/main/kotlin/com/pianonurmi/etrm/inventory/domain/CertificateLot.kt` — range split/merge, lifecycle.
- `modules/allocation/src/main/kotlin/com/pianonurmi/etrm/allocation/solver/{LotClassifier,GreedySolver,CoverageAnalyser,CpSatSolver}.kt`.
- `modules/allocation/src/main/kotlin/com/pianonurmi/etrm/allocation/application/AllocationPlanService.kt` — state machine, idempotency, advisory-lock commit.
- `modules/ledger/src/main/kotlin/com/pianonurmi/etrm/ledger/domain/{PostingRule,JournalEntry,CostingPolicy}.kt` and seeded rule set in `src/main/resources/posting-rules/`.
- `modules/trade/src/main/kotlin/com/pianonurmi/etrm/trade/domain/Trade.kt` — versions, legs, delivery schedules, obligation derivation.
- `platform/src/main/kotlin/com/pianonurmi/etrm/platform/{Money,Quantity,Bitemporal,DomainEvent}.kt`.

## 8. Open questions for later increments (not blocking)

- Exact list of registries and whether API credentials exist (Grexel API access is contractual per registry).
- Entity accounting policies: EUA as intangible vs inventory; free allocation at nominal vs fair value; GoO own-use treatment.
- Which GL system and interface format (SAP IDoc vs flat file).
- Whether exchange-cleared EUA futures with margining are needed in increment 3 or later.
- Market data vendors for GoO bucket marks.
