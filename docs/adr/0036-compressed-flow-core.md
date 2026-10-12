# ADR 0036: A compressed flow core for coverage, headroom and the portfolio views

- Status: accepted (M0 of increment 8; available to sell and the cache in M3); the supply tiers follow in M5
- Date: 2026-10-12
- Amends: ADR 0007 (coverage as max-flow)

## Context
Increment 8 manages the GoO book as a portfolio. Coverage (ADR 0007), available-to-sell, the product hierarchy and
the portfolio value all ask max-flow questions over the same eligibility graph: lot classes on one side,
obligations on the other.

The graph was built uncompressed, with one node per class and per obligation. At the design size of 100 000 lots
and 5 000 obligations the benchmark has about 100 000 classes and 230 million eligible pairs. Those questions have
to be answered in milliseconds, sometimes while a trade is being captured.

## Decision
- **One flow core** in `allocation.domain.flow`:
  - `FlowGraph`: Dinic with paired reverse edges, an iterative depth-first search, blocked vertices, and trials
    (`mark`/`rollback`, `snapshot`/`restore`);
  - `CompressedFlowGraph`: the graph with interchangeable nodes merged;
  - `FlowState`: a solved flow over it.
- **Compression is exact:**
  - Obligations with the same priority and exactly the same eligible supply become one *demand group*.
  - Supply units with the same tier, reporting key (bucket and year) and exactly the same demand groups become one
    *supply atom*.
  - Merged nodes have identical neighbourhoods, so every max-flow value, every priority tier's share and the
    minimal minimum cut are unchanged. A property test checks this against the uncompressed graph.
  - At design size the 230 million pairs compress to 21 groups and 12 atoms (120 edges). Solving the flow takes
    about 0.1 s.
- **Within a group**, flow goes to obligations by delivery date, then id. The old graph distributed it without
  preference. Totals per priority tier and per shortage group are unchanged.
- **Supply units** are a sealed type with a tier (`HELD`, `CONTRACTED`, `EXPECTED`). Only held classes exist in M0.
- **Headroom** (`FlowState.headroom`):
  - Definition: the most a new demand can receive without taking anything from existing coverage.
  - Method: a max-flow to a trial vertex in the residual graph with the sink blocked. Existing flow may be
    rerouted to free supply, but no path may reach the sink.
  - Where the new demand's eligibility splits an atom, the atom's edge to the trial vertex is capped at its
    eligible quantity. This is exact because units of one atom serve the same groups.
  - The trial is rolled back, leaving the solved flow as it was.
  - A brute-force property test checks that h fits and h + 1 does not.
  - At design size a check takes about 1 ms.
- **Eligibility rows are shared:**
  - Obligations with the same criterion, delivery date and target registry get the same row.
  - Criteria are evaluated once per distinct row (480 instead of 5 000 at design size).
- **Score keys:**
  - A problem may declare that scores depend on the class and a key only: the delivery date (expiry haircut) and
    the target registry (transfer fee).
  - Greedy then ranks all classes once per key and each obligation walks that ranking.
  - Results are identical; a property test compares them with per-obligation scoring.
  - Greedy at design size went from 237 s to 7 s, against a target of 60 s.
- **Joint headroom** (`FlowState.jointHeadroom`, M3): several new demands at once, such as a trade's deliveries,
  which compete for the same lots. One trial vertex per demand is capped at its quantity. Each atom is split by
  which of the new demands its units serve, so no unit is offered twice; per-atom caps alone would count a unit for
  each demand that may take it. A brute-force property test checks that the joint figure fits and one more MWh does
  not. `FlowGraph` adds trial vertices and drops them again at rollback.
- **Binding supply** (`FlowState.headroomDetail`): after the trial, the eligible atoms that no augmenting path
  reaches are exhausted. More of them would mean more headroom; they are reported by bucket and production year.
  The trial also reports the free supply (eligible and unused before rerouting), which headroom is never below.
- **Available to sell** (M3, `HeadroomQuery`):
  - Per entity, the held tier is prepared once: live lots, open obligations less what approved plans have already
    reserved for them (those lots are no longer held, and the obligations stay open until the registry confirms),
    and the solved flow. A check is then a trial on it.
  - The prepared figures carry a **stamp**:
    - the entity's holding stamp (lot count, sum of versions and MWh, in any status);
    - its open obligations (count, versions, fulfilled MWh, newest id);
    - the active buckets;
    - the date.
    Every read compares the stamp with the database, so a change made through any pod is seen on the next read
    without events. The aggregates replace the per-holder journal sequence of M0, which needed a lateral query per
    lot.
  - Trials share one flow, so they run one at a time per entry. The lock covers in-memory work only; eligibility,
    which may look up transfer routes, is settled before it.
  - **Amendments:** a trade's own delivery schedules are left out of existing demand. That is a separate prepared
    entry, kept for the most recent variants per entity.
  - **The table:** a row per bucket and vintage the entity holds, and a column per month-end over six months.
    Bucket membership is evaluated once per class; each cell is a bitset of the vintage's members that outlive the
    date, plus one trial.
  - **Pre-trade check:** allocation implements `TradeApprovalCheck` (`GOO_HEADROOM`) for GoO sales. It reads
    prepared figures only. When none are ready it reports INFO "not checked" and they are prepared in the
    background, so a capture is never held up. A delivery that does not fit, or deliveries that fit one by one but
    not together, give a WARN: the sale may be covered by a purchase or by issuance later.
  - Risk's **Check limits** (`recheck`) now runs every module's pre-trade check, not only its own.
  - REST: `POST /api/v1/allocation/headroom/check` and `GET /api/v1/allocation/headroom`, for TRADER,
    MIDDLE_OFFICE, BACK_OFFICE and RISK within entity scope.
- **CP-SAT floor** (amends ADR 0031): the exact result is compared with greedy on all three objectives in order
  (coverage, minimum-share shortfall, cost). Greedy's result is returned when the exact one is worse on the first
  objective that differs. Before, only coverage was compared.

## Consequences
- Coverage, headroom, the product hierarchy and the portfolio value share one implementation and one benchmark.
- Measured at design size (100 000 lots, 5 000 obligations): a warm check takes 0.5 ms at the 95th percentile
  (target 150 ms), a joint check of 12 deliveries 47 ms, and the table's 192 cells 51 ms (target 2 s).
- The per-entity cache (M3) holds the problem and a few thousand flow nodes. **Preparing it is a full problem
  build**, about 22 s at design size. It happens on the first read after a change, or in the background after a
  pre-trade miss. Patching the prepared figures in place on each change (planned at under 200 ms) is not built yet.
  Until it is, a busy book at design size often reads "not checked" before capture. The stamp will also take
  purchase and forecast versions when the supply tiers arrive (M5).
- Device caps and minimum shares of existing obligations are not in the flow either, so available to sell is an
  upper bound, as coverage is.
- Share constraints remain outside the flow, so coverage and headroom stay upper bounds for a plan.
