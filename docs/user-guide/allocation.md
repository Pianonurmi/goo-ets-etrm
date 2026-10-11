# Allocation

Allocation decides which lots deliver which obligations: sales, intercompany deliveries, and cancellations for
disclosure or own consumption.

![The allocation workbench](../images/allocation-workbench.png)

## Obligations and coverage

The **Allocation workbench** opens with four cards: **Demand**, **Covered**, **Shortfall** (all in MWh) and the
number of **Open obligations**, with the active plan if there is one. Below them, **Coverage by obligation** charts
the covered and short MWh by target account and delivery date, and **Plans** lists the plans of the entity. The
**Open obligations** table lists each obligation with its **coverage**: how much of it the live inventory can
satisfy, computed as a maximum flow over eligible lots.

- Obligations from trades open by themselves. **Open obligation** (in the page header) adds a manual one (e.g. own
  consumption). With no obligations, the table shows an empty state.
- Click an obligation to see it: its criteria, cancellation details and **Share constraints** (a maximum share per
  device, minimum shares by criterion), which **Edit constraints** changes while the obligation is open.
  **Close obligation** needs a reason.

## Plans

1. **What if** shows the best assignment without storing anything. Choose the solver, **Greedy** or **Exact**, with
   the switch next to the button.
2. **Propose plan** stores a plan; it waits for approval by middle or back office, never by the proposer.
3. An approved plan reserves the lots and creates the registry instructions; confirmed instructions fulfil the
   obligations.

Click a plan to open it. The page shows the plan's key facts (allocated and short MWh, solver, who approved it) and
its lines by obligation, with a **Lifecycle** timeline beside them: proposed, approved, executing in the registry,
executed. **Approval task** opens its inbox task, and **Cancel plan** (with a reason) releases the reservation of a
plan that is still proposed or approved.

> **Try it in the demo:** the demo has two executed plans and one waiting for approval. Approve it as *Max Middle*.
