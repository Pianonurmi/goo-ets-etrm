# Increment 10: portfolio follow-ups

**Status: in progress.** Release 0.10.0. Increment 9 (housekeeping) is released as 0.9.0.

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
| M1 | Incremental prepare (ADR 0043). A stale entry is patched instead of rebuilt. Inventory gives the lots of a holder changed after a journal position (`LotQuery.changedSince`). Allocation gives the open obligations changed after a version stamp. The patch keeps class indexes stable: an emptied class stays as a tombstone, a new class key is appended and gets its eligibility for the cached keys only, and a new eligibility key evaluates every class once. Then the compressed graph and the flow are solved again (about 0.1 s), and the memos (eligibility, table) are cleared. The patched entry is checked against the database stamp. On a mismatch, too many tombstones, a catalogue change or a new day, a full build runs, so a missed change can only cost time. Risk's GoO measures and the pre-trade check read the patched entry. Targets at design size: under 200 ms for one changed lot or obligation, under 1 s for 1 000 changes. A property test compares patched and fully built entries over random change sequences: coverage, residuals, headroom and the table. |
| M2 | GoO limits by vintage, and sale checks (amends ADR 0040). `LimitScope.productionYear` for concentration, expiry and basis, held in the existing JSONB scope (no migration; additive in the `LimitBreached` JSON). Measures filter classes and obligations by year, and an EUR concentration uses that vintage's mark. Pre-trade: purchases match bucket and year through allocation (the year comes from the obligation rules in `VintageYears`, so risk gains no dependency on product criteria). A GoO sale gets a projected `GOO_BASIS` finding: allocation takes the trade's deliveries as virtual demand on the prepared flow and returns the projected give-away of the cheapest eligible free supply. Over the limit needs risk approval; at warning it is a warning. While figures are being prepared the check is INFO "not checked" and never blocks. Web: a vintage picker on the limit form and the year in the scope label. The demo adds a vintage-scoped limit. |
| M3 | Give-away report. `GOO_GIVE_AWAY` in the `ReportCatalogue`: per sale line of the plans realised in a period, with plan, trade, delivery date, sale bucket, vintage, MWh, delivered value, sale mark and give-away, plus a total row. Roles are RISK, MIDDLE_OFFICE, ACCOUNTANT and AUDITOR; allocation's read guard admits AUDITOR for this query. Logged with its content hash like every report. Reporting gains `allocation :: api`. `realisedGiveAway` gains the per-obligation lines, and valuation's explain keeps its totals. |
| M4 | Side constraints in the flow (amends ADR 0007 and 0036). It starts with a spike gated on numbers at design size. **Device caps:** obligations with a cap form their own groups, and their edges come from atoms split by device, each capped at the obligation's share. Compression stays exact for the uncapped majority. **Minimum shares** are lower bounds, which a priority max-flow cannot hold exactly; available to sell checks them as a post-condition, and the finding names the quota a sale would put at risk. If the device split grows the graph past the check budget (p95 above 150 ms), caps stay outside the flow and the decision is recorded. Either way the UI text says exactly which bound the figure is. |
| M5 | Close. ADR 0043 and amendments; architecture plan ("Status after increment 10"); arc42; the domain and user guides; glossary; screenshots for changed screens; k6 baseline if it moves; the deck's roadmap and "next" slides; `CHANGELOG.md`; release 0.10.0 with the Release workflow. |

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
