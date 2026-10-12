# ADR 0039: Portfolio value and cheapest to deliver, as a management view next to the valuation

- Status: accepted (M6 of increment 8)
- Date: 2026-10-12
- Amends: ADR 0019 (valuation runs and P&L explain)

## Context
Valuation marks each trade against its product's bucket curve. A GoO book is more than its trades, though.

- **The book holds certificates.** They are worth something, and what they are worth depends on the best product
  each qualifies for.
- **Sales are served from that inventory.** When a Nordic hydro certificate delivers a generic EU renewable sale,
  the book hands over the premium between the two marks: the give-away of ADR 0035.

None of this shows in the official MtM, and it should not. Accounting, EMIR valuation reports and credit exposure
need one price per product, independent of which certificate a desk happens to hold. Management still needs to see
what the book is worth as a whole, and what moved it.

## Decision
- **Valuation-grade allocation** (`allocation.domain.PortfolioValuer`):
  - **Problem:** the entity's held certificates against its open obligations.
  - **Solver:** a min-cost max-flow (OR-Tools `SimpleMinCostFlow`) with costs in integer cents.
  - **Objective:** most coverage first, higher priority first (a bonus wider than any cost), then the least cost of
    ADR 0035 (value after the expiry haircut plus the transfer fee).
  - **Constraints:** no time limit and no side constraints (device caps, minimum shares), so the same book always
    gives the same figures.
  - **Nodes:** obligations that share their eligibility, score key and priority form one demand node. Flow into a
    node goes to its obligations by delivery date, then id, cheapest classes first.
- **Portfolio value** (`PortfolioValueQuery.value(entity, asOf)`):
  - **Inventory value:** each held certificate at the best price it qualifies for on `asOf` (D1).
  - **Costs on the allocation:** the give-away, the transfer fees and the expiry haircut.
    - **Negative give-away:** a sale delivered with an older vintage that still qualifies but is marked lower than the
      sale's own product is a gain. In the bridge it nets against give-away elsewhere; the basis limit
      (ADR 0040) counts only what is handed over.
  - **Detail:** the value by bucket and production year, and per obligation the cheapest to deliver (the average
    value per MWh of the certificates that deliver it) against the sale's own mark.
  - **Dates:** holdings and obligations are the current ones; prices are those of `asOf`.
- **Kept with every valuation run** (`portfolio_value`, immutable, EUR):
  - **Bridge:** portfolio value = inventory value + the run's MtM of its GoO trades − give-away − transfer fees −
    expiry haircut. A database check holds the identity.
  - **Never fails a run:** it is computed in a savepoint. It is left out when allocation is not available or cannot
    value the book, or when the base currency has no rate to EUR. Either way the run itself goes ahead unchanged.
  - **On the results:** each GoO sale's result carries its cheapest-to-deliver price and the MtM at that price
    (`ctd_price_eur`, `ctd_mtm_base`). They are shown, never summed into the run.
- **Portfolio explain** (`portfolio_explain`, kept at sign-off against the prior official run, like the P&L explain):
  - **Causes:**
    - **Trade MtM:** the change in the GoO trades' MtM.
    - **Inventory price:** certificates held in both runs at their change in value per MWh (by bucket and year, on
      the smaller quantity).
    - **Inventory in and out:** the rest of the change in inventory value: received, delivered, cancelled, expired or
      moved to another bucket.
    - **Give-away, reallocation (transfer fees) and expiry (haircut):** each cost's change, with the sign it has on
      the value.
    - **Unexplained:** takes the rounding.
  - **Totals:** the causes add up to the change exactly.
  - **Realised give-away:** `PortfolioValueQuery.realisedGiveAway`, the plans approved in the period, shown with the explain.
- **Official figures stay on bucket curves (D10):** official MtM, the run total, the ledger and period end, EMIR VALU
  and credit exposure. `ExplainComponent` and the P&L explain are unchanged.
- **API and UI:**
  - **API:** `GET /api/v1/valuation/runs/{id}/portfolio` (404 when the run has none) and
    `GET /api/v1/valuation/portfolio-explain`.
  - **UI:** the run detail gets a "Portfolio" tab with the bridge as a waterfall, and the explain tab gets a
    portfolio section.
- **Dependencies:** valuation gains `allocation :: api`. Allocation does not depend on valuation, so no cycle forms.

## Consequences
- **Two views side by side:** management sees what the book is worth as inventory and sales together, and how much
  of a sale's margin a specific certificate consumed. Accounting keeps one price per product.
- **Cheapest to deliver is the allocated average** (D11). The marginal figure for quoting a new sale is the shadow
  price of ADR 0035 (M2) or available to sell (ADR 0036).
- **Known limits:**
  - A run for a past date values today's holdings at that date's prices. A rerun after the book changed gives a
    different portfolio value for the same date, which is why each run keeps its own.
  - Future supply (ADR 0038) is not in the portfolio value. Purchases are in it through their trade MtM; expected
    issuance is not booked anywhere.
  - Side constraints of obligations are not in the valuation-grade allocation, so it may be cheaper than any plan
    that meets them.
