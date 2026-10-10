# Market data

| Concept | Meaning |
|---|---|
| Instrument | Something with a price: `EUA_SPOT`, `EUA_FUTURE` (a listed futures contract), `EUA_FORWARD`, `GOO_VINTAGE` (a product bucket and production year), `INDEX`, `RATE` |
| Alias | A vendor's or broker's code for an instrument, per source, so their files import unchanged |
| Quote | A price of an instrument on a date: `SETTLEMENT`, `MID`, `BID`, `ASK`, `CLOSE` or `FIXING`, with source and quality |
| FX rate | Units of the quote currency per unit of the base currency on a date, per source (ECB, an import, manual) |
| Import format | How a file is read (see below) |
| Price index | An instrument's quotes fixed by a rule (`ON_DATE`, `PRIOR_DAY`, `MONTH_AVERAGE`) for INDEX-priced trade legs |

## Quality and corrections
- A price moving more than `etrm.marketdata.max-move-pct` (default 25 %) from the previous one is `SUSPECT`. A
  second person acknowledges it with a note (`POST /quotes/{id}/acknowledge`). Until then readers do not use it.
- A new value for the same instrument, date, type and source supersedes the current row. Reads take `knownAt` to see
  values as they were known then (P&L explain and restated valuations rely on this).

## FX
`FxRates.rate(date, from, to)` tries, in order:
- the direct rate;
- the inverse of the opposite rate;
- a cross through EUR.

Each rate used is the latest on or before the date, and at most `etrm.marketdata.fx-max-staleness-days` (default 10)
old. The result says which path was used and how old the oldest rate is.

## Import formats
| Kind | File | Mapping |
|---|---|---|
| `FX_ECB_XML` | ECB reference rates (`eurofxref-daily.xml`, `-hist-90d.xml`, `-hist.xml`) | constant `base` (EUR) |
| `FX_WIDE_CSV` | one row per date, one column per currency (ECB `eurofxref-hist.csv`); `N/A` means no rate | `date` column, constant `base` |
| `QUOTES_LONG_CSV` | one price per row | `date`, `instrument`, `value`, optional `currency` and `quoteType` columns; constants for what the file lacks |

Each format sets the delimiter, header lines to skip, date format and decimal separator.

Seeded formats:
- `ECB_DAILY_XML`
- `ECB_EUROFXREF_HIST`
- `GENERIC_SETTLEMENT_CSV` (`Date,Contract,Settlement,Currency`)
- `GENERIC_BROKER_GOO_CSV` (`Date,Product,Mid` in EUR/MWh)

Each file is imported once per format (by content hash). Lines that cannot be read, or that name an unknown
instrument, are reported with their line number; the rest are imported.

## API
`/api/v1/market-data/...`:
- `instruments`
- `aliases`
- `quotes` (with `knownAt`), `quotes/suspect`
- `fx-rates`, `fx-rates/convert`
- `import-formats`
- `imports` (multipart upload)
- `indices`, `indices/{code}/fixings/{date}`
- `ecb/pull` (ADMIN)

Futures contracts are maintained at `/api/v1/products/futures-contracts`.
