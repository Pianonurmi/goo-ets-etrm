# Increment 8: GoO portfolio management

**Status: delivered** (M0–M7). What differs from this plan is listed under [Delivered](#delivered); the decisions are in ADRs 0035–0040.

Increment 7 (the public showcase) is delivered and green on `main` (`a89d101`). Its plan is in
`docs/plans/increment-7-showcase-demo.md`.

## Context

The user asked how the system handles a GoO portfolio, where sales can be generic ("any EU renewable 2026") or
specific ("Nordic hydro 2025, unsupported"). Portfolio effects are the opportunities between them: a specific
certificate can deliver a generic sale, but a generic one cannot deliver a specific sale.

**What works today:**
- The criteria language, specificity and product buckets.
- Coverage as max-flow (ADR 0007), so specific supply covers generic demand correctly.
- Greedy and CP-SAT allocation (ADR 0031) that picks the cheapest qualifying class first.

**Gaps, verified in code:**
- The allocation score mixes units. It adds the EUR mark, a dimensionless scarcity ratio and a specificity fallback.
- Nothing prices scarce attributes or the premium given away when a specific lot covers a generic sale.
- There is no available-to-sell figure.
- Positions put each lot in one bucket only, with no nested view or netting.
- Valuation ignores allocation, so there is no portfolio value and no cheapest-to-deliver figure.
- Forward purchases and expected own issuance are not supply.
- GoO risk is a product-blind MWh sum.
- CP-SAT's "never worse than greedy" check covers coverage only and ignores priority in phase 1.

**Goal:** make the system manage the GoO book as a portfolio, covering:
- value-based allocation;
- available-to-sell;
- a flow-netted product hierarchy with portfolio value;
- future supply as tiers;
- GoO risk limits.

## User decisions

| Decision | Choice |
|---|---|
| Lot opportunity value (D1) | **Best price it qualifies for**: the maximum mark over every bucket the lot satisfies. Robust when curves are inverted; give-away is measured against it. |
| Future supply in plans (D6) | **Plans reserve against future**: the solver counts contracted and expected supply virtually, so held lots are not spent on sales that future supply will serve. |
| Cheapest-to-deliver marking (D10) | **Management view only**. Official MtM, ledger postings, EMIR VALU and credit exposure stay on bucket curves. |
| Expected issuance source (D8) | **Both**: a monthly forecast per device where entered, otherwise capacity × load factor (per technology and month). |

Defaults I take for the minor decisions; I will state each in its ADR:
- **D2 unpriced supply:** use the nearest priced ancestor in the hierarchy, and flag it.
- **D3:** an expiry haircut with horizon 60 days (configurable). The scarcity ratio leaves the cost and remains only a tie-break; LP shadow prices replace it.
- **D4:** CP-SAT phase 1 becomes priority-lexicographic, matching the coverage report.
- **D5:** GLOP duals in floating point, converted at the boundary and labelled *indicative*. They are never booked.
- **D7:** a forward purchase's assumed expiry is its delivery-window start + 12 months.
- **D9:** give-away appears in a separate portfolio explain. `pnl_explain` is unchanged, so stop-loss is unaffected.
- **D11:** cheapest-to-deliver is the allocated average for valuation and the marginal (λ) for quoting.
- **D12:** concentration is measured net after flow, and expiry counts unused supply only. Basis is in MWh, or EUR when the limit has a currency. A headroom breach gives a WARN finding.
- **D13:** a node with two parents is shown under each, with an "also under" badge.
- **D14:** cost basis stays out of the objective (it is sunk).
- **D15:** headroom shows inline on the capture form and in Check limits.
- **D16:** transfer fees are taken as EUR, with a guard that fails on any other currency.

## Design (cross-cutting)

### Flow core
New package `allocation.domain.flow`. Modules keep their dependencies: valuation and risk gain `allocation::api` only, and no cycle forms.

**Supply units.** `SupplyUnit` is a sealed type:
- `Held(lot class)`;
- `Contracted(BUY schedule)`, eligible by `implies(purchaseCriterion, saleCriterion)`;
- `Expected(device, month)`, built from the forecast or capacity × load factor, less what is already issued.

Each unit has a tier, quantity, availability date, expiry and registry.

**EligibilityMatrix.** Bitsets, extracted from `ProblemBuilder` and patched in place when events arrive.

**CompressedFlowGraph.** Obligations with identical eligibility merge into groups, and units with identical neighbourhoods merge into atoms. The compression is exact, with a property test against the uncompressed flow. It is a prerequisite at 100k lots × 5k obligations, not an optimisation.

**FlowState.** One max-flow per tier, cumulative: held, then +contracted, then +expected. Priority is handled by tier augmentation.

**PortfolioFlowCache.** One per entity, keyed by a stamp made of:
- the per-entity inventory `journalSeq`;
- obligation, reservation, BUY-schedule and forecast versions;
- the bucket catalogue hash.

Spring events trigger a debounced rebuild on a virtual thread. **Every read compares the stamp**, which keeps multiple pods safe. No lock is held across I/O.

### Value in EUR (`Scoring`)
- `cost(c,o) = R(c) + fee`.
- `R(c) = maxMark(c) × min(1, daysToExpiry/60)`, where `maxMark` is the best price over all buckets the lot qualifies for (D1).
- Future units: a contracted unit costs its contract price (D6); an expected unit costs its maxMark plus a reliability premium for the P50–P90 gap.
- `giveAway(c,o) = q × (maxMark(c) − saleMark(o))`, where `saleMark` is the mark of the obligation's bucket and vintage.
- `ScoreBreakdown` keeps its shape with EUR meanings and adds `saleMarkEur`, `giveAwayEur` and `unit`. Old plans stay readable.

### Plans reserve against future supply (D6)
The solver includes Contracted and Expected units as virtual columns. They are eligible only for obligations dated on or after their availability date plus route days. Expected units count at P90 in plans and P50 in views.

Outputs:
- **Real lines:** held lots only, reserved and instructed as today.
- **Virtual lines:** "to be served by purchase X / expected issuance of device Y in month M". They are stored, never reserved and never instructed, and shown on the plan.

A purchase receipt or issuance that arrives, fails or changes its forecast marks dependent plans STALE through events. Re-planning is then proposed, so nothing reserves silently.

Rule for the exact solver: coverage is maximised per priority tier with held + future together. Phase 3 prefers held supply for obligations that future supply cannot reach. Greedy is still the floor, compared lexicographically on (coverage, shortfall, cost).

### Hierarchy
`ProductBucketQuery.hierarchy()` binds every active bucket, computes `implies` for every pair, merges equivalent buckets as aliases, and takes the transitive reduction to get the Hasse DAG rooted at ANY_GOO. `CriteriaImplication` is exposed through `CriteriaService.implies` in `product.api`.

### Definitions
**Node figures**, per node, for all years and for each year:
- Long: supply guaranteed inside the node.
- Short: obligations that can only be served from inside the node.
- Covered within: the max-flow on that sub-graph.
- Short after netting.
- Free supply: the residual of the node's supply in the entity-wide flow.
- Available to sell.

**Headroom** for product P, delivery date d and tier τ is the max-flow from the source to a new node N. N is fed by the eligible units of tier ≤ τ, and the flow runs in the residual graph of the tier-τ flow **with the sink blocked**, so no existing coverage is stolen. The min cut names the binding (bucket, year, tier) groups. Several schedules are checked jointly through a super-node. The result is an upper bound when share constraints apply.

**Intrinsic value (IV)** at a date, on official curves, from the valuation-grade allocation (OR-Tools `MinCostFlow`, integer cents, priority by tier bonus):

IV = inventory at R + trade MtM − give-away − fees − expiry loss − shortfall premium.

## Milestones

Every milestone ends as increment 7 did:
- green `./gradlew build` and `:apps:web:check`;
- Playwright on a fresh database;
- the OpenAPI snapshot updated, and `api-only.json` with reasons;
- help page and tour updated with their screen;
- `ModularityTests` green;
- push to `main` only after the previous CI is green.

Sub-agents are used per CLAUDE.md: `sonnet` for well-specified UI and docs work, `haiku` for inventories, and main-model review of every diff.

### M0: Foundations (no behaviour change)
- **Product:** `CriteriaService.implies`; `VintageYears` moves from `valuation.domain` to `product.api.criteria`.
- **Trade:** `DeliveryObligationView` gains `bucketCode` and `bucketMatch`.
- **Allocation:**
  - migration `V3__obligation_bucket.sql` adds bucket, match and vintage to obligations;
  - extract the EligibilityMatrix, the Held-only SupplyUnit, the CompressedFlowGraph, and a sink-blocking Dinic with an undo log;
  - `CoverageAnalyser` runs on the compressed graph with identical output;
  - the CpSat floor becomes lexicographic: coverage, shortfall, cost.
- **Inventory:** `LotQuery.journalSeq(holder)`.
- **Tests:** compressed equals uncompressed (property test), a coverage regression on the existing fixtures, the CpSat floor, and benchmark timings at 20k/1k and 100k/5k.
- **Docs:** ADR 0036 draft (flow core and cache).

### M1: Value-based allocation in EUR
- **Policy:** `ValuePolicy` (`etrm.allocation.value.*`) replaces `ScoringWeights`.
- **Scoring:** best-price-it-qualifies-for marks (D1) and the expiry haircut. Greedy sorts by cost, then scarcity as a tie-break.
- **CpSat:** phase 1 is priority-lexicographic (D4); phase 3 minimises cost in cents.
- **API:** `PlanValue` on plans and what-if: total cost, give-away, fees, haircut, unpriced classes, and figures by obligation.
- **Persistence:** `V4__plan_value.sql`.
- **UI:**
  - what-if KPI strip `what-if-value-kpis`, with `Money` and a give-away grid;
  - plan detail "Value" tab `plan-value-tab`.
- **Demo:** step "Generic GoO curves" (EU_RENEWABLE and ANY_GOO curves), plus a generic sale that is forced onto hydro.
- **Tests:** generic lots go to generic sales while hydro is kept for the hydro sale; give-away; haircut; unpriced fallback; the exact solver never worse than greedy; priority tiers; IT for what-if and propose; Playwright on the what-if value.
- **Docs:**
  - ADR 0035 (value-based allocation; amends ADR 0031);
  - `docs/domain/allocation.md`;
  - user guide "Value and give-away";
  - allocation tour step.

### M2: Shadow prices (GLOP)
- **Pricing LP:** after the plan, with coverage fixed at the plan's figures. Constraints: supply, demand, device caps and min-shares. Duals give:
  - λ per obligation: marginal delivery cost;
  - π per class: scarcity rent, aggregated per (bucket, year);
  - the LP bound and gap.
- **Code:** `GlopPricer`, with floating point converted at the boundary and labelled indicative. Size and time limits; falls back to `null` with a reason.
- **API and persistence:** `PlanPricing`; `V5__plan_pricing.sql`.
- **UI:** a "Shadow prices" tab (`plan-shadow-prices`) and a marginal-cost column.
- **Tests:** hand-computed duals; complementary slackness on random instances; a cross-check against min-cost-flow potentials; fallback.
- **Docs:** ADR 0035 amended; glossary.

### M3: Available to sell (held tier)
- **API:** `HeadroomQuery.check(demands, excludeSourceRefs)` returns the figure per demand, the binding groups and the stamp. `headroomTable(entity)` covers buckets × vintages × month-ends, computed in the background.
- **REST:** `POST /api/v1/positions/goo/headroom/check` and `GET /api/v1/positions/goo/headroom`.
- **Risk:** a `GOO_HEADROOM` pre-trade finding. A cold cache gives INFO "not checked" and never blocks capture.
- **UI:**
  - capture form: an inline `HeadroomHint` per delivery row (`leg-${i}-delivery-${d}-headroom`), debounced;
  - Check limits shows the finding;
  - Positions → GoO gets an "Available to sell" sub-tab (`headroom-table`).
- **Tests:**
  - rerouting gives headroom above free supply; no stealing through the sink;
  - exclusion of the trade's own schedules; the joint check;
  - brute force on small graphs: h is feasible and h+1 is not;
  - stale stamp detected without any event;
  - benchmark: warm check p95 < 150 ms, table < 2 s, patch < 200 ms at 100k×5k;
  - k6 profile;
  - Playwright `trading.spec.ts` and a new `portfolio.spec.ts`.
- **Docs:** ADR 0036 final (amends 0007); user guide "Available to sell" (trading and positions); trade tour step.

### M4: Bucket hierarchy and flow-netted portfolio view
- **Product:** `hierarchy()`; `GET /api/v1/products/buckets/hierarchy`; "Contained in" column on the admin buckets screen.
- **Allocation:** `PortfolioQuery.nodes(entity, tier)` returns the node figures plus mark, cheapest-to-deliver cost and give-away if sold as that product.
- **REST:** `GET /api/v1/positions/goo/hierarchy`.
- **UI:** Positions → GoO "Products" sub-tab (`goo-hierarchy`):
  - an indented, expandable table built from flattened paths (AG Grid Community has no tree data);
  - "also under" badges;
  - a naive-net tooltip.
- **Tests:** Hasse edges of the default catalogue; alias merging; netting asymmetry; IT; Playwright.
- **Docs:** ADR 0037 (amends ADR 0016); user guide "Products and netting"; new "portfolio" tour.

### M5: Future supply, with plans reserving against it (D6, D8)
- **Referencedata:**
  - `V8__production_forecast.sql`: `production_forecast` (device, month, expected MWh, P90, source, version, history);
  - `load_factor` (technology × month, a default seed);
  - devices gain `issuance_lag_days` and `issuance_account_id`;
  - `ProductionForecastQuery` resolves the forecast, else capacity × load factor (D8), with the source shown;
  - event `ProductionForecastChanged`;
  - REST and CSV import (`text/csv`); writes need MIDDLE_OFFICE.
- **Allocation:**
  - Contracted and Expected units;
  - `CoverageReport` adds covered-with-contracted and covered-with-expected per obligation;
  - headroom and nodes report three tiers;
  - **the solver includes virtual columns, and plans store virtual lines** (`V6__plan_virtual_lines.sql`, without reservation or instruction);
  - stale marking on receipt, failure or forecast events.
- **UI:**
  - Positions "Future supply" sub-tab (`future-supply`): stacked coverage chart, purchases and forecasts;
  - tier switch (`headroom-tier`);
  - plan detail shows virtual lines;
  - admin device "Forecast" tab (`device-forecast`) with CSV upload and the load-factor fallback shown.
- **Demo:** step "Production forecasts and forward purchases"; one plan with virtual lines.
- **Tests:**
  - forecast CRUD and CSV;
  - fallback to load factor;
  - issued netted against expected;
  - purchase eligibility by implication;
  - a held lot is kept when a purchase covers a later sale;
  - a plan goes STALE on a failed purchase;
  - an unmatched receipt is not double-counted;
  - Playwright.
- **Docs:** ADR 0038 (supply tiers; plans reserving against future); user guide "Production forecasts" and "Future supply".

### M6: Portfolio value, cheapest-to-deliver (management view) and give-away P&L
- **Allocation API:**
  - `PortfolioValueQuery.value(entity, asOf)`: deterministic `MinCostFlow`;
  - `GiveAwayQuery.realised(from, to)`.
- **Valuation:**
  - gains `allocation::api`;
  - `V2__portfolio.sql`:
    - `portfolio_value` (the bridge per run, immutable);
    - `portfolio_explain` with causes TRADE_MTM, INVENTORY_PRICE, INVENTORY_IN_OUT, GIVE_AWAY, REALLOCATION, EXPIRY, UNEXPLAINED;
    - nullable `ctd_price` and `ctd_mtm_base` on `valuation_result`, shown only;
  - the portfolio section is signed off with the run;
  - **official MtM, ledger, EMIR and credit stay on bucket curves (D10)**, and `ExplainComponent` is unchanged.
- **REST:** `GET /api/v1/valuation/runs/{id}/portfolio` and `GET /api/v1/valuation/portfolio-explain`.
- **UI:** run detail "Portfolio" tab with a waterfall (`portfolio-bridge`); a "Portfolio explain" section (`portfolio-explain`).
- **Tests:**
  - the bridge reconciles exactly; determinism;
  - sign-off IT;
  - `MonthEndIT` figures unchanged;
  - the ledger never sees portfolio rows;
  - Playwright.
- **Docs:** ADR 0039 (amends 0019); `docs/domain/valuation.md`; user guide "Portfolio value" and "Give-away"; valuation tour step; optional `ReportCatalogue` entry.

### M7: GoO limits and pre-trade checks
- **Risk:**
  - `GOO_CONCENTRATION`, `GOO_EXPIRY`, `GOO_BASIS`;
  - scope gains `bucketCode`, `productionYear` and `horizonDays`, and rejects a book (flows are not separable by book);
  - `V2__goo_limits.sql`.
- **Measures:**
  - concentration: net per node;
  - expiry: MWh expiring within N days and unused by the flow;
  - basis: generic sales covered by more specific supply.
- **Pre-trade:** findings from `HeadroomQuery` with the trade as virtual demand or supply.
- **Kafka:** `LimitBreached` gains enum values and scope fields (additive); update the contract test.
- **UI:** the limit request form gets the types, a bucket picker, year and horizon; `request-limit` is kept.
- **Demo:** step "GoO limits", with one limit in warning.
- **Tests:** validation, measurement IT, pre-trade findings, breach events, Kafka IT, Playwright `risk.spec.ts`.
- **Docs:** ADR 0040 (amends 0022); `docs/domain/risk.md`; user guide "GoO limits"; risk tour step.

### M8: Close
- The demo seed stays under its time target.
- Screenshots for the new screens via `npm run docs:screenshots`.
- Glossary: give-away, headroom, cheapest-to-deliver, shadow price, firm/expected.
- arc42 §5/§8; architecture plan §2.7 and a new §2.9.
- Roadmap increment 8 table; `docs/plans/increment-8-goo-portfolio.md`.
- k6 baseline if it moves.
- Deck: one portfolio slide.

## Delivered

M0–M7 are built, with ADRs 0035–0040 (see the [roadmap](../roadmap.md)). M8 (this close) is the documentation. What
differs from the plan above:

- **M0:** the cache stamp is made of aggregates (holdings, open obligations, active buckets, date) instead of the per-holder journal sequence, which needed a lateral query per lot.
- **M1:** unpriced supply is valued at zero and flagged (`unpricedMwh`) instead of taking the nearest priced ancestor's mark (D2).
- **M3:**
  - Preparing the per-entity flow is a full problem build (about 22 s at design size). Patching it in place on each change is not built, so a busy book at design size often reads "not checked" before capture.
  - The REST paths are `/api/v1/allocation/headroom` and `/api/v1/allocation/headroom/check`, not under `/positions/goo`. The portfolio view is `GET /api/v1/allocation/portfolio`.
- **M5:**
  - The tiers are HELD, CONTRACTED and EXPECTED (`SupplyTier`), counted cumulatively.
  - A future-supply view endpoint was added, `GET /api/v1/allocation/future-supply`, with coverage per delivery month and tier.
- **M6:**
  - The realised give-away is part of `PortfolioValueQuery` (`realisedGiveAway`); there is no separate `GiveAwayQuery`.
  - There is no `ReportCatalogue` entry.
- **M7:**
  - There is no `productionYear` scope. Concentration covers all vintages together.
  - The pre-trade check only projects purchases (a sale adds nothing to concentration). GoO trades are also told about limits already in breach (`LIMIT_IN_BREACH`).
  - A bug fix: a limit is now measured when it takes effect. `RiskService.evaluate` took the date as a default argument, which, called through the transactional proxy, read the proxy's unset clock, so measuring on activation had always failed. It is now an overload.

## Critical files
- `modules/allocation/src/main/kotlin/.../allocation/domain/Scoring.kt`, `CpSatSolver.kt`, `GreedySolver.kt`, `CoverageAnalyser.kt`
- `modules/allocation/.../application/ProblemBuilder.kt`, `AllocationService.kt`; `api/AllocationModel.kt`
- `modules/product/.../domain/CriteriaImplication.kt`, `application/DefaultBucketCatalogue.kt`, `api/ProductBucketApi.kt`
- `modules/position/.../application/PositionService.kt`
- `modules/valuation/.../application/ValuationService.kt`
- `modules/risk/.../application/RiskService.kt`
- `modules/referencedata/...` (devices and forecasts); `modules/trade/...` (`DeliveryObligationView`)
- `apps/web/src/features/{allocation,positions,trades,valuation,risk,admin}`
- `apps/api/.../demo/DemoScenario.kt`

Reuse:
- OR-Tools, already a dependency: CP-SAT, GLOP `MPSolver`, `MinCostFlow`.
- Existing `TradeApprovalCheck` pre-trade SPI, limit framework, TaskHandler four eyes, and `KpiCard`/`DetailLayout`/`StatusBadge`/`DataGrid fitRows`.

## Risks
- **Eligibility build cost:** compression and in-place patching are mandatory; measure them in M0 before building M3.
- **Plans with virtual lines** must go stale reliably when future supply changes. Covered by event and stamp tests.
- **CP-SAT time budget:** per-tier phase 1 costs time; prune pairs by LP reduced cost while keeping greedy's pairs.
- **Double counting:** an unmatched receipt with an open purchase, intercompany mirror trades, late-recorded issuance. Each gets a test and a warning.
- **Duals are degenerate:** labelled indicative, with the LP gap shown.

## Verification
- Per milestone: `./gradlew build`, `:apps:web:check`, Playwright default + showcase on fresh databases, OpenAPI snapshot, `npm run budget`, axe in the new screens.
- Benchmarks: `-Detrm.benchmark.lots=100000 -Detrm.benchmark.obligations=5000` for the flow build, coverage and headroom latency. k6 smoke with headroom checks.
- Demo walk-through:
  1. A generic sale's what-if shows the give-away and shadow prices.
  2. Capture shows available-to-sell.
  3. Positions shows the product hierarchy and future supply.
  4. A plan holds virtual lines for a forward purchase.
  5. The valuation run's portfolio bridge and explain reconcile.
  6. A GoO concentration limit warns pre-trade.
