# Increment 10: portfolio follow-ups

**Status: delivered** (M0–M5, release 0.10.0). What differs from the plan is under [Delivered](#delivered); the decisions are in ADR 0043 and the amendments to ADRs 0007, 0036 and 0040.

## Context

Increment 8 made the GoO book a portfolio (ADRs 0035–0040) and listed what it left open
([architecture plan](../architecture-plan.md), "Status after increment 8"). This increment closes those points, so the
portfolio figures are ready for a production-size book:

| Open after increment 8 | Effect today |
|---|---|
| Preparing the per-entity flow is a full problem build, about 22 s at design size (100 000 lots, 5 000 obligations); patching in place is not built | A busy book reads "Available to sell not checked" before capture; the available-to-sell table, the portfolio view and the GoO limit measures block on a rebuild, and risk re-measures inside event transactions |
| No production-year scope on GoO limits; the pre-trade check projects purchases only | A desk cannot limit one vintage, and a sale that hands over valuable certificates is not checked against the basis limit before capture |
| No give-away report | Give-away is visible on the plan and the valuation run, but there is no logged, exportable report |
| Device caps and minimum shares are outside the flow | Available to sell and coverage are upper bounds; a sale that fits can still leave the allocation short |

**Unchanged:**
- Unpriced supply stays at zero and flagged. A borrowed price would hide the gap in the curves.
- Official MtM stays on bucket curves (ADR 0039).

## Findings that shape the plan

- **The rebuild has no breakdown.** `AllocationBenchmarkTest` builds from in-memory fixtures and never runs
  `ProblemBuilder`, so the 22 s is not reproduced by any committed test.
- **The flow builds the allocation scoring for nothing.** `ProblemBuilder.build` computes `scoring.scarcityIndex`,
  O(eligible pairs): about 230 million `BigDecimal` merges at design size. No prepared-flow consumer reads
  `problem.score`; headroom, portfolio, future supply and GoO risk use `bestBucketCode` only.
- **Nothing coordinates builds.**
  - `PortfolioFlowCache.prepare` has no single-flight guard, so concurrent readers each build.
  - Risk's GoO measures call it from inside event-listener transactions.
- **The incremental data already exists.**
  - Every lot row carries `updated_seq`, the journal position of its last change.
  - Obligations carry a version.
  - `FlowGraph.raiseCapacity` and the trial machinery exist.
  - Structures are indexed by class position, so a patch must keep indexes stable.
- **The stamp of aggregates stays the ground truth across pods.** Events are pod-local, so a patch can be a fast
  path but never the only path.

## Milestones

| Milestone | Content |
|---|---|
| M0 | Measure, then take the cheap wins. A `PortfolioPrepareBenchmark` runs `ProblemBuilder` and `CoverageAnalyser` at design size with in-memory ports and reports each phase. Prepare logs its phases at debug level. A flow-only build skips the scarcity index, and the scoring is built lazily when a solver asks. `prepare` gets a single-flight guard per key, so a second reader waits for the build already running. The plan records the before and after figures. |
| M1 | Incremental prepare (ADR 0043). **Profiles:** held classes with the same criteria matches (one bit per distinct bound criterion), registry and expiry rank among the delivery dates are eligible for the same demands; compression refines profiles instead of classes. **Patch:** a stale entry diffs the entity's live lot versions (a narrow `(id, version)` query, exact even when transactions commit out of order) and the open obligations against what it holds, fetches only the changed lots, updates classes (an emptied class stays as a tombstone so indexes hold; a new class key is appended and gets its bits in the cached rows), adds rows for new eligibility keys, and solves the flow again. The previous entry is never mutated; readers keep it until the new one is published. A full build runs on a new day, a catalogue change, a route change, too many tombstones or more than a set share of changed lots. Targets at design size: under 300 ms for one changed lot or obligation, and the full build under 3 s. A property test compares patched and fully built entries over random change sequences: coverage, residuals, headroom and the table. |
| M2 | GoO limits by vintage, and sale checks (amends ADR 0040). `LimitScope.productionYear` for concentration, expiry and basis, held in the existing JSONB scope (no migration; additive in the `LimitBreached` JSON). Measures filter classes and obligations by year, and an EUR concentration uses that vintage's mark. Pre-trade: purchases match bucket and year through allocation (the year comes from the obligation rules in `VintageYears`, so risk gains no dependency on product criteria). A GoO sale gets a projected `GOO_BASIS` finding: allocation takes the trade's deliveries as virtual demand on the prepared flow and returns the projected give-away of the cheapest eligible free supply. Over the limit needs risk approval; at warning it is a warning. While figures are being prepared the check is INFO "not checked" and never blocks. Web: a vintage picker on the limit form and the year in the scope label. The demo adds a vintage-scoped limit. |
| M3 | Give-away report. `GOO_GIVE_AWAY` in the `ReportCatalogue`: per sale line of the plans realised in a period, with plan, trade, delivery date, sale bucket, vintage, MWh, delivered value, sale mark and give-away. Roles are RISK, MIDDLE_OFFICE, ACCOUNTANT and AUDITOR; allocation's read guard admits AUDITOR for this query. Logged with its content hash like every report. Reporting gains `allocation :: api`. `realisedGiveAway` gains the per-obligation lines, and valuation's explain keeps its totals. |
| M4 | Side constraints in the flow, behind a spike gated on design-size numbers; if it fails, caps stay outside and the decision is recorded. Outcome: the gate failed (see M4 below), and the check on a realistic book is made fast instead, by routing nested eligibility through chains. |
| M5 | Close. ADR 0043 and amendments; architecture plan ("Status after increment 10"); arc42; the domain and user guides; glossary; screenshots for changed screens; k6 baseline if it moves; the deck's roadmap and "next" slides; `CHANGELOG.md`; release 0.10.0 with the Release workflow. |

## Delivered

### M0: measured, and the full build made cheap
`PortfolioPrepareBenchmarkTest` runs `ProblemBuilder` and `CoverageAnalyser` the way `PortfolioFlowCache` prepares an
entity, with in-memory ports (no database read). The book is realistic: expiry dates spread over a year, three target
registries, one registry without outgoing routes, and future supply. At design size (100 000 lots, 5 000 obligations):

| Phase | Before | After | Change |
|---|---|---|---|
| Eligibility rows | 8.2 s | 1.3 s | Criteria evaluated once per distinct bound criterion; expiry and routes as masks |
| Scarcity index | 14.6 s | 0 s for the flow, 1.2 s for a solver | Computed only when a solver scores; summed per distinct row in exact millionths |
| Compression | 4.2 s | 1.2 s | Supply atoms by partition refinement instead of a list of groups per unit |
| Max-flow | 0.1 s | 0.1 s | |
| **Prepared** | about 28 s | **about 3.5 s** | |

- **The old "0.1 s" held only for a uniform book.** The uniform benchmark has 21 groups and 12 atoms. A realistic
  book has 2 249 groups, 968 atoms and 344 000 edges, so compression, not the max-flow, is the cost after eligibility.
- **Solvers also gain:** coverage analysis 2.3 s → 0.7 s, by sharing masks and supply sums per eligibility row.
- **Single flight:** `prepare` builds once per key, and a second reader waits for that build without holding a
  connection. A background warm-up joins the same build.
- **Phase logging:** the cache logs read, problem and flow time with the graph size at debug level.
- **Consequence for M1:** a full build is now seconds, not tens of seconds, so patching aims at the database read and
  at sub-second answers after a change, measured against this baseline.

### M1: the held figures patched in place (ADR 0043)
- **One code path.** `ProblemIndex` holds classes, eligibility parts and obligations; a full build loads it and
  publishes once, and the portfolio cache keeps it and patches it. A patched problem is therefore the problem a full
  build makes.
- **Profiles.** Held classes with the same criteria matches, registry and expiry rank compress as one. At design size
  1 468 profiles stand for 100 000 classes, and building the flow drops from 2.0 s to 0.7 s. The benchmark checks
  that the graph is the one built class by class.
- **Patch.** `LotQuery.liveVersions` (id and version of every live lot) and `lotsById` find and read the changed
  lots. Classes keep their index, emptied ones stay as tombstones, and supply enters the flow in class-key order, so
  the graph does not depend on when a class was added.
- **Full build instead:**
  - a new day;
  - a catalogue change;
  - an index older than `full-build-after` (1 h);
  - more than a tenth of the lots changed;
  - more than a fifth of the classes emptied.
- **Amendments** publish from the entity's index without their own obligations.
- **Pre-trade checks** patch at once instead of answering "not checked".
- **Figures at design size:** a full build about 3 s, and a patch about 0.3 s whether one lot changed or a thousand.
- **Tests:**
  - `IncrementalFlowTest` compares patched and full builds over 40 random sequences of 30 lot and obligation changes:
    classes, eligibility, groups and atoms, coverage, residuals and headroom. A planted bug fails it at the first
    step.
  - `AllocationServiceIT` checks the patch against a fresh cache on the database.

### M2: GoO limits by vintage, and sales against the basis limit (ADR 0040 amended)
- **Vintage scope.** `LimitScope.productionYear` on any GoO limit:
  - concentration and expiry count only that year's certificates;
  - an EUR concentration uses that vintage's mark;
  - basis counts only the sales marked at that vintage, from the new `giveAwayMwh` per sale.

  It is stored in the JSON scope column (no migration) and is additive in `LimitBreached` and in the OpenAPI
  document.
- **Purchases.** A limit with a vintage counts only the purchase's deliveries marked at it. Allocation reads the
  vintage from the criteria (`GooRiskQuery.vintages`).
- **Sales.** `GooRiskQuery.projectSale` serves a GoO sale from the free certificates, cheapest first, and returns the
  premium per delivery. Risk adds it to each basis limit, giving `GOO_BASIS`: risk approval over the limit, a warning
  past the warning level, and INFO "not checked" while the figures are prepared.
- **Web and demo.**
  - The limit form has a **Vintage** field for GoO limits, and the scope column shows *vintage 2025*.
  - The demo adds an expiry limit on last year's vintage.
- **Tests:**
  - `RiskServiceIT` covers the scope rules, a vintage measure, the event JSON, purchases by vintage, and sale findings
    including "not checked".
  - `AllocationServiceIT` checks concentration by vintage and a projected sale: wind first, then hydro with 1.50
    EUR/MWh of premium.
  - The risk journey requests a vintage-scoped limit.

### M3: the give-away report
- **`GOO_GIVE_AWAY`** in the report catalogue lists, for a period, every sale of the plans approved in it, with plan,
  trade, delivery date, sale bucket, vintage, MWh, delivered value, sale mark and give-away. The *to* date is
  included. Like every report it is logged with its content hash.
- **Readers:** RISK, MIDDLE_OFFICE, ACCOUNTANT and AUDITOR. Allocation's `giveAwayLines` admits auditors.
- **Wiring:** reporting gains `allocation :: api`.
- **No total row:** the grid and the spreadsheet sum the column.
- **Plan-time figures:** a line that later fails at the registry stays listed, which the report's description says.

### M4: device caps fail their gate; the check gets fast instead
- **The spike.** Device caps in the flow, exact (a node per capped obligation and device, supply split by device where
  a capped obligation may take it), passed a property test against an uncompressed reference. At design size with
  one sale in twenty capped at 50 %, building the flow went from 0.7 s to 32 s: a generic capped sale splits nearly
  all supply by device (800 devices). The gate fails, the code is not kept, and caps and minimum shares stay outside
  the flow. Available to sell and coverage remain upper bounds, as the screens say (ADR 0007).
- **What the realistic benchmark showed instead:**
  - A warm available-to-sell check takes 148 ms at the 95th percentile, not the 0.5 ms measured on the uniform book.
  - The augmenting search over 344 000 supply-to-sale edges is 125 ms of it.
  - The 192-cell table would take about 30 s.
- **The fix: nested eligibility as chains.** Sale groups whose eligible supply is nested (the same criterion and
  target, later delivery dates) form chains. Supply enters each chain once, at the smallest set that holds it, and
  flows to the larger ones; each set leads to its groups. Every sale is reachable from exactly the same supply. With
  only unbounded edges in between, the minimum cuts, and so every flow figure, are unchanged.
- **Result at design size:**

  | | Before | After |
  |---|---|---|
  | Edges | 344 000 | 19 500 |
  | Check, 95th percentile | 148 ms | 30 ms |
  | Residual copy per trial | 5 ms | 0.1 ms |
  | 192-cell table | about 30 s | 4 s |

  The existing property tests pass unchanged: compressed against uncompressed (coverage per tier, minimum cut,
  shortage groups), headroom brute force, and patched against full.

## Conventions that apply

- **Module boundaries.** Allocation must not depend on risk, valuation or reporting. Risk reaches allocation through
  `GooRiskQuery` (`allocation :: api`), resolved lazily, with savepoints, and tolerates a missing OR-Tools library.
- **Listeners** are idempotent and never fail the publishing transaction.
- **No lock across blocking I/O:** the single-flight guard waits on a future, not on a held lock, and
  `ConcurrencyIT` stays green with two carrier threads.
- **REST changes:** update the OpenAPI snapshot, regenerate the web types, and keep `api-only.json` in step.
- **Benchmarks:** they run at the default size in the build and at design size with
  `-Detrm.benchmark.lots=100000 -Detrm.benchmark.obligations=5000`.

## Verification

- Each milestone has unit and integration tests, then the full build and the Playwright journeys before `main` moves.
- **M0 and M1:** benchmark figures at design size go into this plan and ADR 0043.
- **M1:** the property test of patched against full build. `AllocationServiceIT` gains a stamp-mismatch case and a
  catalogue-change case.
- **M2:** `RiskServiceIT` covers vintage scope, the sale basis projection and the `LimitBreached` JSON; the e2e
  `risk.spec.ts` covers the limit form with a vintage.
- **M3:** `ReportingIT` runs the report as CSV and as JSON and checks the export log.
- **M4:** a brute-force comparison on small problems (an exact solver against the flow with caps), and the check
  budget at design size.

## Critical files

- **Allocation:**
  - `modules/allocation/.../application/PortfolioFlowCache.kt`, `ProblemBuilder.kt`, `HeadroomService.kt`,
    `GooRiskService.kt`, `PortfolioValueService.kt`;
  - `domain/CoverageAnalyser.kt`, `domain/flow/CompressedFlowGraph.kt`, `domain/flow/FlowGraph.kt`;
    `domain/Scoring.kt`, `domain/LotClassifier.kt`;
  - `api/GooRiskApi.kt`, `api/PortfolioValueApi.kt`.
- **Inventory:** `api/InventoryApi.kt` (`changedSince`) and `adapter/jooq/JooqLotRepository.kt`.
- **Risk:** `api/RiskApi.kt` (`LimitScope`) and `application/RiskService.kt` (`gooMeasure`, `preTradeFindings`).
- **Reporting:** `application/ReportCatalogue.kt` and `ReportingModule.kt`.
- **Web:** `apps/web/src/features/risk/RequestLimitModal.tsx`, `features/risk/model/limits.ts`, `locales/en.json`.
- **Demo:** `apps/api/.../demo/DemoCompliance.kt`.
