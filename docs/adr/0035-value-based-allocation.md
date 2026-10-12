# ADR 0035: Value-based allocation in EUR, with the give-away reported

- Status: accepted (M1 of increment 8; shadow prices added in M2)
- Date: 2026-10-12
- Amends: ADR 0031 (exact allocation with CP-SAT)

## Context
A GoO book holds certificates of many kinds: Nordic hydro, EU wind, labelled, unsupported. Sales can be specific
("Nordic hydro 2026") or generic ("any EEA renewable 2026"). A specific certificate can deliver a generic sale, but
a generic one cannot deliver a specific sale. Delivering a generic sale with a valuable certificate hands the
difference to the buyer for free.

Before this change, the allocation score added three things with different units:
- the mark of the class's most specific bucket, in EUR/MWh, or the bucket's specificity when it had no price;
- a dimensionless scarcity ratio;
- an urgency bonus.

Two lots could therefore be ordered by the scarcity ratio rather than by money. Plans showed a "score" that nobody
could reconcile with the curves. The CP-SAT solver maximised total coverage in its first phase, ignoring the
priority tiers that the coverage report applies.

## Decision
- **Value of a lot:** the best price it qualifies for, i.e. the highest mark over every product bucket the lot
  satisfies. A Nordic wind lot is worth the higher of NORDIC_WIND, EU_WIND, NORDIC_RENEWABLE, EU_RENEWABLE and
  ANY_GOO. This holds when curves are inverted: in the demo, EU_WIND is marked above NORDIC_WIND. Marks come from
  the latest official vintage curve (`GooMarks`) in EUR.
- **Expiry haircut:** a lot that expires within `etrm.allocation.value.liquidity-horizon-days` (60) of the delivery
  date keeps only `days / horizon` of its value, because it lapses unless delivered.
- **Cost per MWh** = value − haircut + transfer fee to the target registry. Allocation minimises it, and it is the
  only quantity both solvers optimise.
- **Give-away per MWh** = value of the lot − the mark of the sale's own bucket at its vintage year (the delivery
  year when the obligation names none). It is reported and does not change the cost, because the sale price is the
  same whichever lot delivers it. Minimising the value spent minimises the give-away.
- **Scarcity** (demand ÷ eligible supply, summed over the obligations that compete for a class) is only a
  tie-break between classes of equal cost. LP shadow prices replace it in M2.
- **Unpriced supply:** a lot with no priced bucket is valued at zero and flagged `priced = false`. The plan reports
  those MWh as `unpricedMwh`, so a zero cost is never mistaken for a real one.
- **Cost basis** stays out of the objective. It is sunk: what a certificate cost does not change what it is worth
  now.
- **Exact solver:**
  - Phase 1 maximises coverage one priority tier at a time, from the highest down. Each tier's optimum is fixed
    before the next tier is solved.
  - The floor against greedy compares coverage per tier, then the minimum-share shortfall, then the cost.
  - The cost phase minimises cost in 1/10 000 EUR per MWh.
- **API:**
  - `ScoreBreakdown` keeps its fields with EUR meanings: `opportunityCost` is the value, `urgencyBonus` the
    haircut, `transferCost` the fee, `total` the cost.
  - It adds `saleMarkEur`, `giveAwayPerMwh`, `unit` (`EUR_PER_MWH`; old plans read `SCORE`) and `priced`.
  - Plans and what-ifs carry a `PlanValue`: total cost, delivered value, expiry haircut, transfer fees, give-away,
    unpriced MWh, and figures per obligation sorted by give-away.
  - The value is stored with the plan (`value_summary`, `V4__plan_value.sql`). Plans scored before this change
    have no value.
- **Shadow prices (M2):** after the plan, a linear program is solved with GLOP:
  - It delivers each obligation's planned coverage at the lowest cost, under the same supply, device caps and
    minimum shares (no lower than the plan achieved).
  - The plan's own solution is feasible, so the LP always has one.
  - Its duals give the **marginal cost** per obligation: what one more MWh delivered to it would cost, rerouting
    included. A sale served only by cheap supply can still have a high marginal cost, because one more MWh for it
    takes cheap supply from another sale, which then buys dearer.
  - They also give the **scarcity rent** per lot class: how much the cost would fall with one more MWh of it.
    Rents are reported per bucket and production year (quantity-weighted average and maximum).
  - The LP's cost is a bound: the **gap** = plan cost − LP cost is what better lot choices could still save for
    the same coverage. It is zero for an optimal exact plan, and greedy plans show it.
  - The duals are computed in floating point and converted to decimals at the boundary. They are labelled
    *indicative*: a degenerate LP has many dual solutions, of which GLOP returns one. They are never booked.
  - `PlanPricing` on plans and what-ifs, stored with the plan (`pricing`, `V5__plan_pricing.sql`), with a status:
    `OPTIMAL`; `SKIPPED` with a reason (no native library, nothing allocated, above
    `etrm.allocation.pricing.max-pairs`, 300 000 pairs); or `FAILED` (time limit
    `etrm.allocation.pricing.time-limit`, 5 s).
  - Scarcity stays the tie-break in the cost. The rents explain a plan; they do not feed back into it.

## Consequences
- **Plan cost:** cost and give-away are in money, and the plan's value reconciles with its lines (MWh × per-MWh
  figures). The UI shows them on the what-if and on the plan.
- **Changed lot choices:**
  - A generic sale now takes the cheapest qualifying certificate, wherever it sits in the hierarchy.
  - Specific supply stays for the specific sales that need it, unless it is the cheapest anyway. In that case the
    give-away shows it.
  - Lots bought cheaply are no longer preferred for their low cost basis.
- **Depends on curves:**
  - Without official curves, every lot values at zero and allocation falls back to the scarcity tie-break and the
    class order. `unpricedMwh` makes that visible.
  - Generic buckets need curves of their own for their sales to have a give-away. The demo marks EU_RENEWABLE and
    ANY_GOO below their children.
- **Shadow prices at scale:** they are computed on lot classes, not on the compressed graph (ADR 0036), because
  classes in one atom can differ in cost. A book above the size limit gets none, with the reason shown.
- **Solver time:** solving per tier costs CP-SAT one extra solve per priority class, within the same time limit.
  When time runs out, the tiers not yet solved keep the coverage of the best solution found.
