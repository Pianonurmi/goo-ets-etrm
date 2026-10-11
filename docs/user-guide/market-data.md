# Market data

Prices and rates come from market data only; no other screen stores a price.

## Quotes, rates and imports

The page opens with three cards: **Suspect quotes**, **Active instruments** and **Active curves**. The tabs below
hold the detail.

![Market data quotes](../images/marketdata-quotes.png)

- **Quotes** per instrument and day (settlement, mid, bid, ask, close, fixing). Pick an instrument to see its
  quotes; **Record quote** adds one by hand. The **Suspect quotes** tab lists the flagged ones; give a reason and
  **Acknowledge** a quote that is right.
- **FX rates**: direct, inverse or across euros, with a staleness limit. **Pull ECB rates** loads the day's rates.
- **File imports** load quotes from files; **Import formats** map a file's columns (JSON).
- **Instruments** (EUA spot and futures, GoO vintages, indices) and their **aliases** in outside sources
  (**Add alias**).
- **Indices** are fixed from an instrument's quotes by a rule (on the date, the day before, or the month's average).
  **Fixing lookup** shows the fixing of an index for a date.

## Curves

A **curve** gives forward prices by delivery period for one product, built from the quotes of a day. A curve
snapshot becomes **official** when a second person signs it off in the inbox.

![Curves and snapshots](../images/marketdata-curves.png)

- **New curve** (in the page header) defines one (product or bucket, currency, quote types, staleness limit and an
  optional proxy curve).
- Choose the **Curve** and the **As of** date; **Build snapshot** sits next to them and builds a snapshot from that
  day's quotes. It opens the snapshot.
- A snapshot's page shows its key facts (points, gaps, currency) and a **Lifecycle** timeline: built, submitted for
  sign-off, signed off as official. **Submit for sign-off** sends a draft to the inbox; **Sign-off task** opens the
  task. On a draft you can **Override** a pillar's price with a note.
- **Official curve on** the chosen date shows the official snapshot; **Price lookup** reads a forward EUA price or a
  GoO vintage price from the official curves.

> **Try it in the demo:** as *Rhea Risk*, open the EUA forward curve and look up the price for next December.
