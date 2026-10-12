# ADR 0031: Exact allocation with CP-SAT, never worse than greedy

- Status: accepted
- Date: 2026-10-11
- Amended by: ADR 0035 (coverage per priority tier; cost in EUR instead of a score)

## Context
The greedy solver (ADR 0007) works through obligations, most constrained first, and gives each the cheapest
eligible lots. It is fast and deterministic. But a cheap pick early can leave a later obligation short even when a
full assignment exists, and it can cost more than needed. Allocation plans already accept `solver: EXACT`, which
until now fell back to greedy. OR-Tools has been in the version catalogue since increment 1. The risk was its
native library: OR-Tools ships glibc binaries for Linux x86-64 and aarch64, macOS and Windows.

## Decision
- **Model:** `CpSatSolver` models the problem as an integer program over lot classes, not lots. Each eligible
  (class, obligation) pair is one variable, the MWh assigned. Hard limits:
  - class supply and obligation demand;
  - `MaxSharePerDevice`, per device and obligation;
  - `MinShare`, as a quota with a shortfall variable.
- **Objectives:** three, solved in order. Each optimum becomes a constraint for the next phase:
  1. allocate as many MWh as possible (since ADR 0035, one priority tier at a time, highest first);
  2. miss as little of the minimum shares as possible;
  3. lowest total score, the same per-MWh score that greedy sorts by, scaled to 1/10 000 (since ADR 0035 the
     cost in EUR/MWh).
- **Never worse than greedy:**
  - The greedy solution is the hint for the first phase.
  - Greedy's result is returned, with the reason in the plan's objective (`fallbackReason`), when:
    - the problem is larger than `etrm.allocation.exact.max-pairs` (200 000 pairs);
    - the native library does not load;
    - no solution is found in time;
    - the result covers less than greedy. Since ADR 0036 the comparison runs on all three objectives in order:
      greedy is returned when the exact result is worse on the first objective that differs.
- **Time limit:** `etrm.allocation.exact.time-limit` (10 s) covers all three phases. `workers` (4) sets the
  search threads. The plan records the solver that actually ran, the solver status (`OPTIMAL` or `FEASIBLE`)
  and the wall time.
- **Runtime image:** it moves from Alpine (musl) to Ubuntu (`eclipse-temurin:25-jre-noble`), so the native
  library loads in production. Only the Linux and macOS arm64 natives are packaged. The Windows and macOS x86-64
  natives are excluded to keep the jar small.
- **UI:** the allocation workbench offers *Greedy* and *Exact* for what-if and propose, and shows which solver ran.

## Consequences
- With a time limit and several workers, two runs on the same snapshot can return different plans of equal or
  near-equal quality. Plans are reviewed and approved (four eyes) like any other, and the objective records the
  status.
- Within the size limit, an exact what-if can take up to the time limit, still within the API's timeouts.
  Larger books use greedy until the model is decomposed, for example per registry or delivery month.
- Disaggregation from classes to lots is shared with greedy: earliest expiry first.
