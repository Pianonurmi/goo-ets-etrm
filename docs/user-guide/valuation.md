# Valuation

A **valuation run** values every open trade at the official curves of a day: mark to market by trade and delivery.

![Valuation runs](../images/valuation-runs.png)

The page opens with four cards: the official mark to market of the latest official run, the latest run and its
status, the runs awaiting sign-off, and the exceptions in the latest run (deliveries without a price or rate). Three
tabs follow: **Runs**, **Official run** and **P&L explain**.

- **Run valuation** (on the **Runs** tab, with a **Valuation date**) starts a run. Runs are submitted by middle
  office or risk and made official by a second person.
- Open a run to see its key facts, a **Lifecycle** timeline and the **Results by delivery**, which you can filter by
  book. **Submit for sign-off** sends a draft to the inbox; **Sign-off task** opens the task.
- **P&L explain** compares two official runs, by cause: new trades, amendments, cancellations, deliveries, price
  moves, FX moves and what stays unexplained.
- **Official run** finds the official run of a date (or the latest before it).
- In the results grid, **CTD (EUR/MWh)** and **MtM at CTD** appear for GoO sales; see [Portfolio value](#portfolio-value).

> **Try it in the demo:** compare the last two official runs on the P&L explain.

## Portfolio value

Open a run and choose the **Portfolio** tab to see what the whole book is worth, not just the trades. It is a
**management view**: the official mark to market, the ledger, EMIR reporting and credit exposure stay on the bucket
curves and do not change. Runs without a portfolio value show a short note instead.

![A run's portfolio value](../images/valuation-portfolio.png)

The **bridge** is a waterfall from the certificates you hold to the portfolio value, all in EUR:

- **Inventory value**: the certificates held, each at the best price it qualifies for.
- **Trade MtM**: the official mark to market of the GoO trades, in EUR.
- **Give-away**, **Transfer fees** and **Expiry haircut**: what it costs to deliver the open obligations from the book,
  on the valuation-grade allocation. Give-away is the value of certificates that are better than the obligation asks
  for.
- **Portfolio value** is the inventory value plus the trade MtM, less those three costs.

The cards above the bridge show the portfolio value, the inventory value, the MWh held and the **Shortfall**: MWh the
obligations need that the book cannot deliver. A note says how many MWh have no price.

Once the run is official, **Change against the prior official run** explains the move by cause:

| Cause | What moved |
|---|---|
| Trade MtM | The trades' mark to market |
| Inventory prices | Prices of the certificates already held |
| Certificates in and out | Certificates received or leaving the book |
| Give-away | The cost of delivering with better certificates than needed |
| Reallocation | A different allocation of certificates to obligations |
| Expiry | Certificates that expired or are about to |
| Unexplained | What is left over |

Below, the **Certificates by product bucket** and **Open obligations** tables show the parts. For each obligation,
**CTD (EUR/MWh)** is the cheapest to deliver: the average value per MWh of the certificates that deliver it. It is set
against the **Sale mark**, and the difference is the **Give-away**. The results grid on the **Results** tab shows the
same CTD price and the **MtM at CTD** for a GoO sale. Both are for information only.

On the **P&L explain** tab, **Portfolio explain** adds the causes in total for the same two dates, the portfolio value of
each official run in between, and the give-away handed over by allocation plans approved in the period.

> **Try it in the demo:** open the latest official run and choose **Portfolio**.
