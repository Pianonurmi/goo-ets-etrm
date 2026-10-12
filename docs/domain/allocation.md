# Allocation engine

1. **Snapshot**: live `HELD` lots of the entity, minus quantities soft-reserved by other proposals, grouped into lot classes.
2. **Obligations**: open sales (from confirmed trades), intercompany deliveries, disclosure and own-consumption cancellations, each with criteria bound for its delivery date, priority class and optional share constraints (`maxSharePerDevice`, `minShare`).
3. **Eligibility**: class matches criteria, expires after the delivery date, and a feasible transfer route exists to the target registry.
4. **Scoring** (lower is better): mark of the most specific bucket the class fits, from the official GoO vintage curve (or the bucket's specificity as proxy when there is no official price) + transfer fee + scarcity (sum over competing obligations of demand / eligible supply) − urgency bonus for classes expiring within 60 days of delivery.
5. **Greedy solve**: obligations ordered by eligible supply / demand, priority, delivery date, specificity; classes by score. Min-share quotas first, device caps as running limits. Deterministic. Scores depend on the class, the delivery date and the target registry only, so classes are ranked once per (date, registry) and each obligation walks that ranking.
6. **Coverage**: Dinic max-flow augmented per priority tier on the compressed graph (ADR 0036: obligations with identical eligible supply and supply with identical demand merge, exactly) → covered / shortfall per obligation (a merged group's flow goes by delivery date, then id), shortage groups (connected components of exhausted supply), surplus by bucket and production year. The same core answers headroom: how much more a new demand could get without taking coverage from anyone.
7. **Plan**: lines at lot level (earliest expiry first, last lot split), `PROPOSED` with soft reservations (default 48 h), four-eyes `approve` under a per-entity advisory lock → hard reservations (`RESERVED` lots, splits) → registry instructions → confirmations fulfil obligations and record trade deliveries → `CONFIRMED` or `PARTIALLY_FAILED`. Lots that can no longer be reserved mark the plan `STALE`.

Exact solver (`solver: EXACT`, ADR 0031): OR-Tools CP-SAT over the same lot classes and eligibility. It solves three
objectives in order: most MWh covered, least `MinShare` shortfall, then lowest total score. Class supply, demand and
`MaxSharePerDevice` are hard limits. It starts from the greedy solution and is never worse than greedy: the two are compared on coverage, then shortfall, then cost (ADR 0036).
Greedy's result is used instead, with the reason in the plan's `objective.fallbackReason`, when the problem has more
than `etrm.allocation.exact.max-pairs` pairs, when no solution is found within `etrm.allocation.exact.time-limit`
(10 s), or when the native library cannot load. `objective.solver` says which solver ran, and `objective.status`
whether CP-SAT proved the result optimal.

Invariants (property-tested): never over-allocate a class, only eligible classes, never over-fill an obligation, device caps hold, greedy allocation ≤ max-flow coverage, same input → identical plan; the compressed flow agrees with the uncompressed one (totals per tier, minimal cut, shortage groups); headroom h fits and h + 1 does not; ranking per score key gives exactly the per-obligation greedy result.

Scale (`AllocationBenchmarkTest` at 100 000 lots × 5 000 obligations, October 2026): problem build 24 s (eligibility, once per distinct criterion, date and registry), greedy 7 s, coverage 1.7 s, compressed flow 21 groups × 12 atoms solved in 0.1 s, one headroom check about 1 ms.
