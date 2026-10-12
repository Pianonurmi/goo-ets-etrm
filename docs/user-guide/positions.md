# Positions

## GoO positions

The **Guarantees of origin** tab opens with four cards: **Live certificates**, **Open obligations**, **Covered** and
**Shortfall**, all in MWh. Below them are the **Expiry ladder (MWh by month)** and a table by product bucket and
vintage: live, held, reserved and in-transfer MWh, what expires within 90 days, and the number of lots. **Shortfall** is the open demand that the
certificates held cannot cover.

![GoO positions with the expiry ladder](../images/positions-goo.png)

## Available to sell

On the **Guarantees of origin** tab, switch the view from **Position** to **Available to sell** to see how much of
each bucket and vintage the entity could still sell. A row is a bucket and vintage with the MWh **Held**; there is
one column for each delivery month-end, holding the most a new sale for that date could receive from the
certificates held without taking anything from sales already agreed. Existing sales may be moved onto other
certificates that serve them too.

The cells are upper bounds on the same supply, so they **do not add up**: a certificate counted in one row can serve
a sale from another row's criteria. They also leave out device caps and the minimum shares of existing sales. The
first time an entity is opened the figures take a moment to prepare; the time they were worked out is shown under the
table. The view is open to traders, middle office, back office and risk.

The same figures check a sale while you capture it; see [Available to sell](trading.md#available-to-sell).

## EU ETS position

The **EU ETS** tab shows allowances held against the entity's compliance obligation, for the **Compliance year** you
choose. Four cards give the net position (long or short), allowances held, emissions and the surrender deadline.
The table lists the same figures per installation.

## Snapshots

Positions are kept as snapshots at the end of each day. The **Snapshots** tab charts them over a period; **Take
snapshots now** stores today's positions (middle office, back office, risk, accounting).
