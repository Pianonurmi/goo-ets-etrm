# ADR 0036: A compressed flow core for coverage, headroom and the portfolio views

- Status: accepted (M0 of increment 8); the cache and the supply tiers follow in M3 and M5
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
- **CP-SAT floor** (amends ADR 0031): the exact result is compared with greedy on all three objectives in order
  (coverage, minimum-share shortfall, cost). Greedy's result is returned when the exact one is worse on the first
  objective that differs. Before, only coverage was compared.

## Consequences
- Coverage, headroom, the product hierarchy and the portfolio value share one implementation and one benchmark.
- A per-entity cache of the solved flow (M3) only has to hold a few thousand nodes. Its stamp combines:
  - the entity's last lot event, together with its held-lot count and quantity (a lot that leaves the entity does
    not raise the entity's last event);
  - obligation, reservation and purchase versions;
  - the forecast version;
  - the bucket catalogue.
- Share constraints remain outside the flow, so coverage and headroom stay upper bounds for a plan.
