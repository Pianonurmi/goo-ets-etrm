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

## Virtual lines

A plan can count on supply that is not held yet: purchases agreed but not yet delivered, and certificates expected
from production devices. The **Plan against future supply** switch next to the solver turns this on (it is on by
default). Such supply appears as **virtual lines**, separate from the lines above: **What if** lists them under the
lines, and an opened plan shows them on its **Virtual lines** tab.

A virtual line is **not reserved and not instructed**. It says which purchase or which device's expected issuance, and
for which month, would serve an obligation, and from when. Once the certificates exist, propose the plan again with
the real lots.

- **Planned** means the plan still counts on what it was made with.
- **Changed** means the purchase or forecast the line counts on has changed since the plan was made, so the line may
  no longer hold. A plan still waiting for approval goes stale instead and is proposed again.
- The **Reliability premium** column is a small extra cost added to supply that is not held, so that held certificates
  win ties. Expected issuance carries more, the further its cautious (P90) figure lies below its expected one.

The tab also shows the coverage of each obligation, with **With purchases** and **With expected issuance** next to
what held lots cover. Each includes the one before it.

## Value and give-away

The solver works in euros. Every certificate has a value, the best mark among all the products it qualifies for,
and every plan states what it does with that value. **What if** shows the figures under the result title; an opened
plan shows them on its **Value** tab, next to **Lines**. Plans made before value-based allocation have no Value tab.

- **Total cost** is what the solver minimised: the value of the certificates used, less the expiry haircut, plus
  transfer fees.
- **Delivered value** is what the delivered certificates are worth.
- **Give-away** is the premium handed over when a certificate worth more than the sale's own product delivers it.
  A Nordic hydro certificate that serves a generic EU renewable sale is the usual case: the buyer pays for the
  generic product and receives a more valuable certificate, and the difference is given away.
- **Expiry haircut** is value lost anyway because a lot lapses soon after delivery. Using such a lot costs less, so
  the solver prefers it.
- **Transfer fees** are the registry fees of moving the certificates.

**Give-away by obligation** lists the sales with their product, the sale mark, the MWh, the average mark of what was
delivered, the delivered value and the give-away, largest give-away first. On the lines, the cost column is in
EUR per MWh, with the give-away per MWh beside it. A plan made before this change shows a plain **Score** instead.

A line marked **Unpriced** delivers a certificate none of whose products has an official price. It is valued at
zero, so a warning above the table gives the unpriced MWh, and the cost and give-away figures understate them. Price
the product in market data and run **What if** again.

> **Try it in the demo:** the demo has two executed plans and one waiting for approval. Its **Value** tab shows a
> generic EU renewable sale delivered with Nordic hydro, and what that gives away. Approve it as *Max Middle*.

## Shadow prices

A plan also says what its choices cost at the margin. **What if** holds this in a collapsed **Shadow prices**
section under the result title; an opened plan shows it on its **Shadow prices** tab, next to **Lines** and
**Value**. When the **Value** tab is open, the give-away table gains a **Marginal cost** column with the same figure.

- **Marginal cost** of a sale is what one more MWh delivered to it would cost, including the rerouting of other sales
  that the extra MWh causes. Sales are listed with the highest marginal cost first. A sale marked **Short** is not
  fully covered, so its extra MWh may not be available at all.
- **Scarcity rent** of a supply, a product bucket and production year, is how much the plan's cost would fall with
  one more MWh of it. The table gives the supply held, the MWh used, the average rent and the highest rent, highest
  first.
- **LP minimum cost** is the cheapest cost of covering the same sales, **Plan cost** is what this plan costs, and
  the **Gap** between them is what better lot choices could still save. It is zero for an optimal plan.

A sale served only with cheap supply can still have a high marginal cost. One more MWh for it has to come from
somewhere: it takes cheap supply from another sale, and that sale then buys dearer certificates to make up for it.
The marginal cost follows that whole chain, not the price of the lots in the sale's own lines.

The figures are indicative. They come from a linear program computed in floating point, they are not unique when
several equally good plans exist, and they are never booked. When the prices could not be computed, the tab states
the reason instead.
