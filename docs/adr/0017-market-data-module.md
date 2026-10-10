# ADR 0017: Market data in its own module, imported through formats defined as data

- Status: accepted
- Date: 2026-10-10

## Context
Increment 3 values trades and inventory, converts amounts between currencies and fixes index prices. Until now:
- FX rates were a table inside the ledger, entered by hand, used only for postings, and looked up only in the exact
  direction entered;
- allocation kept its own latest-only marks;
- settlement fixed index prices by hand after delivery.

No market data vendor has been chosen. This build environment cannot reach the exchanges or the ECB.

## Decision
- A `marketdata` module owns:
  - instruments (EUA spot, futures and forwards, GoO vintages per bucket, indices, rates) with vendor aliases;
  - prices and FX rates;
  - import formats and imports;
  - price indices and their fixings.

  It depends only on reference data, product and workflow, so trade, settlement, allocation, valuation and the
  ledger can all read from it without cycles.
- Prices and rates are bitemporal. A correction supersedes the current row rather than overwriting it, so values can
  be read as they were known at an instant (`knownAt`). Storing the same value again is a no-op.
- A price that moves more than `max-move-pct` from the previous one is held as `SUSPECT`. It is not used until a
  second person acknowledges it.
- FX conversion (`FxRates`) tries the direct rate, then the inverse, then a cross through EUR. Each leg is the latest
  rate on or before the date and at most `fx-max-staleness-days` old. The ledger posts with it, and trade converts
  its approval threshold with it. The ledger's own rate table is migrated and dropped.
- Files are read through import formats stored as data:
  - kinds: ECB XML, wide CSV with one column per currency, long CSV with one price per row;
  - each format sets the delimiter, header lines, date and decimal format, column mapping and constants;
  - a file is imported once per format (content hash); unreadable or unknown lines are reported, not fatal.
- An ECB adapter pulls the daily reference rate file on working days and imports it through the `ECB_DAILY_XML`
  format. A pull and an upload of the same document are therefore the same import. It is disabled by default
  (`etrm.marketdata.ecb.enabled`), retries only safe GETs, and reports the age of the newest rate as a metric.

## Consequences
- Prices from any vendor are loaded by defining a format and aliases, without code.
- The ECB adapter was tested against a local stub of the published file layout. It must be checked against the
  live ECB service once outbound access to `www.ecb.europa.eu` is allowed.
- Futures contracts (venue, delivery date, lot size) are catalogue data in `product`. Market data and trades refer
  to them by code.
