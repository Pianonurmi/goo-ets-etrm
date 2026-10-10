# Allocation engine

1. **Snapshot**: live `HELD` lots of the entity, minus quantities soft-reserved by other proposals, grouped into lot classes.
2. **Obligations**: open sales (from confirmed trades), intercompany deliveries, disclosure and own-consumption cancellations, each with criteria bound for its delivery date, priority class and optional share constraints (`maxSharePerDevice`, `minShare`).
3. **Eligibility**: class matches criteria, expires after the delivery date, and a feasible transfer route exists to the target registry.
4. **Scoring** (lower is better): mark of the most specific bucket the class fits (or the bucket's specificity as proxy) + transfer fee + scarcity (sum over competing obligations of demand / eligible supply) − urgency bonus for classes expiring within 60 days of delivery.
5. **Greedy solve**: obligations ordered by eligible supply / demand, priority, delivery date, specificity; classes by score. Min-share quotas first, device caps as running limits. Deterministic.
6. **Coverage**: Dinic max-flow augmented per priority tier → covered / shortfall per obligation, shortage groups (connected components of exhausted supply), surplus by bucket and production year.
7. **Plan**: lines at lot level (earliest expiry first, last lot split), `PROPOSED` with soft reservations (default 48 h), four-eyes `approve` under a per-entity advisory lock → hard reservations (`RESERVED` lots, splits) → registry instructions → confirmations fulfil obligations and record trade deliveries → `CONFIRMED` or `PARTIALLY_FAILED`. Lots that can no longer be reserved mark the plan `STALE`.

Exact solver: the `Solver` port allows a CP-SAT adapter; the `EXACT` request currently falls back to greedy and records it.

Invariants (property-tested): never over-allocate a class, only eligible classes, never over-fill an obligation, device caps hold, greedy allocation ≤ max-flow coverage, same input → identical plan.
